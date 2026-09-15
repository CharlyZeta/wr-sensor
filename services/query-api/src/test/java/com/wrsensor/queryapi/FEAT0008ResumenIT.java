package com.wrsensor.queryapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0008: `GET /api/sensores/resumen` de punta a punta sobre
 * Postgres real (el `DISTINCT ON` de la hypertable se ejecuta de verdad) y un `sensor-registry`
 * stub HTTP con paginación keyset.
 *
 * <p>Cubre AC-009/AC-011/AF-05/AF-06 y BR-005/BR-006/BR-007, incluidos los roles del endpoint y la
 * ausencia de datos parciales cuando el registry falla.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // BR-007: limit de página chico para forzar varias páginas keyset contra el stub.
        "query.registry.limit-pagina=1",
        "query.registry.timeout-ms=2000",
        "query.registry.conexion-timeout-ms=1000"
})
class FEAT0008ResumenIT {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_SIN_LECTURAS = UUID.fromString("00000000-0000-4000-8000-00000000000d");
    private static final Instant BASE = Instant.parse("2026-09-14T12:00:00Z");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("IT_TIMESCALE_IMAGE", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("wrsensor_ingestion").withUsername("wrsensor").withPassword("wrsensor")
            .withReuse(true);

    private static HttpServer stubRegistry;
    private static int puertoRegistry;
    private static final List<String> CONSULTAS = new CopyOnWriteArrayList<>();
    private static volatile boolean registryCaido = false;

    @BeforeAll
    static void startAll() throws IOException {
        DB.start();
        stubRegistry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubRegistry.createContext("/", FEAT0008ResumenIT::atender);
        stubRegistry.start();
        puertoRegistry = stubRegistry.getAddress().getPort();
    }

    @AfterAll
    static void stopAll() {
        if (stubRegistry != null) {
            stubRegistry.stop(0);
        }
        DB.stop();
    }

    /** Stub del registry: login + listado keyset de a un sensor por página. */
    private static void atender(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        if ("/api/auth/login".equals(path)) {
            responder(ex, 200, "{\"token\":\"token-stub\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}");
            return;
        }
        if (registryCaido) {
            responder(ex, 503, "{\"code\":\"INTERNAL_ERROR\"}");
            return;
        }
        CONSULTAS.add(query == null ? "" : query);
        int offset = query != null && query.contains("cursor=")
                ? Integer.parseInt(query.substring(query.indexOf("cursor=p") + 8)) : 0;
        List<String> pagina = List.of(sensor(ID_A, "S-01"), sensor(ID_B, "S-02"),
                sensor(ID_SIN_LECTURAS, "S-03"));
        boolean hayMas = offset + 1 < pagina.size();
        responder(ex, 200, "{\"items\":[" + pagina.get(offset) + "],\"nextCursor\":"
                + (hayMas ? "\"p" + (offset + 1) + "\"" : "null") + "}");
    }

    private static String sensor(UUID id, String codigo) {
        return "{\"id\":\"" + id + "\",\"codigo\":\"" + codigo + "\",\"nombre\":\"Sensor " + codigo
                + "\",\"tipo\":\"TEMPERATURA\",\"latitud\":-34.60,\"longitud\":-58.40,"
                + "\"unidadMedida\":\"CELSIUS\",\"estado\":\"ACTIVE\",\"histeresis\":0.5,"
                + "\"frecuenciaReporteSegundos\":60,\"rangoNormal\":{\"min\":0,\"max\":10}}";
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":"
                + DB.getFirstMappedPort() + "/wrsensor_ingestion");
        r.add("spring.r2dbc.username", DB::getUsername);
        r.add("spring.r2dbc.password", DB::getPassword);
        r.add("query.registry.base-url", () -> "http://127.0.0.1:" + puertoRegistry);
    }

    @LocalServerPort
    int port;

    private WebTestClient webClient;
    private String tokenAdmin;
    private String tokenViewer;
    private String tokenAuditor;

    private static final String INSERT = "INSERT INTO lectura (sensor_id, ts, valor, unidad_medida, "
            + "severidad, calidad) VALUES ($1, $2, $3, $4, $5, $6)";

    @BeforeEach
    void setUp() throws Exception {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(20)).build();
        tokenAdmin = QueryTestTokens.mint(ID_A, "ADMIN");
        tokenViewer = QueryTestTokens.mint(ID_A, "VIEWER");
        tokenAuditor = QueryTestTokens.mint(ID_A, "AUDITOR");
        CONSULTAS.clear();
        registryCaido = false;

        io.r2dbc.postgresql.PostgresqlConnectionConfiguration cfg =
                io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(DB.getHost()).port(DB.getFirstMappedPort())
                        .database("wrsensor_ingestion").username("wrsensor").password("wrsensor")
                        .build();
        io.r2dbc.spi.ConnectionFactory cf = new io.r2dbc.postgresql.PostgresqlConnectionFactory(cfg);
        DatabaseClient db = DatabaseClient.create(cf);
        db.sql("CREATE TABLE IF NOT EXISTS lectura (sensor_id UUID NOT NULL, ts TIMESTAMPTZ NOT NULL, "
                + "valor NUMERIC(12,2) NOT NULL, unidad_medida VARCHAR(32) NOT NULL, "
                + "severidad VARCHAR(16), calidad VARCHAR(16) NOT NULL DEFAULT 'OK')")
                .fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();

        // A: tres lecturas (la última es la que debe ganar). B: una. C (S-03): ninguna.
        leer(db, ID_A, 1, BASE, "NORMAL", "OK");
        leer(db, ID_A, 2, BASE.plusSeconds(30), "WARNING", "OK");
        leer(db, ID_A, 3, BASE.plusSeconds(60), "CRITICAL", "SOSPECHOSA");
        leer(db, ID_B, 9.5, BASE.plusSeconds(99), "WARNING", "OK");
    }

    private static void leer(DatabaseClient db, UUID sensor, double valor, Instant ts,
                             String severidad, String calidad) {
        db.sql(INSERT).bind(0, sensor)
                .bind(1, OffsetDateTime.ofInstant(ts, ZoneOffset.UTC))
                .bind(2, new BigDecimal(String.valueOf(valor))).bind(3, "CELSIUS")
                .bind(4, severidad).bind(5, calidad)
                .fetch().rowsUpdated().block();
    }

    // ================= Main Flow =================

    @Test
    @DisplayName("integration-test:FEAT-0008-main — resumen con metadata del registry y última lectura real")
    void mainFlow_resumen() {
        var body = webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].codigo").isEqualTo("S-01")
                .jsonPath("$[0].tipo").isEqualTo("TEMPERATURA")
                .jsonPath("$[0].latitud").isEqualTo(-34.60)
                .jsonPath("$[0].longitud").isEqualTo(-58.40)
                .jsonPath("$[0].estado").isEqualTo("ACTIVE")
                .jsonPath("$[0].unidadMedida").isEqualTo("CELSIUS")
                // BR-006: la última lectura es la de ts máximo (BASE+60), no la primera
                .jsonPath("$[0].ultimaLectura.valor").isEqualTo(3.0)
                .jsonPath("$[0].ultimaLectura.severidad").isEqualTo("CRITICAL")
                .jsonPath("$[0].ultimaLectura.calidad").isEqualTo("SOSPECHOSA")
                .jsonPath("$[0].ultimaLectura.timestamp").exists()
                .jsonPath("$[1].codigo").isEqualTo("S-02")
                .jsonPath("$[1].ultimaLectura.valor").isEqualTo(9.5)
                // AF-05: el sensor sin lecturas aparece igual, con ultimaLectura null
                .jsonPath("$[2].codigo").isEqualTo("S-03")
                .jsonPath("$[2].ultimaLectura").doesNotExist();

        // BR-007: paginación keyset hasta agotar (limit-pagina=1 → 3 páginas)
        assertThat(CONSULTAS).hasSize(3);
        assertThat(CONSULTAS.get(0)).isEqualTo("limit=1");
        assertThat(CONSULTAS.get(1)).isEqualTo("limit=1&cursor=p1");
        assertThat(CONSULTAS.get(2)).isEqualTo("limit=1&cursor=p2");
    }

    // ================= AC-009 =================

    @Test
    @DisplayName("AC-009: el rol VIEWER también ve el resumen completo (mismo contenido que ADMIN)")
    void ac009_viewer() {
        String admin = webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        String viewer = webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenViewer)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(viewer).isEqualTo(admin).contains("S-01").contains("S-03");
        assertThat(viewer).contains("\"ultimaLectura\":null");
    }

    @Test
    @DisplayName("AC-009: sin token → 401; rol ajeno → 403 (roles del endpoint)")
    void ac009_roles() {
        webClient.get().uri("/api/sensores/resumen")
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenAuditor)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");
    }

    // ================= AC-011 / AF-06 =================

    @Test
    @DisplayName("AC-011/AF-06: registry en 5xx → 502 REGISTRY_UNAVAILABLE sin lista parcial")
    void ac011_registryCaido() {
        registryCaido = true;
        var body = webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("REGISTRY_UNAVAILABLE").doesNotContain("S-01");
    }

    @Test
    @DisplayName("AC-011: registry inaccesible (puerto cerrado) → 502 y no 500")
    void ac011_registryInaccesible() throws IOException {
        // el stub se baja: el puerto queda sin listener
        stubRegistry.stop(0);
        try {
            webClient.get().uri("/api/sensores/resumen")
                    .header("Authorization", "Bearer " + tokenAdmin)
                    .exchange().expectStatus().isEqualTo(502)
                    .expectBody().jsonPath("$.code").isEqualTo("REGISTRY_UNAVAILABLE");
        } finally {
            stubRegistry = HttpServer.create(new InetSocketAddress("127.0.0.1", puertoRegistry), 0);
            stubRegistry.createContext("/", FEAT0008ResumenIT::atender);
            stubRegistry.start();
        }
    }

    // ================= BR-005 =================

    @Test
    @DisplayName("BR-005: el resumen convive con los endpoints existentes (no los reemplaza)")
    void br005_noReemplazaEndpoints() {
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/actual").build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valor").isEqualTo(3.0);
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/lecturas").pathSegment("")
                        .queryParam("desde", BASE.minusSeconds(1).toString())
                        .queryParam("hasta", BASE.plusSeconds(120).toString())
                        .build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(3);
    }

    @Test
    @DisplayName("BR-005: el resumen devuelve latitud/longitud que el mapa puede pintar")
    void br005_coordenadas() {
        webClient.get().uri("/api/sensores/resumen")
                .header("Authorization", "Bearer " + tokenViewer)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].latitud").exists()
                .jsonPath("$[*].longitud").exists();
    }
}
