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
 * Integration test — Main Flow FEAT-0006 (POST /api/auth/login → JWT).
 * Test ID: {@code integration-test:FEAT-0006-main} (+ assertions AC-001..AC-008).
 *
 * <p>Levanta Postgres + RabbitMQ (Testcontainers), arranca la app completa; el
 * {@code DevUserSeeder} garantiza los usuarios dev (admin@wrsensor.local /
 * viewer@wrsensor.local, passwords Admin123!/Viewer123!). Se siembra un sensor
 * para verificar un endpoint protegido con JWT real (AC-006) y los rechazos
 * (AC-007/AC-008). Tokens "externos" (expirado / firma invalida / rol ajeno) se
 * mintean con {@link TestTokens} (mismo secret dev).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0006MainFlowIT {

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

    private static final UUID SENSOR_ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

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
        db.sql("TRUNCATE TABLE sensor").fetch().rowsUpdated()
                .then(db.sql(INSERT)
                        .bind(0, SENSOR_ID).bind(1, "S-DET-A").bind(2, "seed").bind(3, "RIO")
                        .bind(4, new BigDecimal("-29.15")).bind(5, new BigDecimal("-59.65"))
                        .bind(6, "METROS").bind(7, "ACTIVO").bind(8, new BigDecimal("0.5")).bind(9, 60)
                        .bind(10, LocalDateTime.ofInstant(Instant.parse("2024-05-01T00:00:00Z"), ZoneOffset.UTC))
                        .bind(11, new BigDecimal("4")).bind(12, new BigDecimal("6"))
                        .bind(13, new BigDecimal("2")).bind(14, new BigDecimal("8"))
                        .bind(15, new BigDecimal("0")).bind(16, new BigDecimal("10"))
                        .fetch().rowsUpdated())
                .block();
    }

    private WebTestClient.ResponseSpec login(String email, String password) {
        return webClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange();
    }

    /** Login OK y devuelve el token (falla el test si el login no es 200). */
    private String tokenDe(String email, String password) {
        byte[] body = login(email, password)
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("token ausente en response login: " + json);
        return m.group(1);
    }

    private WebTestClient.ResponseSpec getProtected(UUID id, String headerAuthorization) {
        WebTestClient.RequestHeadersSpec<?> req = webClient.get().uri("/api/sensores/{id}", id.toString());
        if (headerAuthorization != null) req = req.header("Authorization", headerAuthorization);
        return req.exchange();
    }

    @Test
    @DisplayName("integration-test:FEAT-0006-main — login admin → 200 {token, rol=ADMIN, expiraEnSegundos} y el token decodifica rol=ADMIN con exp futuro")
    void mainFlow_loginAdminEmiteJwt() {
        login("admin@wrsensor.local", "Admin123!")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.rol").isEqualTo("ADMIN")
                .jsonPath("$.expiraEnSegundos").isEqualTo(3600);

        String token = tokenDe("admin@wrsensor.local", "Admin123!");
        String payload = TestTokens.decodePayload(token);
        org.assertj.core.api.Assertions.assertThat(payload).contains("\"rol\":\"ADMIN\"");
        long exp = TestTokens.expOf(token);
        org.assertj.core.api.Assertions.assertThat(exp).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("AC-002: login viewer → 200 con rol=VIEWER")
    void ac002_loginViewer() {
        login("viewer@wrsensor.local", "Viewer123!")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rol").isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("AF-03 / AC-003: password incorrecta → 401 INVALID_CREDENTIALS")
    void ac003_passwordIncorrecta() {
        login("admin@wrsensor.local", "wrong-password")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("AF-03 / AC-004: email inexistente → 401 INVALID_CREDENTIALS (indistinguible)")
    void ac004_emailInexistente() {
        login("nadie@wrsensor.local", "Admin123!")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("AF-01 / AF-02 / AC-005: email malformado o password vacia → 400 SENSOR_INVALID_REQUEST")
    void ac005_bodyInvalido() {
        login("no-es-un-email", "Admin123!")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_REQUEST");

        login("admin@wrsensor.local", "")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_REQUEST");
    }

    @Test
    @DisplayName("BR-004 / AC-006: endpoint protegido con JWT real (ADMIN) → 200")
    void ac006_protegidoConTokenValido() {
        String token = tokenDe("admin@wrsensor.local", "Admin123!");
        getProtected(SENSOR_ID, "Bearer " + token)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.codigo").isEqualTo("S-DET-A");
    }

    @Test
    @DisplayName("AF-04 / BR-004 / AC-007: token expirado o de firma invalida → 401 UNAUTHENTICATED")
    void ac007_tokenExpiradoOFirmaInvalida() {
        UUID sub = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String expired = TestTokens.mint(sub, "ADMIN", Instant.now().getEpochSecond() - 60, TestTokens.DEV_SECRET);
        getProtected(SENSOR_ID, "Bearer " + expired)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");

        String badSignature = TestTokens.mint(sub, "ADMIN", Instant.now().getEpochSecond() + 3600, "otro-secreto");
        getProtected(SENSOR_ID, "Bearer " + badSignature)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("AF-05 / BR-006 / AC-008: rol literal legacy 'Bearer ADMIN' → 401 (sin camino literal)")
    void ac008_literalLegacyRechazado() {
        getProtected(SENSOR_ID, "Bearer ADMIN")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }
}
