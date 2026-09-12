package com.wrsensor.ingestion;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Main Flow FEAT-0011 (ingestion end-to-end):
 * mensajes a `sensor.lecturas` → persistencia en TimescaleDB/Postgres + eventos a
 * `sensor.alertas` + rechazos a la DLQ. Config del sensor simulada con un
 * HttpServer local (registro stub) y payload FEAT-0010.
 * Imagen: IT_TIMESCALE_IMAGE (default postgres:16-alpine; usar
 * timescale/timescaledb:latest-pg16 cuando este disponible).
 */
@SpringBootTest
class FEAT0011MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID ID_NORMAL = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_WARNING = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_DESCONOCIDO = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("IT_TIMESCALE_IMAGE", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"))
            .withNetwork(NETWORK).withDatabaseName("wrsensor_ingestion")
            .withUsername("wrsensor").withPassword("wrsensor").withReuse(true);

    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK)
            // FIX-0005: ingestion particiona con x-consistent-hash
            .withPluginsEnabled("rabbitmq_consistent_hash_exchange")
            .withReuse(true);

    private static HttpServer registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":" + DB.getFirstMappedPort() + "/wrsensor_ingestion");
        r.add("spring.r2dbc.username", DB::getUsername);
        r.add("spring.r2dbc.password", DB::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("ingestion.registry.base-url", () -> "http://localhost:" + REGISTRY_PORT);
        r.add("ingestion.ventana-segundos", () -> "3600");
    }

    static final int REGISTRY_PORT = 18123;

    private static String sensorJson(UUID id, String estado) {
        return "{\"id\":\"" + id + "\",\"codigo\":\"S-" + id + "\",\"estado\":\"" + estado
                + "\",\"unidadMedida\":\"METROS\","
                + "\"rangoNormal\":{\"min\":4,\"max\":6},"
                + "\"rangoWarning\":{\"min\":2,\"max\":8},"
                + "\"rangoCritical\":{\"min\":0,\"max\":10}}";
    }

    @BeforeAll
    static void startAll() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> {
            respond(ex, 200, "{\"token\":\"test-token\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}");
        });
        registry.createContext("/api/sensores/", ex -> {
            String path = ex.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            UUID uuid = UUID.fromString(id);
            if (uuid.equals(ID_NORMAL) || uuid.equals(ID_WARNING)) {
                respond(ex, 200, sensorJson(uuid, "ACTIVO"));
            } else {
                respond(ex, 404, "");
            }
        });
        registry.start();

        DB.start();
        RABBIT.start();
    }

    @AfterAll
    static void stopAll() {
        if (registry != null) registry.stop(0);
        DB.stop();
        RABBIT.stop();
    }

    private static void respond(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private Channel channel;
    private final CopyOnWriteArrayList<String> alertas = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Map<String, Object>> dlq = new CopyOnWriteArrayList<>();

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
        String qa = channel.queueDeclare().getQueue();
        channel.queueBind(qa, "sensor.alertas", "#");
        String qdlq = channel.queueDeclare().getQueue();
        channel.queueBind(qdlq, "sensor.lecturas.dlx", "");
        Thread collector = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    GetResponse a = channel.basicGet(qa, true);
                    if (a != null) alertas.add(new String(a.getBody(), StandardCharsets.UTF_8));
                    GetResponse d = channel.basicGet(qdlq, true);
                    if (d != null) dlq.add(Map.of("body", new String(d.getBody(), StandardCharsets.UTF_8)));
                    Thread.sleep(150);
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
        if (channel != null) channel.close();
    }

    private void publicar(String sensorId, String valor) throws Exception {
        String msg = "{\"sensorId\":\"" + sensorId + "\",\"timestamp\":\""
                + Instant.now() + "\",\"valor\":" + valor + ",\"unidadMedida\":\"METROS\"}";
        channel.basicPublish("sensor.lecturas", "lectura." + sensorId, null,
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
    @DisplayName("integration-test:FEAT-0011-main — consume, persiste, publica eventos y rechaza a DLQ")
    void mainFlow_pipelineCompleto() throws Exception {
        // NORMAL (sin evento previo) + WARNING (cambio → evento) + sensor desconocido (→ DLQ).
        publicar(ID_NORMAL.toString(), "5.0");
        publicar(ID_NORMAL.toString(), "7.0");  // cambio NORMAL→WARNING → evento
        publicar(ID_WARNING.toString(), "5.2"); // NORMAL sin cambio
        publicar(ID_DESCONOCIDO.toString(), "5.0");
        esperar(7000);

        assertThat(alertas).as("evento por cambio NORMAL→WARNING").hasSize(1);
        assertThat(alertas.get(0))
                .contains("\"severidadNueva\":\"WARNING\"")
                .contains(ID_NORMAL.toString());

        assertThat(dlq).as("lectura de sensor desconocido en DLQ").hasSize(1);
        assertThat(dlq.get(0).get("body").toString()).contains(ID_DESCONOCIDO.toString());

        // Filas persistidas: 3 validas (2 de normal + 1 de warning) → severidades en DB.
        PostgresqlConnectionConfiguration r2dbcCf = PostgresqlConnectionConfiguration.builder()
                .host(DB.getHost()).port(DB.getFirstMappedPort())
                .database("wrsensor_ingestion").username("wrsensor").password("wrsensor")
                .build();
        io.r2dbc.spi.ConnectionFactory cf = new PostgresqlConnectionFactory(r2dbcCf);
        DatabaseClient db = DatabaseClient.create(cf);
        Integer filas = db.sql("SELECT count(*) AS n FROM lectura")
                .map((row, meta) -> row.get("n", Long.class))
                .first().map(Long::intValue).block();
        assertThat(filas).as("3 lecturas validas persistidas (AC-007)").isEqualTo(3);
        String severidadNormal = db.sql("SELECT severidad FROM lectura WHERE sensor_id = $1 AND valor = 7.0 LIMIT 1")
                .bind(0, ID_NORMAL).map((row, meta) -> row.get("severidad", String.class)).first().block();
        assertThat(severidadNormal).isEqualTo("WARNING");
    }
}


