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
 * Integration test — FIX-0002 Main Flow (Reproduction Steps end-to-end):
 * el payload real de `sensor.alertas` (valorLectura 7.77, cruceHisteresis true) se
 * consume sin errores y dispara la notificación de subida; el payload con
 * valorLectura inválido termina en la DLQ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "alerting.histeresis-segundos=2")
class FIX0002MainFlowIT {

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
    private final CopyOnWriteArrayList<String> ws = new CopyOnWriteArrayList<>();
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
                        session.receive().map(m -> m.getPayloadAsText()).doOnNext(ws::add).then())
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                .subscribe();
        esperar(800);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wsSub != null) wsSub.dispose();
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
    @DisplayName("FIX-0002 Main Flow: payload real (valorLectura 7.77, cruce true) se procesa y notifica; inválido → DLQ")
    void mainFlow_reproduccion() throws Exception {
        // Payload de los Reproduction Steps del Contract.
        String valido = "{\"sensorId\":\"" + ID + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valorLectura\":7.77,\"severidadAnterior\":\"NORMAL\","
                + "\"severidadNueva\":\"WARNING\",\"cruceHisteresis\":true}";
        channel.basicPublish("sensor.alertas", "alerta.warning", null, valido.getBytes(StandardCharsets.UTF_8));
        esperar(2000);

        assertThat(ws.stream().anyMatch(m -> m.contains("\"severidadNueva\":\"WARNING\"")))
                .as("evento con valorLectura real procesado → notificación de subida").isTrue();
        assertThat(dlq).as("payload válido NO va a DLQ").isEmpty();

        // Payload con valorLectura no numérico → rechazo → DLQ (AC-003).
        String invalido = valido.replace("7.77", "\"abc\"");
        channel.basicPublish("sensor.alertas", "alerta.warning", null, invalido.getBytes(StandardCharsets.UTF_8));
        esperar(1500);
        assertThat(dlq.stream().anyMatch(m -> m.contains("\"valorLectura\":\"abc\"")))
                .as("payload con valorLectura inválido en DLQ").isTrue();
    }
}
