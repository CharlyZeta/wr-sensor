package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FIX-0006 (Main Flow + AC-004..AC-009) con Postgres y RabbitMQ reales:
 * payload v1 versionado (schemaVersion/eventId/sequence/calidad), compatibilidad con el payload
 * plano legado, tolerancia hacia adelante, rechazo por versión inválida a la DLQ y persistencia
 * de la secuencia con detección de huecos.
 */
@SpringBootTest(properties = {
        "ingestion.ventana-segundos=3600",
        "ingestion.outbox.intervalo-ms=3600000",
        "ingestion.particiones.total=4"
})
class FIX0006MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    private static final int REGISTRY_PORT = 18155;
    private static final String DLQ = "queue.sensor.lecturas.dlq";

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
            .withPluginsEnabled("rabbitmq_consistent_hash_exchange")
            .withReuse(true);

    private static HttpServer registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":"
                + DB.getFirstMappedPort() + "/wrsensor_ingestion");
        r.add("spring.r2dbc.username", DB::getUsername);
        r.add("spring.r2dbc.password", DB::getPassword);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getFirstMappedPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("ingestion.registry.base-url", () -> "http://localhost:" + REGISTRY_PORT);
    }

    @BeforeAll
    static void startAll() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> responder(ex, 200,
                "{\"token\":\"t\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}"));
        registry.createContext("/api/sensores/", ex -> responder(ex, 200,
                "{\"id\":\"" + A + "\",\"codigo\":\"PARANA-RECONQUISTA\",\"estado\":\"ACTIVO\","
                        + "\"unidadMedida\":\"METROS\",\"rangoNormal\":{\"min\":4,\"max\":6},"
                        + "\"rangoWarning\":{\"min\":2,\"max\":8},\"rangoCritical\":{\"min\":0,\"max\":10}}"));
        registry.start();
        DB.start();
        RABBIT.start();
    }

    @AfterAll
    static void stopAll() {
        if (registry != null) {
            registry.stop(0);
        }
        DB.stop();
        RABBIT.stop();
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Autowired
    DatabaseClient db;

    @Autowired
    com.wrsensor.ingestion.application.service.IngestorLecturas ingestor;

    private Connection conexion;
    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        conexion = cf.newConnection();
        channel = conexion.createChannel();
        channel.exchangeDeclare("sensor.lecturas", "topic", true);
        ingestor.setUltimaSeveridad(A, null);
        ingestor.setUltimaSecuencia(A, null);
        db.sql("DELETE FROM outbox_alerta").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura_procesada").fetch().rowsUpdated())
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();
        purgar();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.close();
        }
        if (conexion != null) {
            conexion.close();
        }
    }

    // ================= helpers =================

    private void purgar() {
        try (Channel ch = conexion.createChannel()) {
            ch.queuePurge(DLQ);
        } catch (Exception ignored) {
            // la DLQ todavía no existe
        }
    }

    private static void esperarHasta(BooleanSupplier cond, long timeoutMs) {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite && !cond.getAsBoolean()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void publicar(String json) throws IOException {
        channel.basicPublish("sensor.lecturas", "lectura." + A, null, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Payload v1 (FIX-0006): los campos variables se agregan por parámetro. */
    private void publicarV1(String valor, String extra) throws IOException {
        publicar("{\"schemaVersion\":\"1.0\",\"eventId\":\"" + UUID.randomUUID() + "\","
                + "\"sensorId\":\"" + A + "\",\"timestamp\":\"" + Instant.now() + "\","
                + "\"valor\":" + valor + ",\"unidadMedida\":\"METROS\""
                + (extra == null ? "" : extra) + "}");
    }

    private long filas() {
        Long n = db.sql("SELECT count(*) AS n FROM lectura")
                .map((r, m) -> r.get("n", Long.class)).first().block();
        return n == null ? 0 : n;
    }

    private String campo(String columna) {
        return db.sql("SELECT " + columna + " AS v FROM lectura WHERE sensor_id = $1 ORDER BY ts DESC LIMIT 1")
                .bind(0, A).map((r, m) -> r.get("v") == null ? "<NULL>" : r.get("v").toString())
                .first().block();
    }

    private GetResponse basicGetDlq() {
        try {
            return channel.basicGet(DLQ, true);
        } catch (IOException e) {
            return null;
        }
    }

    private static ListAppender<ILoggingEvent> capturarLog(Class<?> clase) {
        Logger logger = (Logger) LoggerFactory.getLogger(clase);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // ================= Main Flow =================

    @Test
    @DisplayName("Main Flow: payload v1 → persistido con severidad, calidad y secuencia")
    void mainFlow() throws Exception {
        publicarV1("5.0", ",\"sequence\":7,\"calidad\":{\"estado\":\"OK\",\"confianza\":0.95,"
                + "\"codigosAnomalias\":[]}");

        esperarHasta(() -> filas() == 1, 20000);
        assertThat(filas()).isEqualTo(1);
        assertThat(campo("severidad")).isEqualTo("NORMAL");
        assertThat(campo("calidad")).isEqualTo("OK");
        assertThat(campo("secuencia")).isEqualTo("7");
    }

    // ================= AC-004 / AC-005 / AC-006 =================

    @Test
    @DisplayName("AC-004: payload válido con espacios y campos desconocidos → se procesa")
    void ac004_espaciosYDesconocidos() throws Exception {
        publicar("{\n  \"sensorId\" : \"" + A + "\" ,\n  \"timestamp\" : \"" + Instant.now()
                + "\",\n  \"valor\" : 5.0,\n  \"unidadMedida\" : \"METROS\",\n  \"futuro\" : { \"x\" : 1 }\n}");
        esperarHasta(() -> filas() == 1, 20000);
        assertThat(filas()).as("los patrones regex lo habrían rechazado").isEqualTo(1);
        assertThat(campo("severidad")).isEqualTo("NORMAL");
        assertThat(campo("secuencia")).as("sin sequence → NULL (legado)").isEqualTo("<NULL>");
    }

    @Test
    @DisplayName("AC-005: el payload plano legado se procesa y deja evidencia INFO")
    void ac005_legado() throws Exception {
        ListAppender<ILoggingEvent> logs = capturarLog(
                com.wrsensor.ingestion.application.service.RegistroEsquema.class);
        try {
            publicarV1Legado("5.0");
            esperarHasta(() -> filas() == 1, 20000);
            assertThat(filas()).isEqualTo(1);
            assertThat(campo("secuencia")).isEqualTo("<NULL>");
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("evento legado"));
        } finally {
            ((Logger) LoggerFactory.getLogger(
                    com.wrsensor.ingestion.application.service.RegistroEsquema.class))
                    .detachAppender(logs);
        }
    }

    private void publicarV1Legado(String valor) throws IOException {
        publicar("{\"sensorId\":\"" + A + "\",\"timestamp\":\"" + Instant.now()
                + "\",\"valor\":" + valor + ",\"unidadMedida\":\"METROS\"}");
    }

    @Test
    @DisplayName("AC-006: una versión mayor desconocida se persiste (no va a la DLQ) y avisa una vez")
    void ac006_mayorDesconocida() throws Exception {
        ListAppender<ILoggingEvent> logs = capturarLog(
                com.wrsensor.ingestion.application.service.RegistroEsquema.class);
        try {
            publicarV1("5.0", ",\"schemaVersion\":\"2.0\"");
            publicarV1("5.5", ",\"schemaVersion\":\"2.0\"");
            esperarHasta(() -> filas() == 2, 20000);

            assertThat(filas()).as("tolerancia hacia adelante: las lecturas se persisten").isEqualTo(2);
            assertThat(basicGetDlq()).as("nada fue a la DLQ").isNull();
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("mayor desconocida")).count())
                    .as("un aviso por versión, no por evento").isEqualTo(1);
        } finally {
            ((Logger) LoggerFactory.getLogger(
                    com.wrsensor.ingestion.application.service.RegistroEsquema.class))
                    .detachAppender(logs);
        }
    }

    // ================= AC-007: DLQ y cola viva =================

    @Test
    @DisplayName("AC-007: schemaVersion malformada → DLQ con PAYLOAD_INVALID y el consumer sigue procesando")
    void ac007_versionMalformada() throws Exception {
        publicarV1("5.0", ",\"schemaVersion\":\"uno\"");

        AtomicReference<GetResponse> recibido = new AtomicReference<>();
        esperarHasta(() -> {
            if (recibido.get() != null) {
                return true;
            }
            GetResponse r = basicGetDlq();
            if (r != null && r.getProps() != null && r.getProps().getHeaders() != null
                    && r.getProps().getHeaders().containsKey("x-rechazo")) {
                recibido.set(r);
            }
            return false;
        }, 20000);

        GetResponse rechazado = recibido.get();
        assertThat(rechazado).as("el mensaje con versión inválida llegó a la DLQ").isNotNull();
        assertThat(new String((byte[]) rechazado.getProps().getHeaders().get("x-rechazo"),
                StandardCharsets.UTF_8)).isEqualTo("PAYLOAD_INVALID");
        assertThat(filas()).as("una versión inválida no se persiste").isZero();

        // ... y la cola sigue viva: el mensaje siguiente se procesa normalmente
        publicarV1("5.0", ",\"sequence\":1");
        esperarHasta(() -> filas() == 1, 20000);
        assertThat(filas()).isEqualTo(1);
    }

    // ================= AC-008 / AC-009 / BR-005 =================

    @Test
    @DisplayName("AC-008/AC-009: secuencias 1 y 3 → ambas persistidas con su valor y WARN de hueco")
    void ac008_huecoYColumna() throws Exception {
        ListAppender<ILoggingEvent> logs = capturarLog(
                com.wrsensor.ingestion.application.service.IngestorLecturas.class);
        try {
            publicarV1("5.0", ",\"sequence\":1");
            esperarHasta(() -> filas() == 1, 20000);
            publicarV1("5.1", ",\"sequence\":3");
            esperarHasta(() -> filas() == 2, 20000);

            assertThat(filas()).isEqualTo(2);
            List<String> secuencias = db.sql("SELECT secuencia FROM lectura WHERE sensor_id = $1 ORDER BY ts")
                    .bind(0, A).map((r, m) -> String.valueOf(r.get("secuencia")))
                    .all().collectList().block();
            assertThat(secuencias).containsExactly("1", "3");
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("hueco de secuencia"));
        } finally {
            ((Logger) LoggerFactory.getLogger(
                    com.wrsensor.ingestion.application.service.IngestorLecturas.class))
                    .detachAppender(logs);
        }
    }

    @Test
    @DisplayName("BR-005: calidad.estado=ERROR_SENSOR del emisor ⇒ se persiste sin severidad ni alerta")
    void br005_calidadErrorSensor() throws Exception {
        publicarV1("5.0", ",\"sequence\":2,\"calidad\":{\"estado\":\"ERROR_SENSOR\","
                + "\"confianza\":0.1,\"codigosAnomalias\":[\"ANOMALIA_INYECTADA\"]}");

        esperarHasta(() -> filas() == 1, 20000);
        assertThat(campo("calidad")).isEqualTo("ERROR_SENSOR");
        assertThat(campo("severidad")).isEqualTo("<NULL>");
        assertThat(campo("secuencia")).as("la secuencia se persiste igual").isEqualTo("2");
        Long outbox = db.sql("SELECT count(*) AS n FROM outbox_alerta")
                .map((r, m) -> r.get("n", Long.class)).first().block();
        assertThat(outbox).as("sin alerta").isZero();
    }
}
