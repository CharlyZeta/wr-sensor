package com.wrsensor.datasimulator;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0010 (data-simulator → RabbitMQ real).
 * Test ID: {@code integration-test:FEAT-0010-main} (+ assertions AC-001..AC-008).
 * Frecuencia de demo forzada a 1 s (IT rapido); exchange `sensor.lecturas` real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "simulador.frecuencia-reporte-segundos=1",
                "simulador.anomalia.segundos=5"
        })
class FEAT0010MainFlowIT {

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @BeforeAll
    static void startRabbit() {
        RABBIT.start();
    }

    @AfterAll
    static void stopRabbit() {
        RABBIT.stop();
    }

    @LocalServerPort
    int port;

    private WebTestClient webClient;
    private Channel channel;
    private final List<String> mensajes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        Connection conn = cf.newConnection();
        channel = conn.createChannel();
        channel.exchangeDeclare("sensor.lecturas", "topic", true);
        String q = channel.queueDeclare().getQueue();
        channel.queueBind(q, "sensor.lecturas", "#");
        mensajes.clear();
        Thread collector = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    GetResponse r = channel.basicGet(q, true);
                    if (r == null) {
                        Thread.sleep(150);
                        continue;
                    }
                    mensajes.add(new String(r.getBody(), StandardCharsets.UTF_8));
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        collector.setDaemon(true);
        collector.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        webClient.post().uri("/api/simulador/detener").exchange();
        if (channel != null) channel.close();
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean esperarLectura(long timeoutMs, java.util.function.Predicate<String> ok) {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite) {
            if (mensajes.stream().anyMatch(ok)) return true;
            esperar(200);
        }
        return false;
    }

    private static final Pattern P_SENSOR = Pattern.compile("\"sensorId\":\"([0-9a-f-]{36})\"");
    private static final Pattern P_VALOR = Pattern.compile("\"valor\":(-?\\d+\\.?\\d*)");

    private static boolean payloadValido(String m) {
        return P_SENSOR.matcher(m).find() && P_VALOR.matcher(m).find()
                && m.contains("\"timestamp\":") && m.contains("\"unidadMedida\":\"METROS\"")
                && !m.contains("severidad");
    }

    @Test
    @DisplayName("integration-test:FEAT-0010-main — iniciar/publica/detener/anomalia (AC-001..AC-008)")
    void mainFlow_simuladorCompleto() {
        // AC-008 (parte): detenido al arrancar.
        webClient.get().uri("/api/simulador/estado").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("STOPPED");

        // AC-001: iniciar → RUNNING con los 6 sensores seed.
        webClient.post().uri("/api/simulador/iniciar").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("RUNNING")
                .jsonPath("$.sensores.length()").isEqualTo(6);

        // AC-001 (mensajeria): llegan lecturas con el payload de BR-002.
        assertThat(esperarLectura(6000, FEAT0010MainFlowIT::payloadValido))
                .as("lectura publicada en sensor.lecturas con payload BR-002").isTrue();
        long contadorCorriendo = mensajes.size();

        // AF-01/AC-002: iniciar dos veces → 409.
        webClient.post().uri("/api/simulador/iniciar").exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo("SIMULATOR_ALREADY_RUNNING");

        // AC-008: estado RUNNING + contador.
        webClient.get().uri("/api/simulador/estado").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.estado").isEqualTo("RUNNING")
                .jsonPath("$.lecturasPublicadas").isNumber();

        // AC-005: anomalia en PARANA-RECONQUISTA → lectura con valor > rangoNormalMax (7.0).
        webClient.post().uri("/api/simulador/{s}/anomalia", "PARANA-RECONQUISTA").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("RUNNING");
        java.util.UUID idS1 = com.wrsensor.datasimulator.application.service.SimuladorService.idDe("PARANA-RECONQUISTA");
        assertThat(esperarLectura(8000, m -> {
            Matcher ms = P_SENSOR.matcher(m);
            Matcher mv = P_VALOR.matcher(m);
            return ms.find() && mv.find()
                    && ms.group(1).equals(idS1.toString())
                    && new java.math.BigDecimal(mv.group(1)).compareTo(new java.math.BigDecimal("7.0")) > 0;
        })).as("anomalia produce lectura sobre rangoNormalMax de PARANA-RECONQUISTA").isTrue();

        // AF-03/AC-006: anomalia en sensor no configurado → 404 SENSOR_NOT_FOUND.
        webClient.post().uri("/api/simulador/{s}/anomalia", "NO-EXISTE").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SENSOR_NOT_FOUND");

        // AF-02/AC-003: detener → STOPPED y deja de publicar.
        webClient.post().uri("/api/simulador/detener").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("STOPPED");
        long despuesDeDetener = mensajes.size();
        esperar(2500);
        assertThat(mensajes.size()).as("sin publicaciones tras detener").isEqualTo(despuesDeDetener);

        // AC-004: detener otra vez idempotente.
        webClient.post().uri("/api/simulador/detener").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.estado").isEqualTo("STOPPED");

        // AF-05/AC-007: anomalia sin simulacion → 409 SIMULATOR_NOT_RUNNING.
        webClient.post().uri("/api/simulador/{s}/anomalia", "PARANA-RECONQUISTA").exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo("SIMULATOR_NOT_RUNNING");
    }
}
