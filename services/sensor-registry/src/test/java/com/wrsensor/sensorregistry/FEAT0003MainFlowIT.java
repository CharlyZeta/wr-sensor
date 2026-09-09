package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorResponse;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0003 (GET /api/sensores/{id}, detalle).
 * Test ID: {@code integration-test:FEAT-0003-main} (+ assertions e2e AC-001..AC-007).
 *
 * <p>Levanta Postgres real (Testcontainers R2DBC) y RabbitMQ (mismo setup que
 * {@code FEAT0002MainFlowIT}; el publisher de eventos no se usa en GET pero el
 * contexto completo lo exige), arranca el Spring Boot app, siembra 2 sensores
 * deterministicos (uno ACTIVO y uno INACTIVO) y verifica punta a punta el
 * flujo principal + los flujos alternativos de FEAT-0003:
 * <ul>
 *   <li>Main Flow / AC-001: GET detalle con ADMIN → 200 con el SensorResponse completo.</li>
 *   <li>AC-002: con VIEWER → 200 (mismo acceso).</li>
 *   <li>AF-01/BR-004/AC-003: sin Authorization → 401 UNAUTHENTICATED.</li>
 *   <li>AF-02/BR-003/AC-004: rol OTHER → 403 INSUFFICIENT_ROLE.</li>
 *   <li>AF-03/BR-002/AC-005: UUID valido inexistente → 404 SENSOR_NOT_FOUND.</li>
 *   <li>AF-04/BR-001/AC-006: id malformado → 400 SENSOR_INVALID_ID.</li>
 *   <li>AF-05/BR-006/AC-007: sensor INACTIVO → 200 con estado INACTIVO (sin filtrar).</li>
 * </ul>
 *
 * <p>Seeding identico al de FEAT-0002 (INSERT espejo de
 * {@code SensorPersistenceAdapter.save}, binding naive-UTC {@code LocalDateTime}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0003MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withNetwork(NETWORK)
            .withDatabaseName("wrsensor")
            .withUsername("wrsensor")
            .withPassword("wrsensor")
            .withReuse(true);

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK)
            .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url",
                () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/wrsensor");
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
    private String otherToken; // minted con rol ajeno (403, AF-02/AC-004)

    private static final UUID ID_ACTIVO = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_INACTIVO = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_INEXISTENTE = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private static final Instant FECHA = Instant.parse("2024-05-01T00:00:00Z");

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
        webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        // FEAT-0006: autenticacion real — JWT via /api/auth/login (seed dev).
        adminToken = TestTokens.login(webClient,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_ADMIN,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_ADMIN);
        viewerToken = TestTokens.login(webClient,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_VIEWER,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_VIEWER);
        otherToken = TestTokens.mint(UUID.fromString("11111111-1111-4111-8111-111111111111"), "AUDITOR");
        db.sql("TRUNCATE TABLE sensor").fetch().rowsUpdated()
                .then(insertSeed(ID_ACTIVO, "S-DET-A", "ACTIVO"))
                .then(insertSeed(ID_INACTIVO, "S-DET-I", "INACTIVO"))
                .block();
    }

    private Mono<Long> insertSeed(UUID id, String codigo, String estado) {
        return db.sql(INSERT)
                .bind(0, id)
                .bind(1, codigo)
                .bind(2, "seed")
                .bind(3, "RIO")
                .bind(4, new BigDecimal("-29.15"))
                .bind(5, new BigDecimal("-59.65"))
                .bind(6, "METROS")
                .bind(7, estado)
                .bind(8, new BigDecimal("0.5"))
                .bind(9, 60)
                .bind(10, LocalDateTime.ofInstant(FECHA, ZoneOffset.UTC))
                .bind(11, new BigDecimal("4"))
                .bind(12, new BigDecimal("6"))
                .bind(13, new BigDecimal("2"))
                .bind(14, new BigDecimal("8"))
                .bind(15, new BigDecimal("0"))
                .bind(16, new BigDecimal("10"))
                .fetch().rowsUpdated();
    }

    /** FEAT-0006: ADMIN/VIEWER = JWT real via login; OTHER = token minted rol AUDITOR (→403). null = sin header. */
    private WebTestClient.ResponseSpec get(String id, String rol) {
        WebTestClient.RequestHeadersSpec<?> req = webClient.get().uri("/api/sensores/{id}", id);
        String bearer = "ADMIN".equals(rol) ? adminToken
                : "VIEWER".equals(rol) ? viewerToken
                : "OTHER".equals(rol) ? otherToken : null;
        if (bearer != null) {
            req = req.header("Authorization", "Bearer " + bearer);
        }
        return req.exchange();
    }

    @Test
    @DisplayName("integration-test:FEAT-0003-main — GET detalle (ADMIN) responde 200 con el SensorResponse completo")
    void mainFlow_detalleCompleto() {
        SensorResponse body = get(ID_ACTIVO.toString(), "ADMIN")
                .expectStatus().isOk()
                .expectBody(SensorResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(ID_ACTIVO);
        assertThat(body.codigo()).isEqualTo("S-DET-A");
        assertThat(body.nombre()).isEqualTo("seed");
        assertThat(body.tipo()).isEqualTo("RIO");
        assertThat(body.estado()).isEqualTo("ACTIVO");
        assertThat(body.unidadMedida()).isEqualTo("METROS");
        assertThat(body.histeresis()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(body.frecuenciaReporteSegundos()).isEqualTo(60);
        assertThat(body.fechaInstalacion()).isEqualTo(FECHA);
        assertThat(body.rangoNormal().min()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(body.rangoNormal().max()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(body.rangoWarning().min()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(body.rangoCritical().max()).isEqualByComparingTo(new BigDecimal("10"));
    }

    @Test
    @DisplayName("AC-002: GET detalle con rol VIEWER → 200 (mismo acceso que ADMIN)")
    void ac002_viewerPuedeLeerDetalle() {
        get(ID_ACTIVO.toString(), "VIEWER")
                .expectStatus().isOk()
                .expectBody(SensorResponse.class)
                .returnResult().getResponseBody();
    }

    @Test
    @DisplayName("AF-01 / BR-004 / AC-003: sin Authorization → 401 UNAUTHENTICATED")
    void ac003_sinAuth401() {
        get(ID_ACTIVO.toString(), null)
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("AF-02 / BR-003 / AC-004: rol OTHER → 403 INSUFFICIENT_ROLE")
    void ac004_rolFuera403() {
        get(ID_ACTIVO.toString(), "OTHER")
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");
    }

    @Test
    @DisplayName("AF-03 / BR-002 / AC-005: UUID valido inexistente → 404 SENSOR_NOT_FOUND")
    void ac005_inexistente404() {
        get(ID_INEXISTENTE.toString(), "ADMIN")
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SENSOR_NOT_FOUND");
    }

    @Test
    @DisplayName("AF-04 / BR-001 / AC-006: id malformado → 400 SENSOR_INVALID_ID")
    void ac006_idMalformado400() {
        get("no-es-un-uuid", "ADMIN")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SENSOR_INVALID_ID");
    }

    @Test
    @DisplayName("AF-05 / BR-006 / AC-007: sensor INACTIVO → 200 con estado INACTIVO (sin filtrar)")
    void ac007_inactivoVisible() {
        SensorResponse body = get(ID_INACTIVO.toString(), "ADMIN")
                .expectStatus().isOk()
                .expectBody(SensorResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.id()).isEqualTo(ID_INACTIVO);
        assertThat(body.estado()).isEqualTo("INACTIVO");
    }
}

