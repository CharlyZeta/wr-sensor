package com.wrsensor.sensorregistry;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0001 + AC-001 (happy path). Test ID:
 * integration-test:FEAT-0001-main.
 *
 * <p>Levanta un Postgres real (Testcontainers R2DBC) y un RabbitMQ real
 * (Testcontainers), arranca el Spring Boot app completo (WebFlux + R2DBC), y
 * dispara el POST /api/sensores con el cuerpo valido de AC-001. Verifica:
 * <ul>
 *   <li>201 Created</li>
 *   <li>body con id UUID no nulo, codigo = PARANA-RECONQUISTA, nombre = Reconquista</li>
 *   <li>los tres rangos persistidos (rangoNormal, rangoWarning, rangoCritical)</li>
 *   <li>se publica un evento de creacion de sensor a RabbitMQ (al menos un
 *       mensaje llega al exchange sensor.created)</li>
 * </ul>
 *
 * <p>Stack (stack.md): JUnit 5 + Reactor Test + Testcontainers (R2DBC +
 * RabbitMQ) + WebTestClient. Cero bloqueante en el SUT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0001MainFlowIT {

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

    @LocalServerPort
    int port;

    private WebTestClient webClient;
    private String adminToken;
    private String viewerToken; // lazy (FEAT-0006)

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

    @org.junit.jupiter.api.BeforeEach
    void setupWebClient() {
        webClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        // FEAT-0006: autenticacion real — JWT obtenido via /api/auth/login (seed dev).
        adminToken = TestTokens.login(webClient,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.EMAIL_ADMIN,
                com.wrsensor.sensorregistry.infrastructure.seed.DevUserSeeder.DEV_PASSWORD_ADMIN);
    }

    @Test
    void testMainFlow_ac001_creacionDeSensorExitosa() throws Exception {
        // Arrange — cuerpo valido de AC-001 + header Authorization rol ADMIN.
        Map<String, Object> body = Map.ofEntries(
                Map.entry("codigo", "PARANA-RECONQUISTA"),
                Map.entry("nombre", "Reconquista"),
                Map.entry("tipo", "RIO"),
                Map.entry("latitud", new BigDecimal("-29.15")),
                Map.entry("longitud", new BigDecimal("-59.65")),
                Map.entry("unidadMedida", "METROS"),
                Map.entry("rangoNormal", Map.of("min", new BigDecimal("4"), "max", new BigDecimal("6"))),
                Map.entry("rangoWarning", Map.of("min", new BigDecimal("2"), "max", new BigDecimal("8"))),
                Map.entry("rangoCritical", Map.of("min", new BigDecimal("0"), "max", new BigDecimal("10"))),
                Map.entry("histeresis", new BigDecimal("0.5")),
                Map.entry("frecuenciaReporteSegundos", 60),
                Map.entry("estado", "ACTIVO")
        );

        // Conectar a RabbitMQ para declarar una cola exclusiva atada al exchange
        // sensor.created y contabilizar el evento publicado por el adapter.
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());

        String queueName;
        try (Connection conn = cf.newConnection(); Channel ch = conn.createChannel()) {
            ch.exchangeDeclare("sensor.created", "topic", true);
            queueName = ch.queueDeclare().getQueue();
            ch.queueBind(queueName, "sensor.created", "sensor.created.#");

            // Act + Assert HTTP (AC-001: 201, body, rangos).
            webClient.post().uri("/api/sensores")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.id").isNotEmpty()
                    .jsonPath("$.codigo").isEqualTo("PARANA-RECONQUISTA")
                    .jsonPath("$.nombre").isEqualTo("Reconquista")
                    .jsonPath("$.rangoNormal.min").isEqualTo(4)
                    .jsonPath("$.rangoNormal.max").isEqualTo(6)
                    .jsonPath("$.rangoWarning.min").isEqualTo(2)
                    .jsonPath("$.rangoWarning.max").isEqualTo(8)
                    .jsonPath("$.rangoCritical.min").isEqualTo(0)
                    .jsonPath("$.rangoCritical.max").isEqualTo(10);

            // Assert RabbitMQ: AC-001 "se publica un evento de creacion de sensor".
            // Ambiguity Log: exchange/routing-key/payload no definidos; verificamos
            // solo que llega al menos un mensaje al exchange sensor.created.
            AMQP.Queue.DeclareOk declareOk = ch.queueDeclarePassive(queueName);
            long initial = declareOk.getMessageCount();
            com.rabbitmq.client.GetResponse msg = ch.basicGet(queueName, true);
            long waited = 0;
            while (msg == null && waited < 5000) {
                Thread.sleep(100);
                waited += 100;
                // re-declare to see updated message count (basicGet auto-acks)
                msg = ch.basicGet(queueName, true);
            }
            assertThat(msg).as("evento sensor.created publicado a RabbitMQ").isNotNull();
            String payload = new String(msg.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(payload).contains("PARANA-RECONQUISTA");
        }
    }
}

