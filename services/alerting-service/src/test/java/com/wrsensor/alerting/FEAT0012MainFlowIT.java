package com.wrsensor.alerting;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
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
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0012 (histéresis + WebSocket).
 * RabbitMQ real; ventana de histéresis forzada a 2 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "alerting.histeresis-segundos=2")
class FEAT0012MainFlowIT {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine")).withReuse(true);

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

    private Channel channel;
    private final CopyOnWriteArrayList<String> recibidosWs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> dlq = new CopyOnWriteArrayList<>();
    private Disposable wsSub;

    @BeforeEach
    void setUp() throws Exception {
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        Connection conn = cf.newConnection();
        channel = conn.createChannel();
        channel.exchangeDeclare("sensor.alertas", "topic", true);
        channel.exchangeDeclare("sensor.alertas.dlx", "fanout", true);
        String q = channel.queueDeclare().getQueue();
        channel.queueBind(q, "sensor.alertas.dlx", "");
        Thread t = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    GetResponse r = channel.basicGet(q, true);
                    if (r != null) dlq.add(new String(r.getBody(), StandardCharsets.UTF_8));
                    Thread.sleep(150);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.setDaemon(true);
        t.start();

        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        wsSub = client.execute(URI.create("ws://localhost:" + port + "/ws/alertas"), session ->
                        session.receive().map(m -> m.getPayloadAsText()).doOnNext(recibidosWs::add).then())
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                .subscribe();
        esperar(800); // deja abrir el WS
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wsSub != null) wsSub.dispose();
        if (channel != null) channel.close();
    }

    private void publicar(String anterior, String nueva) throws Exception {
        String msg = "{\"sensorId\":\"" + ID + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valorLectura\":7.0,\"severidadAnterior\":\"" + anterior
                + "\",\"severidadNueva\":\"" + nueva + "\",\"cruceHisteresis\":false}";
        channel.basicPublish("sensor.alertas", "alerta." + nueva.toLowerCase(), null,
                msg.getBytes(StandardCharsets.UTF_8));
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("integration-test:FEAT-0012-main — subida inmediata por WS; bajada confirmada tras ventana")
    void mainFlow_histéresisConWs() throws Exception {
        // Subida NORMAL→WARNING: inmediata.
        publicar("NORMAL", "WARNING");
        esperar(1500);
        assertThat(recibidosWs.stream().anyMatch(m -> m.contains("\"severidadNueva\":\"WARNING\"")
                && m.contains("\"confirmada\":true")))
                .as("subida notificada inmediato por WS").isTrue();

        // Bajada WARNING→NORMAL: no inmediata.
        publicar("WARNING", "NORMAL");
        esperar(900); // < ventana 2s
        assertThat(recibidosWs.stream().anyMatch(m -> m.contains("\"severidadNueva\":\"NORMAL\"")))
                .as("bajada NO notificada antes de la ventana").isFalse();

        // Tras la ventana se confirma.
        esperar(2200);
        assertThat(recibidosWs.stream().anyMatch(m -> m.contains("\"severidadNueva\":\"NORMAL\"")
                && m.contains("\"confirmada\":true")))
                .as("bajada confirmada por permanencia y notificada por WS").isTrue();

        // Payload malformado → DLQ.
        channel.basicPublish("sensor.alertas", "alerta.warning", null,
                "no-es-json".getBytes(StandardCharsets.UTF_8));
        esperar(1500);
        assertThat(dlq.stream().anyMatch(m -> m.equals("no-es-json"))).as("malformado en DLQ").isTrue();
    }
}
