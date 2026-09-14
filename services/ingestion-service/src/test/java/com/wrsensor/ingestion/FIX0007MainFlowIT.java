package com.wrsensor.ingestion;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FIX-0007 (Main Flow + AC-003, AC-004, AC-006, AC-010) con Postgres y
 * RabbitMQ reales y un registry stub controlable (puede responder OK, 500 o devolver el sensor
 * INACTIVO). Cubre: persistencia normal, last-known-good con config vencida, frescura de la cache
 * (sensor desactivado se ve), DLQ con motivo REGISTRY_UNAVAILABLE y el endpoint de estado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ingestion.ventana-segundos=3600",
        "ingestion.outbox.intervalo-ms=3600000",
        "ingestion.particiones.total=4",
        "ingestion.registry.cache.ttl-segundos=1",
        "ingestion.registry.timeout-ms=2000",
        "ingestion.registry.circuito.fallos-para-abrir=3"
})
class FIX0007MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000f7");
    private static final int REGISTRY_PORT = 18165;
    private static final String DLQ = "queue.sensor.lecturas.dlq";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("IT_TIMESCALE_IMAGE", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"))
            .withNetwork(NETWORK).withDatabaseName("wrsensor_ingestion")
            .withUsername("wrsensor").withPassword("wrsensor").withReuse(true);

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK)
            .withPluginsEnabled("rabbitmq_consistent_hash_exchange")
            .withReuse(true);

    private static HttpServer registry;
    private static final AtomicReference<String> RESPUESTA_SENSOR = new AtomicReference<>();

    private static final String ACTIVO = "{\"id\":\"" + A + "\",\"codigo\":\"PARANA-RECONQUISTA\","
            + "\"estado\":\"ACTIVO\",\"unidadMedida\":\"METROS\","
            + "\"rangoNormal\":{\"min\":4,\"max\":6},\"rangoWarning\":{\"min\":2,\"max\":8},"
            + "\"rangoCritical\":{\"min\":0,\"max\":10}}";
    private static final String INACTIVO = ACTIVO.replace("\"ACTIVO\"", "\"INACTIVO\"");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":"
                + DB.getFirstMappedPort() + "/wrsensor_ingestion");
        r.add("spring.r2dbc.username", DB::getUsername);
        r.add("spring.r2dbc.password", DB::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("ingestion.registry.base-url", () -> "http://localhost:" + REGISTRY_PORT);
    }

    @BeforeAll
    static void startAll() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> responder(ex, 200,
                "{\"token\":\"t\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}"));
        registry.createContext("/api/sensores/", ex -> {
            String r = RESPUESTA_SENSOR.get();
            if (r == null) {
                responder(ex, 200, ACTIVO);
            } else if ("500".equals(r)) {
                responder(ex, 500, "{\"code\":\"DOWN\",\"message\":\"stub caido\"}");
            } else if ("404".equals(r)) {
                responder(ex, 404, "");
            } else {
                responder(ex, 200, r);
            }
        });
        registry.start();
        DB.start();
        RABBIT.start();
    }

    @AfterAll
    static void stopAll() {
        if (registry != null) {
            registry.stop(0);
        }
        DB.stop();
        RABBIT.stop();
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Autowired
    DatabaseClient db;

    @Autowired
    com.wrsensor.ingestion.application.service.IngestorLecturas ingestor;

    @Autowired
    com.wrsensor.ingestion.domain.CircuitoResiliencia circuito;

    @Autowired
    com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores cacheConfig;

    @LocalServerPort
    int puertoGateway;

    private Connection conexion;
    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        RESPUESTA_SENSOR.set(null);
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        conexion = cf.newConnection();
        channel = conexion.createChannel();
        channel.exchangeDeclare("sensor.lecturas", "topic", true);
        ingestor.setUltimaSeveridad(A, null);
        ingestor.setUltimaSecuencia(A, null);
        circuito.reset();        // el breaker y la cache son singletons compartidos entre tests
        cacheConfig.limpiar();
        db.sql("DELETE FROM outbox_alerta").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura_procesada").fetch().rowsUpdated())
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();
        purgar();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.close();
        }
        if (conexion != null) {
            conexion.close();
        }
    }

    private void purgar() {
        try (Channel ch = conexion.createChannel()) {
            ch.queuePurge(DLQ);
        } catch (Exception ignored) {
            // DLQ aún no existe
        }
    }

    private static void esperarHasta(BooleanSupplier cond, long timeoutMs) {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite && !cond.getAsBoolean()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void publicar(String valor) throws IOException {
        String msg = "{\"sensorId\":\"" + A + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valor\":" + valor + ",\"unidadMedida\":\"METROS\"}";
        channel.basicPublish("sensor.lecturas", "lectura." + A, null,
                msg.getBytes(StandardCharsets.UTF_8));
    }

    private long filas() {
        Long n = db.sql("SELECT count(*) AS n FROM lectura")
                .map((r, m) -> r.get("n", Long.class)).first().block();
        return n == null ? 0 : n;
    }

    private GetResponse basicGetDlq() {
        try {
            return channel.basicGet(DLQ, true);
        } catch (IOException e) {
            return null;
        }
    }

    private String xRechazo(GetResponse r) {
        return r == null || r.getProps() == null || r.getProps().getHeaders() == null
                || !r.getProps().getHeaders().containsKey("x-rechazo")
                ? null
                : new String((byte[]) r.getProps().getHeaders().get("x-rechazo"), StandardCharsets.UTF_8);
    }

    private String endpoint() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + puertoGateway + "/api/ingestion/resiliencia"))
                    .GET().build();
            HttpResponse<String> res = HttpClient.newHttpClient().send(req,
                    HttpResponse.BodyHandlers.ofString());
            return res.body();
        } catch (Exception e) {
            return "<ERROR " + e.getMessage() + ">";
        }
    }

    // ================= Main Flow =================

    @Test
    @DisplayName("Main Flow: registry OK → la lectura se procesa y persiste")
    void mainFlow() throws Exception {
        publicar("5.0");
        esperarHasta(() -> filas() == 1, 20000);
        assertThat(filas()).isEqualTo(1);
    }

    // ================= AC-004: frescura de la cache =================

    @Test
    @DisplayName("AC-004: con TTL vencido se refresca la config y un sensor INACTIVO se ve")
    void ac004_frescura() throws Exception {
        publicar("5.0");                       // cachea ACTIVO
        esperarHasta(() -> filas() == 1, 20000);
        assertThat(filas()).isEqualTo(1);

        RESPUESTA_SENSOR.set(INACTIVO);        // el sensor pasa a INACTIVO
        Thread.sleep(1200);                    // > TTL (1 s)

        publicar("5.0");
        GetResponse rechazado = esperarDlq();
        assertThat(xRechazo(rechazado)).as("la lectura del sensor INACTIVO se rechaza")
                .isEqualTo("SENSOR_INACTIVE");
        assertThat(filas()).as("no se persiste la lectura del sensor desactivado").isEqualTo(1);
    }

    // ================= AC-003: last-known-good =================

    @Test
    @DisplayName("AC-003: registry caído + config vencida → se usa la copia vencida y se procesa")
    void ac003_lastKnownGood() throws Exception {
        publicar("5.0");                       // cachea ACTIVO
        esperarHasta(() -> filas() == 1, 20000);

        RESPUESTA_SENSOR.set("500");           // registry cae
        Thread.sleep(1200);                    // > TTL

        publicar("5.0");
        esperarHasta(() -> filas() == 2, 20000);
        assertThat(filas()).as("la lectura se procesa con la config vencida (last-known-good)")
                .isEqualTo(2);
    }

    // ================= AC-006: DLQ con motivo específico =================

    @Test
    @DisplayName("AC-006: registry caído + sensor nunca visto → DLQ con REGISTRY_UNAVAILABLE")
    void ac006_sinCopiaRegistryCaido() throws Exception {
        RESPUESTA_SENSOR.set("500");
        publicar("5.0");
        GetResponse rechazado = esperarDlq();

        assertThat(xRechazo(rechazado)).as("motivo específico, no INFRA_ERROR")
                .isEqualTo("REGISTRY_UNAVAILABLE");
        assertThat(filas()).isZero();
    }

    // ================= AC-010: endpoint de estado =================

    @Test
    @DisplayName("AC-010: el endpoint de resiliencia refleja el estado y no expone datos de sensores")
    void ac010_endpoint() throws Exception {
        publicar("5.0");
        esperarHasta(() -> filas() == 1, 20000);

        String json = endpoint();
        assertThat(json).contains("\"circuito\":\"CERRADO\"")
                .contains("\"cacheTamano\":1");
        assertThat(json).as("sin datos de sensores ni credenciales")
                .doesNotContain(A.toString(), "PARANA-RECONQUISTA", "email", "password", "token");
    }

    private GetResponse esperarDlq() {
        AtomicReference<GetResponse> recibido = new AtomicReference<>();
        esperarHasta(() -> {
            if (recibido.get() != null) {
                return true;
            }
            GetResponse r = basicGetDlq();
            if (r != null && r.getProps() != null && r.getProps().getHeaders() != null
                    && r.getProps().getHeaders().containsKey("x-rechazo")) {
                recibido.set(r);
            }
            return false;
        }, 20000);
        return recibido.get();
    }
}
