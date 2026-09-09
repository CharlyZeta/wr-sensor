package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.service.CursorCodec;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorListResponse;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0002 (GET /api/sensores, paginacion keyset).
 * Test ID: {@code integration-test:FEAT-0002-main}.
 *
 * <p>Levanta Postgres real (Testcontainers R2DBC, postgres:16-alpine) y RabbitMQ
 * real, arranca el Spring Boot app completo (WebFlux + R2DBC), siembra 6 sensores
 * con fechas deterministas (un empate por {@code fecha_instalacion} con desempate
 * por {@code id}) y dispara GET /api/sensores con WebTestClient para verificar
 * punta a punta el flujo principal de paginacion:
 * <ul>
 *   <li>BR-004: orden DESC por {@code fechaInstalacion}, desempate ASC por {@code id}.</li>
 *   <li>BR-005: {@code nextCursor} presente solo si hay mas filas despues del
 *       ultimo item devuelto.</li>
 *   <li>BR-003 (transitivo): el cursor opaco porta {@code (fechaInstalacion, id)}
 *       — verificado por el encadenamiento correcto y por decode(id) == ultimo item.</li>
 *   <li>Encadenamiento: pagina 1 (limit=3, nextCursor no nulo) → cursor → pagina 2
 *       (sin nextCursor). Sin duplicados ni saltos entre ambas paginas.</li>
 * </ul>
 *
 * <p>Mirrors {@code FEAT0001MainFlowIT} en setup (mismos contenedores, mismas
 * propiedades dinamicas). Siembra via {@link DatabaseClient} reactivo
 * ({@code TRUNCATE} + {@code INSERT} espejo del {@code SensorPersistenceAdapter.save}
 * de FEAT-0001, mismo binding naive-UTC {@code LocalDateTime.ofInstant(..., UTC)})
 * para que la representacion almacenada sea identica a la de produccion y la tabla quede
 * en estado deterministico. Actua como rol ADMIN via header
 * {@code Authorization: Bearer ADMIN} (el {@code RolFilter} lo inyecta en el
 * Reactor Context; el emisor JWT real es FEAT-0006, aun no existe).
 *
 * <p>Stack (stack.md): JUnit 5 + Reactor Test + Testcontainers (R2DBC + RabbitMQ)
 * + WebTestClient. El SUT es 100% reactivo; el fixture bloquea el hilo del test
 * ({@code .block()}) solo para sembrar — precedente: {@code FEAT0001MainFlowIT}
 * bloquea igual (polling Rabbit con {@code basicGet}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0002MainFlowIT {

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
    private String viewerToken; // lazy (FEAT-0006)

    // Fechas deterministas. APR1 se repite (empate) → desempate ASC por id (BR-004).
    private static final Instant FEB1 = Instant.parse("2024-02-01T00:00:00Z");
    private static final Instant MAR1 = Instant.parse("2024-03-01T00:00:00Z");
    private static final Instant APR1 = Instant.parse("2024-04-01T00:00:00Z");
    private static final Instant MAY1 = Instant.parse("2024-05-01T00:00:00Z");
    private static final Instant JUN1 = Instant.parse("2024-06-01T00:00:00Z");

    /** Sensor semilla (id, codigo, fechaInstalacion). Campos no-clave constantes validos. */
    private record Seed(UUID id, String codigo, Instant fecha) {}

    // Orden resultante (DESC fecha, ASC id): JUN, MAY, APR-A, APR-B, MAR, FEB.
    // limit=3 → pagina1 = [S-JUN, S-MAY, S-APR-A] (nextCursor presente);
    //           pagina2 = [S-APR-B, S-MAR, S-FEB] (nextCursor ausente, ultima).
    private static final List<Seed> SEEDS = List.of(
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000001"), "S-JUN",   JUN1),
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000002"), "S-MAY",   MAY1),
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000003"), "S-APR-A", APR1),
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000004"), "S-APR-B", APR1),
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000005"), "S-MAR",   MAR1),
            new Seed(UUID.fromString("00000000-0000-4000-8000-000000000006"), "S-FEB",   FEB1)
    );

    /** INSERT espejo de SensorPersistenceAdapter.save (FEAT-0001): mismo binding de fecha_instalacion. */
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
        // ponytail: tests pueden bloquear el hilo del test para fixtures
        // (precedente: FEAT0001MainFlowIT bloquea con basicGet+Thread.sleep).
        db.sql("TRUNCATE TABLE sensor").fetch().rowsUpdated()
                .then(Flux.fromIterable(SEEDS).concatMap(this::insertSeed).then())
                .block();
    }

    private Mono<Long> insertSeed(Seed s) {
        return db.sql(INSERT)
                .bind(0, s.id())
                .bind(1, s.codigo())
                .bind(2, "seed")
                .bind(3, "RIO")
                .bind(4, new BigDecimal("-29.15"))
                .bind(5, new BigDecimal("-59.65"))
                .bind(6, "METROS")
                .bind(7, "ACTIVO")
                .bind(8, new BigDecimal("0.5"))
                .bind(9, 60)
                // Espejo de SensorPersistenceAdapter.save (FEAT-0002 fix): naive-UTC LocalDateTime.
                .bind(10, LocalDateTime.ofInstant(s.fecha(), ZoneOffset.UTC))
                .bind(11, new BigDecimal("4"))
                .bind(12, new BigDecimal("6"))
                .bind(13, new BigDecimal("2"))
                .bind(14, new BigDecimal("8"))
                .bind(15, new BigDecimal("0"))
                .bind(16, new BigDecimal("10"))
                .fetch().rowsUpdated();
    }

    @Test
    @DisplayName("integration-test:FEAT-0002-main — paginacion keyset encadena pagina 1 → 2 (ADMIN, limit=3)")
    void testMainFlow_paginaPorCursorEncadenaYOrdena() {
        // Page 1: ADMIN, sin cursor, limit=3 → 3 items en orden (BR-004), nextCursor presente (BR-005).
        SensorListResponse page1 = list(3, null, "ADMIN");

        assertThat(page1).isNotNull();
        assertThat(page1.items().stream().map(SensorResponse::codigo).toList())
                .as("BR-004: orden DESC por fechaInstalacion, desempate ASC por id (pagina 1)")
                .containsExactly("S-JUN", "S-MAY", "S-APR-A");
        assertThat(page1.items()).as("limit=3 respeta el tamano de pagina").hasSize(3);
        assertThat(page1.nextCursor()).as("BR-005: nextCursor presente habiendo mas filas").isNotNull();

        // Cross-check (BR-003): el cursor opaco decodifica al id del ultimo item de la pagina 1.
        assertThat(CursorCodec.decode(page1.nextCursor()).id())
                .as("cursor aporta el id del ultimo item devuelto")
                .isEqualTo(page1.items().get(2).id());

        // Page 2: reusar el nextCursor → siguiente pagina (BR-005: ultima → nextCursor ausente).
        SensorListResponse page2 = list(3, page1.nextCursor(), "ADMIN");

        assertThat(page2.items().stream().map(SensorResponse::codigo).toList())
                .as("BR-004: encadenamiento via cursor entrega la pagina siguiente, sin saltear el empate")
                .containsExactly("S-APR-B", "S-MAR", "S-FEB");
        assertThat(page2.items()).hasSize(3);
        assertThat(page2.nextCursor()).as("BR-005: nextCursor ausente en ultima pagina").isNull();

        // Sin duplicados ni saltos entre ambas paginas (6 ids unicos cubren toda la semilla).
        Set<UUID> seen = new LinkedHashSet<>();
        page1.items().forEach(s -> seen.add(s.id()));
        page2.items().forEach(s -> seen.add(s.id()));
        assertThat(seen).as("encadenamiento no duplica ni saltea").hasSize(6);
    }

    /** GET /api/sensores con limit/cursor opcionales y rol via header Bearer (JWT real). Devuelve la pagina tipada. */
    private SensorListResponse list(Integer limit, String cursor, String rol) {
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/sensores");
                    if (limit != null) b.queryParam("limit", limit);
                    if (cursor != null) b.queryParam("cursor", cursor);
                    return b.build();
                })
                .header("Authorization", "Bearer " + tokenDe(rol))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SensorListResponse.class)
                .returnResult()
                .getResponseBody();
    }

    /** FEAT-0006: ADMIN/VIEWER reales via login; OTHER = token minted con rol ajeno (403). */
    private String tokenDe(String rol) {
        if ("ADMIN".equals(rol)) return adminToken;
        if ("VIEWER".equals(rol)) {
            if (viewerToken == null) {
                viewerToken = TestTokens.login(webClient,
                        com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_VIEWER,
                        com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_VIEWER);
            }
            return viewerToken;
        }
        throw new IllegalArgumentException("rol no soportado: " + rol);
    }
}

