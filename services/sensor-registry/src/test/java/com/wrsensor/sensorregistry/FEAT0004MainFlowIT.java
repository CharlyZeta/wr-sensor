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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Integration test — Main Flow FEAT-0004 (PUT /api/sensores/{id}).
 * Test ID: {@code integration-test:FEAT-0004-main} (+ assertions AC-001..AC-009).
 *
 * <p>Levanta Postgres + RabbitMQ (Testcontainers), siembra un sensor y verifica el
 * flujo punta a punta con JWT real (login admin/viewer, FEAT-0006).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0004MainFlowIT {

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

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");
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
                .then(db.sql(INSERT)
                        .bind(0, ID).bind(1, "S-DET-A").bind(2, "seed").bind(3, "RIO")
                        .bind(4, new BigDecimal("-29.15")).bind(5, new BigDecimal("-59.65"))
                        .bind(6, "METROS").bind(7, "ACTIVO").bind(8, new BigDecimal("0.5")).bind(9, 60)
                        .bind(10, LocalDateTime.ofInstant(Instant.parse("2024-05-01T00:00:00Z"), ZoneOffset.UTC))
                        .bind(11, new BigDecimal("4")).bind(12, new BigDecimal("6"))
                        .bind(13, new BigDecimal("2")).bind(14, new BigDecimal("8"))
                        .bind(15, new BigDecimal("0")).bind(16, new BigDecimal("10"))
                        .fetch().rowsUpdated())
                .block();
    }

    private Map<String, Object> body(String estado, String histeresis, Integer frecuencia) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("estado", estado);
        b.put("histeresis", histeresis == null ? null : new BigDecimal(histeresis));
        b.put("frecuenciaReporteSegundos", frecuencia);
        b.put("rangoNormal", Map.of("min", 3, "max", 7));
        b.put("rangoWarning", Map.of("min", 1, "max", 9));
        b.put("rangoCritical", Map.of("min", 0, "max", 10));
        return b;
    }

    private WebTestClient.ResponseSpec put(String id, Map<String, Object> body, String token) {
        WebTestClient.RequestHeadersSpec<?> req = webClient.put().uri("/api/sensores/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        if (token != null) req = req.header("Authorization", "Bearer " + token);
        return req.exchange();
    }

    @Test
    @DisplayName("integration-test:FEAT-0004-main — PUT admin valido → 200 con config actualizada e identidad intacta")
    void mainFlow_updateValido() {
        put(ID.toString(), body("ACTIVO", "1.0", 120), adminToken)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(ID.toString())
                .jsonPath("$.codigo").isEqualTo("S-DET-A")
                .jsonPath("$.estado").isEqualTo("ACTIVO")
                .jsonPath("$.histeresis").isEqualTo(1.0)
                .jsonPath("$.frecuenciaReporteSegundos").isEqualTo(120)
                .jsonPath("$.rangoNormal.min").isEqualTo(3)
                .jsonPath("$.rangoNormal.max").isEqualTo(7)
                .jsonPath("$.rangoCritical.max").isEqualTo(10);
    }

    @Test
    @DisplayName("AF-02 / BR-003 / AC-002: PUT con rol VIEWER → 403 INSUFFICIENT_ROLE")
    void ac002_viewer403() {
        put(ID.toString(), body("ACTIVO", "1.0", 120), viewerToken)
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");
    }

    @Test
    @DisplayName("AF-01 / BR-003 / AC-003: PUT sin Authorization → 401 UNAUTHENTICATED")
    void ac003_sinAuth401() {
        put(ID.toString(), body("ACTIVO", "1.0", 120), null)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("AF-04 / BR-002 / AC-004: id UUID valido inexistente → 404 SENSOR_NOT_FOUND")
    void ac004_inexistente404() {
        put(ID_INEXISTENTE.toString(), body("ACTIVO", "1.0", 120), adminToken)
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_NOT_FOUND");
    }

    @Test
    @DisplayName("AF-03 / BR-001 / AC-005: id malformado → 400 SENSOR_INVALID_ID")
    void ac005_idMalformado400() {
        put("no-es-un-uuid", body("ACTIVO", "1.0", 120), adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_ID");
    }

    @Test
    @DisplayName("AF-05 / BR-006 / AC-006: histeresis -0.5 → 400 SENSOR_INVALID_HISTERESIS")
    void ac006_histeresisNegativa() {
        put(ID.toString(), body("ACTIVO", "-0.5", 120), adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_HISTERESIS");
    }

    @Test
    @DisplayName("AF-05 / BR-006 / AC-007: rangos que rompen la cadena → 400 SENSOR_INVALID_RANGES")
    void ac007_rangosInvalidos() {
        Map<String, Object> b = body("ACTIVO", "1.0", 120);
        b.put("rangoCritical", Map.of("min", 5, "max", 9)); // critical.min(5) > warning.min(1)
        put(ID.toString(), b, adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_RANGES");
    }

    @Test
    @DisplayName("AF-05 / BR-007 / AC-008: estado INACTIVO → 400 SENSOR_INVALID_ESTADO")
    void ac008_inactivoRechazado() {
        put(ID.toString(), body("INACTIVO", "1.0", 120), adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_ESTADO");
    }

    @Test
    @DisplayName("AF-06 / BR-005 / AC-009: body incompleto o con campo inmutable → 400")
    void ac009_bodyInvalido() {
        // Campo obligatorio faltante (rangoWarning ausente) → code de dominio del campo
        // (criterio estructural del alta: SENSOR_INVALID_RANGES).
        Map<String, Object> sinWarning = body("ACTIVO", "1.0", 120);
        sinWarning.remove("rangoWarning");
        put(ID.toString(), sinWarning, adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_RANGES");

        // Campo inmutable presente (codigo) → SENSOR_INVALID_REQUEST
        // (@JsonIgnoreProperties(ignoreUnknown=false) → HttpMessageNotReadableException).
        Map<String, Object> conCodigo = body("ACTIVO", "1.0", 120);
        conCodigo.put("codigo", "OTRO-CODIGO");
        put(ID.toString(), conCodigo, adminToken)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_REQUEST");
    }
}
