package com.wrsensor.ingestion;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.Severidad;
import com.wrsensor.ingestion.infrastructure.adapter.out.messaging.OutboxAlertaPoller;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test — FIX-0003 (Main Flow + AC-001..AC-008) sobre Postgres y
 * RabbitMQ reales. El poller de la app queda "quieto" (intervalo 60 s) y los tests
 * lo disparan explícitamente con {@code procesarLoteAhora()/purgarAhora()}.
 */
@SpringBootTest(properties = {
        "ingestion.outbox.intervalo-ms=3600000", // poller de la app quieto (solo tick inicial, sin filas)
        "ingestion.outbox.max-intentos=2",
        "ingestion.outbox.tamano-lote=10",
        
        "ingestion.ventana-segundos=3600"
})
class FIX0003MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final int REGISTRY_PORT = 18124;

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

    @BeforeAll
    static void startAll() throws Exception {
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> respond(ex, 200,
                "{\"token\":\"t\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}"));
        registry.createContext("/api/sensores/", ex -> respond(ex, 200,
                "{\"id\":\"" + SENSOR + "\",\"codigo\":\"PARANA-RECONQUISTA\",\"estado\":\"ACTIVO\","
                        + "\"unidadMedida\":\"METROS\",\"rangoNormal\":{\"min\":4,\"max\":6},"
                        + "\"rangoWarning\":{\"min\":2,\"max\":8},\"rangoCritical\":{\"min\":0,\"max\":10}}"));
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
    IngestaTransaccionalPort store;

    @Autowired
    OutboxAlertaPoller poller;

    @Autowired
    IngestionProperties props;

    @Autowired
    Sender sender;

    @Autowired
    com.wrsensor.ingestion.application.service.IngestorLecturas ingestor;

    private Channel channel;
    private String alertasQueue;

    @BeforeEach
    void setUp() throws Exception {
        ingestor.setUltimaSeveridad(SENSOR, null); // estado en memoria limpio por test
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        Connection conn = cf.newConnection();
        channel = conn.createChannel();
        channel.exchangeDeclare("sensor.alertas", "topic", true);
        channel.exchangeDeclare("sensor.lecturas", "topic", true);
        alertasQueue = channel.queueDeclare().getQueue();
        channel.queueBind(alertasQueue, "sensor.alertas", "#");

        db.sql("DELETE FROM outbox_alerta").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura_procesada").fetch().rowsUpdated())
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();
        drenarCola();
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

    private void drenarCola() throws Exception {
        GetResponse r;
        while ((r = channel.basicGet(alertasQueue, true)) != null) {
            // descarta mensajes previos
        }
    }

    private int contarMensajes() throws Exception {
        int n = 0;
        GetResponse r;
        while ((r = channel.basicGet(alertasQueue, true)) != null) {
            n++;
        }
        return n;
    }

    private void publicarLectura(String valor, Instant ts) throws Exception {
        String msg = "{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"" + ts
                + "\",\"valor\":" + valor + ",\"unidadMedida\":\"METROS\"}";
        channel.basicPublish("sensor.lecturas", "lectura." + SENSOR, null,
                msg.getBytes(StandardCharsets.UTF_8));
    }

    private long contar(String tabla) {
        return db.sql("SELECT count(*) AS n FROM " + tabla)
                .map((row, meta) -> row.get("n", Long.class)).first().block();
    }

    private void insertarOutbox(String routingKey, String payload, String estado, Instant enviadoEn) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO outbox_alerta (sensor_id, routing_key, payload, estado, creado_en, enviado_en)
                        VALUES ($1, $2, $3, $4, now(), $5)
                        """)
                .bind(0, SENSOR).bind(1, routingKey).bind(2, payload).bind(3, estado);
        if (enviadoEn == null) spec = spec.bindNull(4, LocalDateTime.class);
        else spec = spec.bind(4, LocalDateTime.ofInstant(enviadoEn, ZoneOffset.UTC));
        spec.fetch().rowsUpdated().block();
    }

    // ===== AC-001 =====

    @Test
    @DisplayName("AC-001 / BR-001 / BR-002: el mismo mensaje dos veces → 1 lectura, 1 registro, sin outbox")
    void ac001_redelivery() throws Exception {
        Instant ts = Instant.now().minusSeconds(5);
        publicarLectura("5.0", ts);
        esperar(2500);
        publicarLectura("5.0", ts); // redelivery idéntico (misma clave natural)
        esperar(2500);

        assertThat(contar("lectura")).as("lectura no duplicada").isEqualTo(1);
        assertThat(contar("lectura_procesada")).isEqualTo(1);
        assertThat(contar("outbox_alerta")).as("primera lectura NORMAL no encola").isEqualTo(0);
    }

    // ===== AC-003 / AC-004 =====

    @Test
    @DisplayName("AC-003 / AC-004 / BR-004: cambio encola outbox sin publicar; el poller publica y marca ENVIADO (sin reenvío)")
    void ac003_004_outboxYPoller() throws Exception {
        publicarLectura("5.0", Instant.now().minusSeconds(10)); // NORMAL (sin cambio)
        esperar(2000);
        publicarLectura("7.0", Instant.now().minusSeconds(5));  // NORMAL→WARNING (cambio)
        esperar(2500);

        // AC-003: fila PENDIENTE y nada publicado aún (poller quieto)
        assertThat(contar("outbox_alerta")).isEqualTo(1);
        assertThat(contarMensajes()).as("nada publicado todavía").isZero();
        String estado = db.sql("SELECT estado FROM outbox_alerta").map((r, m) -> r.get("estado", String.class)).first().block();
        assertThat(estado).isEqualTo("PENDIENTE");

        // AC-004: el poller publica y marca ENVIADO
        poller.procesarLoteAhora().block();
        esperar(1500);
        GetResponse msg = channel.basicGet(alertasQueue, true);
        assertThat(msg).as("evento publicado por el poller").isNotNull();
        assertThat(msg.getEnvelope().getRoutingKey()).isEqualTo("alerta.warning");
        assertThat(new String(msg.getBody(), StandardCharsets.UTF_8)).contains("\"valorLectura\":7.0");
        String estado2 = db.sql("SELECT estado FROM outbox_alerta").map((r, m) -> r.get("estado", String.class)).first().block();
        assertThat(estado2).isEqualTo("ENVIADO");

        // segunda corrida: no reenvía
        poller.procesarLoteAhora().block();
        esperar(800);
        assertThat(contarMensajes()).as("no se reenvía una fila ENVIADO").isZero();
    }

    // ===== AC-005 =====

    @Test
    @DisplayName("AC-005 / BR-005: fila pendiente de un 'crash' previo se emite al menos una vez al retomar")
    void ac005_recuperacion() throws Exception {
        insertarOutbox("alerta.critical", "{\"marcador\":\"recuperado\"}", "PENDIENTE", null);
        poller.procesarLoteAhora().block();
        esperar(1200);

        GetResponse msg = channel.basicGet(alertasQueue, true);
        assertThat(msg).as("evento recuperado y emitido").isNotNull();
        assertThat(new String(msg.getBody(), StandardCharsets.UTF_8)).contains("recuperado");
    }

    // ===== AC-006 =====

    @Test
    @DisplayName("AC-006 / BR-007: Rabbit inaccesible → intentos/ultimo_error, nunca se pierde; tras agotar → FALLIDO")
    void ac006_falloRabbit() {
        insertarOutbox("alerta.warning", "{\"marcador\":\"sin-broker\"}", "PENDIENTE", null);

        com.rabbitmq.client.ConnectionFactory malo = new com.rabbitmq.client.ConnectionFactory();
        malo.setHost("127.0.0.1");
        malo.setPort(1); // puerto cerrado → fallo de publicación
        Sender senderMalo = RabbitFlux.createSender(new SenderOptions().connectionFactory(malo));
        OutboxAlertaPoller pollerMalo = new OutboxAlertaPoller(senderMalo, db, props);

        pollerMalo.procesarLoteAhora().block();
        esperar(1500);
        Integer intentos1 = db.sql("SELECT intentos FROM outbox_alerta").map((r, m) -> r.get("intentos", Integer.class)).first().block();
        String estado1 = db.sql("SELECT estado FROM outbox_alerta").map((r, m) -> r.get("estado", String.class)).first().block();
        assertThat(intentos1).isGreaterThanOrEqualTo(1);
        assertThat(estado1).isIn("PENDIENTE", "FALLIDO");
        assertThat(contar("outbox_alerta")).as("la fila no se pierde").isEqualTo(1);

        pollerMalo.procesarLoteAhora().block(); // agota max-intentos=2 → FALLIDO
        esperar(1500);
        String estado2 = db.sql("SELECT estado FROM outbox_alerta").map((r, m) -> r.get("estado", String.class)).first().block();
        String error = db.sql("SELECT ultimo_error FROM outbox_alerta").map((r, m) -> r.get("ultimo_error", String.class)).first().block();
        assertThat(estado2).isEqualTo("FALLIDO");
        assertThat(error).isNotBlank();
    }

    // ===== AC-007 =====

    @Test
    @DisplayName("AC-007 / BR-006: el poller publica en orden de inserción (id creciente) por sensor")
    void ac007_orden() throws Exception {
        insertarOutbox("alerta.warning", "{\"marcador\":\"PRIMERO\"}", "PENDIENTE", null);
        insertarOutbox("alerta.critical", "{\"marcador\":\"SEGUNDO\"}", "PENDIENTE", null);

        poller.procesarLoteAhora().block();
        esperar(1500);

        GetResponse primero = channel.basicGet(alertasQueue, true);
        GetResponse segundo = channel.basicGet(alertasQueue, true);
        assertThat(primero).isNotNull();
        assertThat(segundo).isNotNull();
        assertThat(new String(primero.getBody(), StandardCharsets.UTF_8)).contains("PRIMERO");
        assertThat(new String(segundo.getBody(), StandardCharsets.UTF_8)).contains("SEGUNDO");
    }

    // ===== AC-008 =====

    @Test
    @DisplayName("AC-008 / BR-008 / BR-009: la purga elimina ENVIADO y dedupe viejos sin tocar la hypertable")
    void ac008_purga() throws Exception {
        // lectura histórica que NO debe purgarse
        db.sql("INSERT INTO lectura (sensor_id, ts, valor, unidad_medida, severidad) VALUES ($1,$2,$3,$4,$5)")
                .bind(0, SENSOR).bind(1, LocalDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC))
                .bind(2, new BigDecimal("5.00")).bind(3, "METROS").bind(4, "NORMAL")
                .fetch().rowsUpdated().block();
        insertarOutbox("alerta.warning", "{\"viejo\":true}", "ENVIADO", Instant.now().minusSeconds(200L * 24 * 3600));
        db.sql("INSERT INTO lectura_procesada (clave, sensor_id, ts, procesado_en) VALUES ($1,$2,$3,$4)")
                .bind(0, "nat:vieja").bind(1, SENSOR)
                .bind(2, LocalDateTime.ofInstant(Instant.now().minusSeconds(200L * 24 * 3600), ZoneOffset.UTC))
                .bind(3, LocalDateTime.ofInstant(Instant.now().minusSeconds(200L * 24 * 3600), ZoneOffset.UTC))
                .fetch().rowsUpdated().block();

        poller.purgarAhora().block();

        assertThat(contar("outbox_alerta")).as("outbox ENVIADO purgado").isZero();
        assertThat(contar("lectura_procesada")).as("dedupe viejo purgado").isZero();
        assertThat(contar("lectura")).as("hypertable intacta").isEqualTo(1);

        // el servicio sigue procesando con normalidad tras la purga
        publicarLectura("5.0", Instant.now().minusSeconds(2));
        esperar(2500);
        assertThat(contar("lectura")).isEqualTo(2);
    }

    // ===== AC-002 =====

    @Test
    @DisplayName("AC-002 / BR-003: si falla la escritura posterior, la transacción hace rollback (nada queda)")
    void ac002_rollback() {
        LecturaPersistida lectura = new LecturaPersistida(SENSOR, Instant.now().minusSeconds(5),
                new BigDecimal("5.0"), "METROS", Severidad.NORMAL);
        IngestaTransaccionalPort.OutboxAlerta outboxInvalido =
                new IngestaTransaccionalPort.OutboxAlerta(SENSOR, "alerta.warning", null); // payload NOT NULL → falla

        assertThatThrownBy(() -> store.persistir(lectura, "nat:rollback-test", outboxInvalido).block())
                .isInstanceOf(Exception.class);

        assertThat(contar("lectura")).as("rollback: sin lectura").isZero();
        assertThat(contar("lectura_procesada")).as("rollback: sin registro de dedupe").isZero();
        assertThat(contar("outbox_alerta")).isZero();
    }
}


