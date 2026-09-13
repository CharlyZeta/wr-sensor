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
import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.infrastructure.adapter.in.messaging.LecturasRabbitConsumer;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
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
import reactor.core.publisher.Mono;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FIX-0005 (Main Flow + AC-001..AC-007) con Postgres y RabbitMQ reales.
 *
 * <p>Las "instancias" se modelan en el mismo JVM pero con **estado propio**
 * (cada una tiene su {@link IngestorLecturas}, o sea su propio mapa {@code ultimaSeveridad}),
 * compartiendo broker y base: es exactamente el escenario de varios procesos consumiendo
 * particiones disjuntas. El consumer del contexto Spring se detiene al inicio para no
 * competir por las mismas colas y cada test arma sus instancias.</p>
 */
@SpringBootTest(properties = {
        "ingestion.ventana-segundos=3600",
        "ingestion.outbox.intervalo-ms=3600000",
        "ingestion.particiones.total=4"
})
class FIX0005MainFlowIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000f1");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000000f2");
    private static final int REGISTRY_PORT = 18145;
    private static final String EXCHANGE = "sensor.lecturas";
    private static final String PART = "sensor.lecturas.part";
    private static final String PATRON = "queue.sensor.lecturas.p{i}";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse(System.getenv().getOrDefault("IT_TIMESCALE_IMAGE", "postgres:16-alpine"))
                    .asCompatibleSubstituteFor("postgres"))
            .withNetwork(NETWORK).withDatabaseName("wrsensor_ingestion")
            .withUsername("wrsensor").withPassword("wrsensor").withReuse(true);

    /** Broker real con el exchange type de particionamiento habilitado (BR-009/AC-001). */
    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK)
            .withPluginsEnabled("rabbitmq_consistent_hash_exchange")
            .withReuse(true);

    /** Segundo broker donde `sensor.lecturas.part` ya existe con otro tipo (AC-007). */
    @SuppressWarnings("resource")
    private static final RabbitMQContainer RABBIT_SIN_HASH = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine"))
            .withNetwork(NETWORK).withReuse(true);

    private static HttpServer registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + DB.getHost() + ":" + DB.getFirstMappedPort()
                + "/wrsensor_ingestion");
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
        registry = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", REGISTRY_PORT), 0);
        registry.createContext("/api/auth/login", ex -> respond(ex, 200,
                "{\"token\":\"t\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}"));
        // cualquier sensor consultado existe y está ACTIVO, en METROS con bandas 4..6 / 2..8 / 0..10
        registry.createContext("/api/sensores/", ex -> {
            String path = ex.getRequestURI().getPath();
            UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            respond(ex, 200, "{\"id\":\"" + id + "\",\"codigo\":\"S-" + id.toString().substring(0, 8)
                    + "\",\"estado\":\"ACTIVO\",\"unidadMedida\":\"METROS\","
                    + "\"rangoNormal\":{\"min\":4,\"max\":6},\"rangoWarning\":{\"min\":2,\"max\":8},"
                    + "\"rangoCritical\":{\"min\":0,\"max\":10}}");
        });
        registry.start();
        DB.start();
        RABBIT.start();
        RABBIT_SIN_HASH.start();
    }

    @AfterAll
    static void stopAll() {
        if (registry != null) {
            registry.stop(0);
        }
        DB.stop();
        RABBIT.stop();
        RABBIT_SIN_HASH.stop();
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
    IngestorLecturas ingestorDelContexto;

    @Autowired
    LecturasRabbitConsumer consumerDelContexto;

    @Autowired
    IngestionProperties props;

    @Autowired
    SensorConfigPort sensores;

    @Autowired
    IngestaTransaccionalPort store;

    @Autowired
    Sender sender;

    @Autowired
    Receiver receiver;

    private Channel channel;
    private Connection conexion;
    private final List<Instancia> instancias = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        consumerDelContexto.detener();
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT.getHost());
        cf.setPort(RABBIT.getFirstMappedPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        conexion = cf.newConnection();
        channel = conexion.createChannel();
        channel.exchangeDeclare(EXCHANGE, "topic", true);

        ingestorDelContexto.setUltimaSeveridad(A, null);
        ingestorDelContexto.setUltimaSeveridad(B, null);
        db.sql("DELETE FROM outbox_alerta").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM lectura_procesada").fetch().rowsUpdated())
                .then(db.sql("DELETE FROM lectura").fetch().rowsUpdated())
                .then().block();
        limpiarColas();
    }

    /**
     * Deja las colas de partición y la DLQ sin consumers y sin mensajes: espera a que los
     * consumers de la corrida anterior se cierren (los mensajes en vuelo se reencolan) y purga
     * de forma iterativa hasta que las profundidades sean cero.
     */
    private void limpiarColas() {
        esperarHasta(() -> consumers(0) + consumers(1) + consumers(2) + consumers(3) == 0, 10000);
        List<String> todas = List.of(cola(0), cola(1), cola(2), cola(3),
                "queue.sensor.lecturas.dlq", "queue.sensor.lecturas.alt.p0",
                "queue.sensor.lecturas.alt.p1");
        for (int intento = 0; intento < 15; intento++) {
            for (String c : todas) {
                purgar(c);
            }
            if (profundidad(0) + profundidad(1) + profundidad(2) + profundidad(3) == 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Purga sin romper el canal principal: un 404 de cola inexistente cierra el canal que lo
     * pidió, así que cada intento usa un canal descartable.
     */
    private void purgar(String cola) {
        try (Channel ch = conexion.createChannel()) {
            ch.queuePurge(cola);
        } catch (Exception ignored) {
            // la cola todavía no existe: nada que purgar
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Instancia i : instancias) {
            i.consumer.detener();
        }
        instancias.clear();
        if (channel != null) {
            channel.close();
        }
        if (conexion != null) {
            conexion.close();
        }
    }

    // ================= helpers =================

    private static String cola(int i) {
        return PATRON.replace("{i}", String.valueOf(i));
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

    /** Instancia lógica de ingestion: consumer propio + ingestor con estado propio. */
    private final class Instancia {
        final AtomicInteger procesadas = new AtomicInteger();
        final LecturasRabbitConsumer consumer;

        Instancia(Integer total, List<Integer> asignadas, String exchange, String patron) {
            IngestionProperties cfg = conParticiones(new IngestionProperties.Particiones(
                    total, asignadas, exchange, patron));
            // contar intentos de procesamiento sin tocar la lógica del ingestor
            IngestorLecturas propio = new IngestorLecturas(sensores, store, cfg) {
                @Override
                public Mono<Resultado> procesar(LecturaEntrada lectura, Instant ahora) {
                    procesadas.incrementAndGet();
                    return super.procesar(lectura, ahora);
                }
            };
            this.consumer = new LecturasRabbitConsumer(sender, receiver, propio, cfg);
            this.consumer.iniciar();
        }

        int procesadas() {
            return procesadas.get();
        }
    }

    private Instancia instancia(Integer total, List<Integer> asignadas) {
        return instancia(total, asignadas, PART, PATRON);
    }

    private Instancia instancia(Integer total, List<Integer> asignadas, String exchange, String patron) {
        Instancia i = new Instancia(total, asignadas, exchange, patron);
        instancias.add(i);
        return i;
    }

    private IngestionProperties conParticiones(IngestionProperties.Particiones p) {
        return new IngestionProperties(props.ventanaSegundos(), props.registry(), props.lecturas(),
                props.alertas(), props.messaging(), props.outbox(), props.rangoFisico(), p,
                props.schema());
    }

    private void publicar(UUID sensor, String valor, String ts) throws IOException {
        String msg = "{\"sensorId\":\"" + sensor + "\",\"timestamp\":\"" + ts + "\",\"valor\":"
                + valor + ",\"unidadMedida\":\"METROS\"}";
        channel.basicPublish(EXCHANGE, "lectura." + sensor, null, msg.getBytes(StandardCharsets.UTF_8));
    }

    private void publicar(UUID sensor, String valor) throws IOException {
        publicar(sensor, valor, Instant.now().toString());
    }

    private long filas(UUID sensor) {
        Long n = db.sql("SELECT count(*) AS n FROM lectura WHERE sensor_id = $1")
                .bind(0, sensor).map((r, m) -> r.get("n", Long.class)).first().block();
        return n == null ? 0 : n;
    }

    private long eventos(UUID sensor) {
        Long n = db.sql("SELECT count(*) AS n FROM outbox_alerta WHERE sensor_id = $1")
                .bind(0, sensor).map((r, m) -> r.get("n", Long.class)).first().block();
        return n == null ? 0 : n;
    }

    private List<String> eventosDe(UUID sensor) {
        return db.sql("SELECT payload FROM outbox_alerta WHERE sensor_id = $1 ORDER BY id")
                .bind(0, sensor).map((r, m) -> r.get("payload", String.class)).all().collectList().block();
    }

    private List<String> severidadesDe(UUID sensor) {
        return db.sql("SELECT severidad FROM lectura WHERE sensor_id = $1 ORDER BY ts")
                .bind(0, sensor).map((r, m) -> String.valueOf(r.get("severidad")))
                .all().collectList().block();
    }

    // ===== Management API de RabbitMQ (topología/consumers/profundidad) =====

    private String api(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(RABBIT.getHttpUrl() + "/api/" + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword())
                                    .getBytes(StandardCharsets.UTF_8)))
                    .GET().build();
            HttpResponse<String> res = HttpClient.newHttpClient().send(req,
                    HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? res.body() : "<HTTP " + res.statusCode() + ">";
        } catch (Exception e) {
            return "<ERROR " + e.getMessage() + ">";
        }
    }

    private static long numero(String json, String campo) {
        if (json.startsWith("<")) {
            return -1;                        // error HTTP (cola inexistente o API caída)
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + campo + "\":(\\d+)").matcher(json);
        // RabbitMQ omite los campos en cero: ausencia = 0
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private long consumers(int particion) {
        return Math.max(0, numero(api("queues/%2F/" + cola(particion)), "consumers"));
    }

    private long profundidad(int particion) {
        return Math.max(0, numero(api("queues/%2F/" + cola(particion)), "messages_ready"));
    }

    private long profundidadAlt(int particion) {
        return Math.max(0, numero(api("queues/%2F/queue.sensor.lecturas.alt.p" + particion),
                "messages_ready"));
    }

    // ================= Main Flow + AC-002 =================

    @Test
    @DisplayName("Main Flow / AC-002: NORMAL→WARNING→NORMAL del mismo sensor produce 2 transiciones "
            + "idénticas con 3 instancias (afinidad) y con 1 instancia")
    void mainFlowAc002() throws Exception {
        // --- 3 instancias disjuntas (asignadas = [0], [1], [2,3]) ---
        Instancia i0 = instancia(4, List.of(0));
        Instancia i1 = instancia(4, List.of(1));
        Instancia i2 = instancia(4, List.of(2, 3));

        publicar(A, "5.0");   // NORMAL
        Thread.sleep(120);
        publicar(A, "7.0");   // WARNING  → transición
        Thread.sleep(120);
        publicar(A, "5.0");   // NORMAL   → transición

        esperarHasta(() -> filas(A) == 3 && eventos(A) == 2, 20000);

        assertThat(severidadesDe(A)).containsExactly("NORMAL", "WARNING", "NORMAL");
        assertThat(eventos(A)).as("2 transiciones (no se pierde ninguna)").isEqualTo(2);
        List<String> payloads = eventosDe(A);
        assertThat(payloads.get(0)).contains("\"severidadAnterior\":\"NORMAL\"")
                .contains("\"severidadNueva\":\"WARNING\"");
        assertThat(payloads.get(1)).contains("\"severidadAnterior\":\"WARNING\"")
                .contains("\"severidadNueva\":\"NORMAL\"");

        List<Integer> conteos = List.of(i0.procesadas(), i1.procesadas(), i2.procesadas());
        assertThat(conteos.stream().mapToInt(Integer::intValue).sum())
                .as("las 3 lecturas se procesan (una sola vez cada una)").isEqualTo(3);
        assertThat(conteos.stream().filter(n -> n > 0).count())
                .as("afinidad: un único dueño para ese sensor").isEqualTo(1);

        // --- 1 instancia (todas las particiones) ---
        for (Instancia i : instancias) {
            i.consumer.detener();
        }
        instancias.clear();
        for (String cola : List.of(cola(0), cola(1), cola(2), cola(3))) {
            purgar(cola);
        }
        Instancia unica = instancia(4, null);

        publicar(B, "5.0");
        Thread.sleep(120);
        publicar(B, "7.0");
        Thread.sleep(120);
        publicar(B, "5.0");
        esperarHasta(() -> filas(B) == 3 && eventos(B) == 2, 20000);

        assertThat(severidadesDe(B)).containsExactly("NORMAL", "WARNING", "NORMAL");
        assertThat(eventos(B)).as("mismo resultado que con 3 instancias").isEqualTo(2);
        assertThat(unica.procesadas()).isEqualTo(3);
    }

    // ================= AC-001 =================

    @Test
    @DisplayName("AC-001: la topología particionada existe (x-consistent-hash + e2e lectura.# + "
            + "4 colas con peso 1) y no altera las colas de los otros servicios")
    void ac001_topologia() throws Exception {
        channel.queueDeclare("query.sensor.lecturas", true, false, false, null);
        channel.queueBind("query.sensor.lecturas", EXCHANGE, "lectura.#");
        try {
            // la cola única previa a FIX-0005 queda retirada (BR-010): si existía, se borra
            channel.queueDelete("queue.sensor.lecturas");
        } catch (IOException ignored) {
            // no existía
        }

        instancia(4, List.of(0));

        esperarHasta(() -> api("exchanges/%2F/" + PART).contains("x-consistent-hash")
                && api("queues/%2F/" + cola(3)).contains(cola(3)), 15000);

        assertThat(api("exchanges/%2F/" + PART)).contains("\"type\":\"x-consistent-hash\"");
        String bindingsPart = api("exchanges/%2F/" + PART + "/bindings/source");
        assertThat(bindingsPart).as("4 colas bindeadas con peso 1").contains(
                cola(0), cola(1), cola(2), cola(3));
        assertThat(bindingsPart.split("\"routing_key\":\"1\"", -1).length - 1)
                .as("una binding con peso 1 por partición").isEqualTo(4);
        assertThat(api("exchanges/%2F/" + EXCHANGE + "/bindings/source"))
                .as("binding exchange-to-exchange con lectura.#")
                .contains(PART).contains("lectura.#");

        assertThat(api("queues/%2F/query.sensor.lecturas"))
                .as("la cola de query-api sigue existiendo y bindeada a sensor.lecturas")
                .contains("query.sensor.lecturas");
        assertThat(api("queues/%2F/queue.sensor.lecturas"))
                .as("la cola única anterior ya no se declara").startsWith("<HTTP 404");
    }

    // ================= AC-003 =================

    @Test
    @DisplayName("AC-003: cambiar total a 2 (solo configuración, sin recompilar) declara "
            + "exactamente 2 colas y ninguna lectura queda sin consumir")
    void ac003_totalConfigurable() throws Exception {
        Instancia dos = instancia(2, null, "sensor.lecturas.part2", "queue.sensor.lecturas.alt.p{i}");

        // el declare es asíncrono: esperar a que la topología de 2 particiones esté declarada
        esperarHasta(() -> api("queues/%2F/queue.sensor.lecturas.alt.p0").contains("alt.p0")
                && api("queues/%2F/queue.sensor.lecturas.alt.p1").contains("alt.p1"), 15000);

        assertThat(api("queues/%2F/queue.sensor.lecturas.alt.p0")).contains("alt.p0");
        assertThat(api("queues/%2F/queue.sensor.lecturas.alt.p1")).contains("alt.p1");
        assertThat(api("queues/%2F/queue.sensor.lecturas.alt.p2"))
                .as("con total=2 no se declara la tercera partición").startsWith("<HTTP 404");
        assertThat(api("queues/%2F/queue.sensor.lecturas.p2"))
                .as("el namespace de 4 particiones sigue existiendo (config previa)").contains(cola(2));
        assertThat(profundidadAlt(0) + profundidadAlt(1)).as("colas nuevas vacías").isZero();

        for (int i = 0; i < 4; i++) {
            publicar(UUID.fromString(String.format("00000000-0000-4000-8000-0000000001%02d", i)), "5.0");
        }
        esperarHasta(() -> dos.procesadas() == 4, 20000);

        assertThat(dos.procesadas()).isEqualTo(4);
        esperarHasta(() -> profundidadAlt(0) + profundidadAlt(1) == 0, 10000);
        assertThat(profundidadAlt(0) + profundidadAlt(1))
                .as("todo lo publicado en el namespace de 2 particiones se consumió").isZero();
    }

    // ================= AC-004 =================

    @Test
    @DisplayName("AC-004: con asignadas [0] y [1] cada cola tiene a lo sumo 1 consumer "
            + "(p0/p1 = 1, p2/p3 = 0) y nunca hay consumers concurrentes sobre la misma cola")
    void ac004_consumersPorParticion() throws Exception {
        instancia(4, List.of(0));
        instancia(4, List.of(1));

        esperarHasta(() -> consumers(0) == 1 && consumers(1) == 1, 10000);
        // las demás particiones no tienen consumer asignado (0 explícito o campo omitido)
        esperarHasta(() -> consumers(2) == 0 && consumers(3) == 0, 10000);
        assertThat(consumers(0)).isEqualTo(1);
        assertThat(consumers(1)).isEqualTo(1);
        assertThat(consumers(2)).as("sin consumer asignado").isZero();
        assertThat(consumers(3)).as("sin consumer asignado").isZero();
    }

    // ================= AC-005 =================

    @Test
    @DisplayName("AC-005: por partición se preservan DLQ con x-rechazo e idempotencia "
            + "(un redelivery no duplica)")
    void ac005_dlqEIdempotencia() throws Exception {
        instancia(4, null);

        // (a) payload inválido → DLQ con header x-rechazo
        channel.basicPublish(EXCHANGE, "lectura." + A, null,
                "{\"sensorId\":\"no-es-uuid\"}".getBytes(StandardCharsets.UTF_8));
        java.util.concurrent.atomic.AtomicReference<GetResponse> recibido =
                new java.util.concurrent.atomic.AtomicReference<>();
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
        assertThat(rechazado).as("el mensaje inválido llegó a la DLQ").isNotNull();
        assertThat(new String((byte[]) rechazado.getProps().getHeaders().get("x-rechazo"),
                StandardCharsets.UTF_8)).isEqualTo("PAYLOAD_INVALID");
        assertThat(filas(A)).as("una lectura inválida no se persiste").isZero();

        // (b) mismo mensaje publicado dos veces (misma clave natural) → una sola fila
        String ts = Instant.now().toString();
        publicar(B, "5.0", ts);
        publicar(B, "5.0", ts);
        esperarHasta(() -> filas(B) >= 1, 20000);
        Thread.sleep(700);
        assertThat(filas(B)).as("idempotencia por clave natural (FIX-0003)").isEqualTo(1);
    }

    private GetResponse basicGetDlq() {
        try {
            return channel.basicGet("queue.sensor.lecturas.dlq", true);
        } catch (IOException e) {
            return null;
        }
    }

    // ================= AC-006 =================

    @Test
    @DisplayName("AC-006: con total=4 y una instancia que consume solo p0 se emite WARN con las "
            + "particiones sin consumer y ningún mensaje se descarta")
    void ac006_sinConsumerNoPierde() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class);
        logger.addAppender(logs);
        Instancia solo;
        try {
            solo = instancia(4, List.of(0));
            final Instancia asignada = solo;

            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("particiones sin consumer")
                    && e.getFormattedMessage().contains("1, 2, 3"));
            assertThat(consumers(1)).isZero();
            assertThat(consumers(2)).isZero();

            for (int i = 0; i < 8; i++) {
                publicar(UUID.randomUUID(), "5.0");
            }
            esperarHasta(() -> asignada.procesadas() + profundidad(1) + profundidad(2)
                            + profundidad(3) == 8 && profundidad(0) == 0,
                    20000);
            assertThat(solo.procesadas() + profundidad(1) + profundidad(2) + profundidad(3))
                    .as("nada se pierde: lo asignado se procesa y el resto queda en cola")
                    .isEqualTo(8);
            assertThat(profundidad(0)).as("la partición asignada no acumula").isZero();
            assertThat(basicGetDlq()).as("no se descarta nada a la DLQ").isNull();
        } finally {
            logger.detachAppender(logs);
        }
    }

    // ================= AC-007 =================

    @Test
    @DisplayName("AC-007: si el exchange de particiones no se puede declarar, la instancia "
            + "registra ERROR y no queda ningún consumer (no consume sin particionar)")
    void ac007_failFast() throws Exception {
        // broker aparte: sensor.lecturas.part existe como topic → declararlo como
        // x-consistent-hash falla (inequivalent arg 'type'), sin depender del plugin
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(RABBIT_SIN_HASH.getHost());
        cf.setPort(RABBIT_SIN_HASH.getFirstMappedPort());
        cf.setUsername(RABBIT_SIN_HASH.getAdminUsername());
        cf.setPassword(RABBIT_SIN_HASH.getAdminPassword());
        try (Connection conn = cf.newConnection(); Channel ch = conn.createChannel()) {
            ch.exchangeDeclare(PART, "topic", true);
        }

        Sender senderAparte = RabbitFlux.createSender(
                new SenderOptions().connectionFactory(cf));
        Receiver receiverAparte = RabbitFlux.createReceiver(
                new ReceiverOptions().connectionFactory(cf));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class);
        logger.addAppender(logs);
        try {
            IngestionProperties cfg = conParticiones(new IngestionProperties.Particiones(
                    4, null, PART, PATRON));
            new LecturasRabbitConsumer(senderAparte, receiverAparte,
                    new IngestorLecturas(sensores, store, cfg), cfg).iniciar();
            Thread.sleep(1500);

            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("no se inicia el consumo"));

            // ninguna cola de partición quedó declarada con consumer en ese broker
            String apiSinHash = apiEn(RABBIT_SIN_HASH, "queues/%2F/" + cola(0));
            assertThat(apiSinHash).as("no se declara/consume sin particionar")
                    .startsWith("<HTTP 404");
        } finally {
            logger.detachAppender(logs);
            senderAparte.close();
            receiverAparte.close();
        }
    }

    private static String apiEn(RabbitMQContainer broker, String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(broker.getHttpUrl() + "/api/" + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (broker.getAdminUsername() + ":" + broker.getAdminPassword())
                                    .getBytes(StandardCharsets.UTF_8)))
                    .GET().build();
            HttpResponse<String> res = HttpClient.newHttpClient().send(req,
                    HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 ? res.body() : "<HTTP " + res.statusCode() + ">";
        } catch (Exception e) {
            return "<ERROR " + e.getMessage() + ">";
        }
    }
}
