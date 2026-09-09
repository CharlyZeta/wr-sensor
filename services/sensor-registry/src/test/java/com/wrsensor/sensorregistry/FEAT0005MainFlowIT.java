package com.wrsensor.sensorregistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Integration test — Main Flow FEAT-0005 (DELETE /api/sensores/{id}, baja logica).
 * Test ID: {@code integration-test:FEAT-0005-main} (+ assertions AC-001..AC-008).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0005MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withNetwork(NETWORK).withDatabaseName("wrsensor")
            .withUsername("wrsensor").withPassword("wrsensor").withReuse(true);

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK).withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/wrsensor");
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        RABBIT.start();
    }

    @AfterAll
    static void stopContainers() {
        POSTGRES.stop();
        RABBIT.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    DatabaseClient db;

    private WebTestClient webClient;
    private String adminToken;
    private String viewerToken;

    private static final UUID ID_ACTIVO = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_INACTIVO = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_INEXISTENTE = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

    private static final String INSERT = """
            INSERT INTO sensor (
                id, codigo, nombre, tipo, latitud, longitud, unidad_medida, estado,
                histeresis, frecuencia_reporte_segundos, fecha_instalacion,
                rango_normal_min, rango_normal_max,
                rango_warning_min, rango_warning_max,
                rango_critical_min, rango_critical_max
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)
            """;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        adminToken = TestTokens.login(webClient,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_ADMIN,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_ADMIN);
        viewerToken = TestTokens.login(webClient,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_VIEWER,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_VIEWER);
        db.sql("TRUNCATE TABLE sensor").fetch().rowsUpdated()
                .then(insertSeed(ID_ACTIVO, "S-DET-A", "ACTIVO"))
                .then(insertSeed(ID_INACTIVO, "S-DET-I", "INACTIVO"))
                .block();
    }

    private Mono<Long> insertSeed(UUID id, String codigo, String estado) {
        return db.sql(INSERT)
                .bind(0, id).bind(1, codigo).bind(2, "seed").bind(3, "RIO")
                .bind(4, new BigDecimal("-29.15")).bind(5, new BigDecimal("-59.65"))
                .bind(6, "METROS").bind(7, estado).bind(8, new BigDecimal("0.5")).bind(9, 60)
                .bind(10, LocalDateTime.ofInstant(Instant.parse("2024-05-01T00:00:00Z"), ZoneOffset.UTC))
                .bind(11, new BigDecimal("4")).bind(12, new BigDecimal("6"))
                .bind(13, new BigDecimal("2")).bind(14, new BigDecimal("8"))
                .bind(15, new BigDecimal("0")).bind(16, new BigDecimal("10"))
                .fetch().rowsUpdated();
    }

    private WebTestClient.ResponseSpec del(String id, String token) {
        WebTestClient.RequestHeadersSpec<?> req = webClient.delete().uri("/api/sensores/{id}", id);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        return req.exchange();
    }

    private WebTestClient.ResponseSpec get(String id, String token) {
        return webClient.get().uri("/api/sensores/{id}", id)
                .header("Authorization", "Bearer " + token)
                .exchange();
    }

    @Test
    @DisplayName("integration-test:FEAT-0005-main — DELETE admin → 204 y el sensor queda INACTIVO (fila intacta)")
    void mainFlow_bajaLogica() {
        del(ID_ACTIVO.toString(), adminToken)
                .expectStatus().isNoContent();

        get(ID_ACTIVO.toString(), adminToken)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("INACTIVO");
    }

    @Test
    @DisplayName("AF-02 / BR-003 / AC-002: DELETE con rol VIEWER → 403 INSUFFICIENT_ROLE")
    void ac002_viewer403() {
        del(ID_ACTIVO.toString(), viewerToken)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");
    }

    @Test
    @DisplayName("AF-01 / BR-003 / AC-003: DELETE sin Authorization → 401 UNAUTHENTICATED")
    void ac003_sinAuth401() {
        del(ID_ACTIVO.toString(), null)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("AF-04 / BR-002 / AC-004: id UUID valido inexistente → 404 SENSOR_NOT_FOUND")
    void ac004_inexistente404() {
        del(ID_INEXISTENTE.toString(), adminToken)
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_NOT_FOUND");
    }

    @Test
    @DisplayName("AF-03 / BR-001 / AC-005: id malformado → 400 SENSOR_INVALID_ID")
    void ac005_idMalformado400() {
        del("no-es-un-uuid", adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_ID");
    }

    @Test
    @DisplayName("AF-05 / BR-005 / AC-006: sensor ya INACTIVO → 204 idempotente y sigue INACTIVO")
    void ac006_reBajaIdempotente() {
        del(ID_INACTIVO.toString(), adminToken)
                .expectStatus().isNoContent();

        get(ID_INACTIVO.toString(), adminToken)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("INACTIVO");
    }

    @Test
    @DisplayName("BR-006 / AC-007: tras la baja el sensor sigue visible en el listado (estado INACTIVO)")
    void ac007_visibleEnListado() {
        del(ID_ACTIVO.toString(), adminToken).expectStatus().isNoContent();

        webClient.get().uri("/api/sensores")
                .header("Authorization", "Bearer " + adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[?(@.codigo == 'S-DET-A')].estado").isEqualTo("INACTIVO");
    }

    @Test
    @DisplayName("BR-007 / AC-008: reactivacion via PUT (FEAT-0004) estado=ACTIVO → 200 ACTIVO")
    void ac008_reactivacionPorPut() {
        del(ID_INACTIVO.toString(), adminToken).expectStatus().isNoContent(); // ya inactivo, no-op

        webClient.put().uri("/api/sensores/{id}", ID_INACTIVO.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of(
                        "estado", "ACTIVO",
                        "histeresis", new BigDecimal("0.5"),
                        "frecuenciaReporteSegundos", 60,
                        "rangoNormal", Map.of("min", 4, "max", 6),
                        "rangoWarning", Map.of("min", 2, "max", 8),
                        "rangoCritical", Map.of("min", 0, "max", 10)))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("ACTIVO");
    }
}

