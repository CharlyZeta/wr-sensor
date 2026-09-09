package com.wrsensor.queryapi;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0013 (query-api e2e: histórico keyset,
 * /actual y WS tiempo real sobre Rabbit + TimescaleDB/Postgres).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0013MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_SIN_DATOS = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    private static final Instant BASE = Instant.parse("2026-09-09T12:00:00Z");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("IT_TIMESCALE_IMAGE", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"))
            .withNetwork(NETWORK).withDatabaseName("wrsensor_ingestion")
            .withUsername("wrsensor").withPassword("wrsensor").withReuse(true);

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK).withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":" + DB.getFirstMappedPort() + "/wrsensor_ingestion");
        r.add("spring.r2dbc.username", DB::getUsername);
        r.add("spring.r2dbc.password", DB::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @BeforeAll
    static void startAll() throws Exception {
        DB.start();
        RABBIT.start();
    }

    @AfterAll
    static void stopAll() {
        DB.stop();
        RABBIT.stop();
    }

    @LocalServerPort
    int port;

    private WebTestClient webClient;
    private Channel channel;
    private String tokenAdmin;
    private String tokenViewer;
    private String tokenAuditor;

    private static final String INSERT = "INSERT INTO lectura (sensor_id, ts, valor, unidad_medida, severidad) "
            + "VALUES ($1, $2, $3, $4, $5)";

    @BeforeEach
    void setUp() throws Exception {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        tokenAdmin = QueryTestTokens.mint(ID_A, "ADMIN");
        tokenViewer = QueryTestTokens.mint(ID_A, "VIEWER");
        tokenAuditor = QueryTestTokens.mint(ID_A, "AUDITOR");

        io.r2dbc.postgresql.PostgresqlConnectionConfiguration cfg =
                io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                        .host(DB.getHost()).port(DB.getFirstMappedPort())
                        .database("wrsensor_ingestion").username("wrsensor").password("wrsensor")
                        .build();
        io.r2dbc.spi.ConnectionFactory cf = new io.r2dbc.postgresql.PostgresqlConnectionFactory(cfg);
        DatabaseClient db = DatabaseClient.create(cf);
        db.sql("CREATE TABLE IF NOT EXISTS lectura (sensor_id UUID NOT NULL, ts TIMESTAMPTZ NOT NULL, "
                + "valor NUMERIC(12,2) NOT NULL, unidad_medida VARCHAR(32) NOT NULL, severidad VARCHAR(16) NOT NULL)")
                .fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then()
                .block();
        for (int i = 0; i < 5; i++) {
            db.sql(INSERT).bind(0, ID_A)
                    .bind(1, java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(i), ZoneOffset.UTC))
                    .bind(2, new BigDecimal(i + 1)).bind(3, "METROS").bind(4, "NORMAL")
                    .fetch().rowsUpdated().block();
        }
        db.sql(INSERT).bind(0, ID_B)
                .bind(1, java.time.OffsetDateTime.ofInstant(BASE.plusSeconds(99), ZoneOffset.UTC))
                .bind(2, new BigDecimal("9.5")).bind(3, "METROS").bind(4, "WARNING")
                .fetch().rowsUpdated().block();

        com.rabbitmq.client.ConnectionFactory rcf = new com.rabbitmq.client.ConnectionFactory();
        rcf.setHost(RABBIT.getHost());
        rcf.setPort(RABBIT.getFirstMappedPort());
        rcf.setUsername(RABBIT.getAdminUsername());
        rcf.setPassword(RABBIT.getAdminPassword());
        Connection conn = rcf.newConnection();
        channel = conn.createChannel();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) channel.close();
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("integration-test:FEAT-0013-main — historico keyset, /actual y WS realtime")
    void mainFlow_historicoActualWs() throws Exception {
        String desde = BASE.minusSeconds(1).toString();
        String hasta = BASE.plusSeconds(10).toString();

        // AC-001/AC-002: pagina 1 y 2 por cursor sin duplicados.
        WebTestClient.ResponseSpec p1 = webClient.get()
                .uri(uri -> uri.path("/api/sensores/{id}/lecturas").pathSegment("")
                        .queryParam("desde", desde).queryParam("hasta", hasta).queryParam("limit", 2)
                        .build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange();
        String body1 = new String(p1.expectBody().returnResult().getResponseBody(), StandardCharsets.UTF_8);
        assertThat(body1).contains("\"valor\":5").contains("\"valor\":4");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(body1);
        assertThat(m.find()).isTrue();
        String cursor1 = m.group(1);

        WebTestClient.ResponseSpec p2 = webClient.get()
                .uri(uri -> uri.path("/api/sensores/{id}/lecturas").pathSegment("")
                        .queryParam("desde", desde).queryParam("hasta", hasta).queryParam("limit", 2)
                        .queryParam("cursor", cursor1).build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenViewer)
                .exchange();
        String body2 = new String(p2.expectStatus().isOk().expectBody().returnResult().getResponseBody(),
                StandardCharsets.UTF_8);
        // Pagina 2 = [valor3, valor2]: sin duplicados de la pagina 1 (valores 5 y 4).
        assertThat(body2).contains("\"valor\":3").contains("\"valor\":2")
                .doesNotContain("\"valor\":5").doesNotContain("\"valor\":4");

        // AC-008: sin token → 401; rol ajeno → 403.
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/actual").build(ID_A.toString()))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/actual").build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAuditor)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");

        // AC-005: /actual devuelve el ts maximo (BASE+4).
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/actual").build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valor").isEqualTo(5);

        // AF-05 / AC-003: rango sin datos → [] sin nextCursor.
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/lecturas").pathSegment("")
                        .queryParam("desde", BASE.plusSeconds(100).toString())
                        .queryParam("hasta", BASE.plusSeconds(200).toString()).build(ID_B.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items.length()").isEqualTo(0)
                .jsonPath("$.nextCursor").doesNotExist();

        // AC-006: /actual sin lecturas → 404 SENSOR_NOT_FOUND.
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/actual").build(ID_SIN_DATOS.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_NOT_FOUND");

        // AF-04/AC-004: limit invalido y desde>hasta.
        webClient.get().uri(uri -> uri.path("/api/sensores/{id}/lecturas").pathSegment("")
                        .queryParam("desde", desde).queryParam("hasta", hasta).queryParam("limit", 1001)
                        .build(ID_A.toString()))
                .header("Authorization", "Bearer " + tokenAdmin)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_INVALID_LIMIT");

        // AC-007: WS tiempo real — suscribirse y publicar lectura del sensor A.
        CopyOnWriteArrayList<String> wsA = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        Disposable sub = client.execute(URI.create("ws://localhost:" + port + "/ws/sensores/" + ID_A), session ->
                        session.receive().map(msg -> msg.getPayloadAsText()).doOnNext(wsA::add).then())
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                .subscribe();
        esperar(900);
        String msg = "{\"sensorId\":\"" + ID_A + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valor\":7.77,\"unidadMedida\":\"METROS\"}";
        channel.basicPublish("sensor.lecturas", "lectura." + ID_A, null,
                msg.getBytes(StandardCharsets.UTF_8));
        esperar(2000);
        assertThat(wsA.stream().anyMatch(j -> j.contains("\"valor\":7.77") && j.contains(ID_A.toString())))
                .as("lectura realtime recibida por WS del sensor A").isTrue();
        sub.dispose();
    }
}

