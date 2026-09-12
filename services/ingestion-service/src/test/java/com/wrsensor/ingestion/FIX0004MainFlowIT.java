package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FIX-0004 (Main Flow + AC-001..AC-007) con Postgres y RabbitMQ
 * reales: rango físico global por unidad, override por código, unidad CENTIMETROS,
 * marca de calidad del emisor y evidencia WARN.
 */
@SpringBootTest(properties = {
        "ingestion.ventana-segundos=3600",
        "ingestion.outbox.intervalo-ms=3600000",
        "ingestion.rango-fisico.overrides.00000000-0000-4000-8000-00000000000b.min=0.0",
        "ingestion.rango-fisico.overrides.00000000-0000-4000-8000-00000000000b.max=8.0"
})
class FIX0004MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    private static final int REGISTRY_PORT = 18125;

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
    }

    private static String sensorJson(UUID id, String codigo, String unidad) {
        return "{\"id\":\"" + id + "\",\"codigo\":\"" + codigo + "\",\"estado\":\"ACTIVO\","
                + "\"unidadMedida\":\"" + unidad + "\",\"rangoNormal\":{\"min\":4,\"max\":6},"
                + "\"rangoWarning\":{\"min\":2,\"max\":8},\"rangoCritical\":{\"min\":0,\"max\":10}}";
    }

    @BeforeAll
    static void startAll() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> respond(ex, 200,
                "{\"token\":\"t\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}"));
        registry.createContext("/api/sensores/", ex -> {
            String path = ex.getRequestURI().getPath();
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            if (id.equals(A)) respond(ex, 200, sensorJson(A, "PARANA-RECONQUISTA", "METROS"));
            else if (id.equals(B)) respond(ex, 200, sensorJson(B, "SALADO-SANJUSTO", "METROS"));
            else if (id.equals(C)) respond(ex, 200, sensorJson(C, "SALADO-RECREO", "CENTIMETROS"));
            else respond(ex, 404, "");
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

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Autowired
    DatabaseClient db;

    @Autowired
    com.wrsensor.ingestion.application.service.IngestorLecturas ingestor;

    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        Connection conn = cf.newConnection();
        channel = conn.createChannel();
        channel.exchangeDeclare("sensor.lecturas", "topic", true);

        for (UUID id : new UUID[]{A, B, C}) ingestor.setUltimaSeveridad(id, null);
        db.sql("DELETE FROM outbox_alerta").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura_procesada").fetch().rowsUpdated())
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();
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

    private void publicar(UUID sensor, String valor, String extra) throws Exception {
        String msg = "{\"sensorId\":\"" + sensor + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valor\":" + valor + ",\"unidadMedida\":\"METROS\"" + (extra == null ? "" : extra) + "}";
        channel.basicPublish("sensor.lecturas", "lectura." + sensor, null, msg.getBytes(StandardCharsets.UTF_8));
    }

    private String calidadUltima(UUID sensor) {
        return db.sql("SELECT calidad FROM lectura WHERE sensor_id = $1 ORDER BY ts DESC LIMIT 1")
                .bind(0, sensor).map((r, m) -> str(r, "calidad")).first().block();
    }

    private String severidadUltima(UUID sensor) {
        return db.sql("SELECT severidad FROM lectura WHERE sensor_id = $1 ORDER BY ts DESC LIMIT 1")
                .bind(0, sensor).map((r, m) -> str(r, "severidad")).first().block();
    }

    /** Null-safe: R2DBC NO permite que el mapper devuelva null → marcador <NULL>. */
    private static String str(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return v == null ? "<NULL>" : v.toString();
    }

    private long contar(String tabla) {
        return db.sql("SELECT count(*) AS n FROM " + tabla)
                .map((row, meta) -> row.get("n", Long.class)).first().block();
    }

    // ===== AC-001 / AC-002 =====

    @Test
    @DisplayName("AC-001 / AC-002: dentro de rango → OK con severidad; fuera de rango → ERROR_SENSOR sin severidad ni alerta")
    void ac001_002_rangoFisico() throws Exception {
        publicar(A, "5.0", null);   // dentro (-1..15)
        esperar(2500);
        assertThat(calidadUltima(A)).isEqualTo("OK");
        assertThat(severidadUltima(A)).isEqualTo("NORMAL");

        publicar(A, "-50.0", null); // fuera de rango físico
        esperar(2500);
        assertThat(calidadUltima(A)).isEqualTo("ERROR_SENSOR");
        assertThat(severidadUltima(A)).as("no evaluada").isEqualTo("<NULL>");
        assertThat(contar("outbox_alerta")).as("nunca encola alerta").isZero();
        assertThat(contar("lectura")).isEqualTo(2);
    }

    // ===== AC-003 =====

    @Test
    @DisplayName("AC-003: la lectura ERROR_SENSOR no altera el estado → la siguiente válida evalúa contra NORMAL")
    void ac003_estadoNoAlterado() throws Exception {
        publicar(A, "5.0", null);   // NORMAL
        esperar(2500);
        publicar(A, "-50.0", null); // ERROR_SENSOR (no altera estado)
        esperar(2500);
        publicar(A, "7.0", null);   // WARNING (cambio respecto de NORMAL)
        esperar(2500);

        assertThat(contar("outbox_alerta")).isEqualTo(1);
        assertThat(contar("lectura")).isEqualTo(3);
        String payload = db.sql("SELECT payload FROM outbox_alerta")
                .map((r, m) -> r.get("payload", String.class)).first().block();
        assertThat(payload).contains("\"severidadAnterior\":\"NORMAL\"")
                .contains("\"severidadNueva\":\"WARNING\"");
    }

    // ===== AC-004 =====

    @Test
    @DisplayName("AC-004: override por código más restrictivo (0..8) vs global (-1..15)")
    void ac004_override() throws Exception {
        publicar(B, "9.5", null); // fuera del override de SALADO-SANJUSTO
        esperar(2500);
        assertThat(calidadUltima(B)).isEqualTo("ERROR_SENSOR");

        publicar(A, "9.5", null); // mismo valor, sensor sin override → dentro del global
        esperar(2500);
        assertThat(calidadUltima(A)).isEqualTo("OK");
    }

    // ===== AC-005 =====

    @Test
    @DisplayName("AC-005: sensor en CENTIMETROS usa el rango de centímetros (340 cm → OK)")
    void ac005_centimetros() throws Exception {
        publicar(C, "340", null);
        esperar(2500);
        assertThat(calidadUltima(C)).isEqualTo("OK");
    }

    // ===== AC-006 =====

    @Test
    @DisplayName("AC-006: marca de calidad del emisor (calidad.estado=ERROR_SENSOR) excluye aunque el valor sea plausible")
    void ac006_calidadEmisor() throws Exception {
        publicar(A, "5.0", ",\"calidad\":{\"estado\":\"ERROR_SENSOR\",\"confianza\":0.1,\"codigosAnomalias\":[]}");
        esperar(2500);
        assertThat(calidadUltima(A)).isEqualTo("ERROR_SENSOR");
        assertThat(severidadUltima(A)).isEqualTo("<NULL>");
        assertThat(contar("outbox_alerta")).isZero();
    }

    // ===== AC-007 =====

    @Test
    @DisplayName("AC-007: se registra WARN con sensorId y motivo de la lectura descartada")
    void ac007_logWarn() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(
                com.wrsensor.ingestion.application.service.IngestorLecturas.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            publicar(A, "-50.0", null);
            esperar(2500);
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains(A.toString())
                            && e.getFormattedMessage().contains("ERROR_SENSOR"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}


