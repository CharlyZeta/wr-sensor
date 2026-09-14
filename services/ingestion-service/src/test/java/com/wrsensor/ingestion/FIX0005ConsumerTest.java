package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.Calidad;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;
import com.wrsensor.ingestion.infrastructure.adapter.in.messaging.LecturasRabbitConsumer;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FIX-0005 — comportamiento del consumer particionado (unit tests BR-002, BR-003, BR-005,
 * BR-006, BR-008, BR-009 y AF-03, AF-04). Broker simulado con mocks de Sender/Receiver.
 */
class FIX0005ConsumerTest {

    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private Sender sender;
    private Receiver receiver;

    @BeforeEach
    void setUp() {
        sender = mock(Sender.class);
        receiver = mock(Receiver.class);
        when(sender.declare(any(ExchangeSpecification.class))).thenReturn(Mono.empty());
        when(sender.declare(any(QueueSpecification.class))).thenReturn(Mono.empty());
        when(sender.bind(any(BindingSpecification.class))).thenReturn(Mono.empty());
        when(sender.bindExchange(any(BindingSpecification.class))).thenReturn(Mono.empty());
        when(sender.send(any(Mono.class))).thenReturn(Mono.empty());
        when(receiver.consumeManualAck(any(String.class), any(ConsumeOptions.class)))
                .thenReturn(Flux.never());
    }

    // ================= helpers =================

    private static IngestionProperties props(Integer total, List<Integer> asignadas) {
        return props(total, asignadas, "queue.sensor.lecturas.p{i}");
    }

    private static IngestionProperties props(Integer total, List<Integer> asignadas, String patron) {
        return new IngestionProperties(
                300L,
                new IngestionProperties.Registry("http://x",
                        new IngestionProperties.Registry.Auth("a", "b"), null, null, null, null),
                new IngestionProperties.Lecturas("sensor.lecturas", "queue.sensor.lecturas.dlq",
                        "sensor.lecturas.dlx"),
                new IngestionProperties.Alertas("sensor.alertas"),
                new IngestionProperties.Messaging(3, "sensor.lecturas.dlx"),
                new IngestionProperties.Outbox(null, null, null, null, null, null, null),
                new IngestionProperties.RangoFisico(
                        java.util.Map.of("METROS", new IngestionProperties.RangoFisico.Rango(
                                new BigDecimal("-1.0"), new BigDecimal("15.0"))),
                        java.util.Map.of()),
                new IngestionProperties.Particiones(total, asignadas, "sensor.lecturas.part", patron),
                new IngestionProperties.Schema(null, null));
    }

    private static byte[] cuerpo(UUID sensor, String valor, String ts) {
        return ("{\"sensorId\":\"" + sensor + "\",\"timestamp\":\"" + ts + "\",\"valor\":" + valor
                + ",\"unidadMedida\":\"METROS\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** Entrega real (Delivery + AcknowledgableDelivery) cuyo ack termina en el Channel mock. */
    private static AcknowledgableDelivery entrega(Channel canal, long tag, byte[] body) {
        return new AcknowledgableDelivery(
                new Delivery(new Envelope(tag, false, "sensor.lecturas", "lectura." + SENSOR), null, body),
                canal,
                (ctx, ex) -> ctx.ackOrNack());
    }

    private static void esperarHasta(BooleanSupplier cond, long timeoutMs) {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite && !cond.getAsBoolean()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ListAppender<ILoggingEvent> capturarLog(Class<?> clase) {
        Logger logger = (Logger) LoggerFactory.getLogger(clase);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // ================= BR-002: orden intra-partición =================

    @Test
    @DisplayName("BR-002: un consumer por partición con qos=1 y procesamiento secuencial "
            + "(nunca dos lecturas de la misma partición en vuelo)")
    void br002_procesoSecuencial() throws Exception {
        Channel canal = mock(Channel.class);
        AtomicInteger enVuelo = new AtomicInteger();
        AtomicInteger maxEnVuelo = new AtomicInteger();
        List<String> procesadas = new ArrayList<>();
        IngestorLecturas ingestor = mock(IngestorLecturas.class);
        when(ingestor.procesar(any())).thenAnswer(inv -> {
            LecturaEntrada l = inv.getArgument(0, LecturaEntrada.class);
            return Mono.defer(() -> {
                maxEnVuelo.accumulateAndGet(enVuelo.incrementAndGet(), Math::max);
                return Mono.delay(Duration.ofMillis(40))
                        .then(Mono.fromSupplier(() -> {
                            procesadas.add(l.valor().toPlainString());
                            enVuelo.decrementAndGet();   // termina antes de pedir el siguiente
                            return new IngestorLecturas.Resultado(SENSOR, Severidad.NORMAL,
                                    Calidad.OK, false, false);
                        }));
            });
        });

        List<AcknowledgableDelivery> entregas = List.of(
                entrega(canal, 1L, cuerpo(SENSOR, "5.0", "2026-09-11T10:00:00Z")),
                entrega(canal, 2L, cuerpo(SENSOR, "7.0", "2026-09-11T10:00:01Z")),
                entrega(canal, 3L, cuerpo(SENSOR, "6.0", "2026-09-11T10:00:02Z")));
        when(receiver.consumeManualAck(eq("queue.sensor.lecturas.p2"), any(ConsumeOptions.class)))
                .thenReturn(Flux.fromIterable(entregas));

        new LecturasRabbitConsumer(sender, receiver, ingestor, props(4, List.of(2))).iniciar();

        esperarHasta(() -> procesadas.size() == 3, 5000);
        assertThat(procesadas).as("orden de procesamiento = orden de entrega")
                .containsExactly("5.0", "7.0", "6.0");
        assertThat(maxEnVuelo.get()).as("sin paralelismo dentro de la partición").isEqualTo(1);

        InOrder orden = inOrder(canal);
        orden.verify(canal).basicAck(1L, false);
        orden.verify(canal).basicAck(2L, false);
        orden.verify(canal).basicAck(3L, false);

        ArgumentCaptor<ConsumeOptions> opciones = ArgumentCaptor.forClass(ConsumeOptions.class);
        verify(receiver).consumeManualAck(eq("queue.sensor.lecturas.p2"), opciones.capture());
        assertThat(opciones.getValue().getQos()).as("qos = 1 (BR-002)").isEqualTo(1);
    }

    // ================= BR-003: topología particionada =================

    @Test
    @DisplayName("BR-003: exchange x-consistent-hash + binding e2e (lectura.#) + N colas con peso 1")
    void br003_topologia() {
        new LecturasRabbitConsumer(sender, receiver, mock(IngestorLecturas.class),
                props(4, null)).iniciar();

        ArgumentCaptor<ExchangeSpecification> exchanges = ArgumentCaptor.forClass(ExchangeSpecification.class);
        verify(sender, times(3)).declare(exchanges.capture());
        List<ExchangeSpecification> declarados = exchanges.getAllValues();
        ExchangeSpecification hash = declarados.stream()
                .filter(e -> "sensor.lecturas.part".equals(e.getName())).findFirst().orElseThrow();
        assertThat(hash.getType()).isEqualTo("x-consistent-hash");
        assertThat(hash.isDurable()).isTrue();
        assertThat(declarados).anyMatch(e -> "sensor.lecturas".equals(e.getName())
                && "topic".equals(e.getType()));
        assertThat(declarados).anyMatch(e -> "sensor.lecturas.dlx".equals(e.getName()));

        ArgumentCaptor<BindingSpecification> e2e = ArgumentCaptor.forClass(BindingSpecification.class);
        verify(sender).bindExchange(e2e.capture());
        assertThat(e2e.getValue().getExchange()).isEqualTo("sensor.lecturas");
        assertThat(e2e.getValue().getExchangeTo()).isEqualTo("sensor.lecturas.part");
        assertThat(e2e.getValue().getRoutingKey())
                .as("la routing key del sensor (lectura.{sensorId}) llega intacta al hash")
                .isEqualTo("lectura.#");

        ArgumentCaptor<QueueSpecification> colas = ArgumentCaptor.forClass(QueueSpecification.class);
        verify(sender, times(5)).declare(colas.capture());   // 4 particiones + 1 DLQ
        List<String> nombres = colas.getAllValues().stream().map(QueueSpecification::getName).toList();
        assertThat(nombres).contains("queue.sensor.lecturas.p0", "queue.sensor.lecturas.p1",
                "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3",
                "queue.sensor.lecturas.dlq");

        ArgumentCaptor<BindingSpecification> bindings = ArgumentCaptor.forClass(BindingSpecification.class);
        verify(sender, times(5)).bind(bindings.capture());   // 1 dlq + 4 particiones
        List<BindingSpecification> pesos = bindings.getAllValues().stream()
                .filter(b -> "sensor.lecturas.part".equals(b.getExchange())).toList();
        assertThat(pesos).hasSize(4);
        assertThat(pesos).allMatch(b -> "1".equals(b.getRoutingKey()));
        assertThat(pesos.stream().map(BindingSpecification::getQueue))
                .containsExactlyInAnyOrder("queue.sensor.lecturas.p0", "queue.sensor.lecturas.p1",
                        "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3");
    }

    // ================= BR-005: declarar todo, consumir lo propio =================

    @Test
    @DisplayName("BR-005: declara las 4 colas y consume solo la partición asignada "
            + "(nunca dos consumers sobre la misma cola)")
    void br005_declaraTodoConsumeAsignado() {
        new LecturasRabbitConsumer(sender, receiver, mock(IngestorLecturas.class),
                props(4, List.of(2))).iniciar();

        ArgumentCaptor<QueueSpecification> colas = ArgumentCaptor.forClass(QueueSpecification.class);
        verify(sender, times(5)).declare(colas.capture());
        assertThat(colas.getAllValues().stream().map(QueueSpecification::getName))
                .contains("queue.sensor.lecturas.p0", "queue.sensor.lecturas.p1",
                        "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3");

        ArgumentCaptor<String> consumidas = ArgumentCaptor.forClass(String.class);
        verify(receiver, times(1)).consumeManualAck(consumidas.capture(), any(ConsumeOptions.class));
        assertThat(consumidas.getAllValues()).containsExactly("queue.sensor.lecturas.p2");
    }

    // ================= BR-006: DLX por partición + rechazo a DLQ =================

    @Test
    @DisplayName("BR-006: cada cola de partición lleva x-dead-letter-exchange y un rechazo "
            + "termina en la DLQ con header x-rechazo")
    void br006_dlxYRechazo() throws Exception {
        Channel canal = mock(Channel.class);
        byte[] roto = "{\"sensorId\":\"no-es-uuid\",\"valor\":1}".getBytes(StandardCharsets.UTF_8);
        when(receiver.consumeManualAck(eq("queue.sensor.lecturas.p0"), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(entrega(canal, 9L, roto)));

        new LecturasRabbitConsumer(sender, receiver, realIngestor(), props(4, List.of(0))).iniciar();

        ArgumentCaptor<QueueSpecification> colas = ArgumentCaptor.forClass(QueueSpecification.class);
        verify(sender, times(5)).declare(colas.capture());
        List<QueueSpecification> particiones = colas.getAllValues().stream()
                .filter(q -> q.getName().startsWith("queue.sensor.lecturas.p")).toList();
        assertThat(particiones).hasSize(4);
        assertThat(particiones).allSatisfy(q -> assertThat(q.getArguments())
                .containsEntry("x-dead-letter-exchange", "sensor.lecturas.dlx"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<OutboundMessage>> enviados = ArgumentCaptor.forClass(Mono.class);
        esperarHasta(() -> {
            try {
                verify(sender, times(1)).send(enviados.capture());
                return true;
            } catch (Exception e) {
                return false;
            }
        }, 3000);
        OutboundMessage msg = enviados.getValue().block();
        assertThat(msg.getExchange()).isEqualTo("sensor.lecturas.dlx");
        assertThat(msg.getProperties().getHeaders()).containsKey("x-rechazo");
        assertThat(new String((byte[]) msg.getProperties().getHeaders().get("x-rechazo"),
                StandardCharsets.UTF_8)).isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
        // El rechazo se publica en la DLQ y recién después se asienta (ack): es el
        // comportamiento vigente de FEAT-0011 y evita el loop de poison message.
        verify(canal, times(1)).basicAck(9L, false);
    }

    // ================= BR-008: particiones sin consumer =================

    @Test
    @DisplayName("BR-008: con particiones sin consumer se registra WARN indicando cuáles")
    void br008_warnSinConsumer() {
        ListAppender<ILoggingEvent> logs = capturarLog(LecturasRabbitConsumer.class);
        try {
            new LecturasRabbitConsumer(sender, receiver, mock(IngestorLecturas.class),
                    props(4, List.of(0))).iniciar();
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("particiones sin consumer")
                    && e.getFormattedMessage().contains("1, 2, 3"));
        } finally {
            ((Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class)).detachAppender(logs);
        }
    }

    // ================= BR-009: fail-fast de topología =================

    @Test
    @DisplayName("BR-009: si no se puede declarar el exchange de particiones → ERROR y ningún "
            + "consumer (nunca consume sin particionar)")
    void br009_failFastTopologia() {
        when(sender.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.empty())
                .thenReturn(Mono.empty())
                .thenReturn(Mono.error(new RuntimeException(
                        "COMMAND_INVALID - unknown exchange type 'x-consistent-hash'")));

        ListAppender<ILoggingEvent> logs = capturarLog(LecturasRabbitConsumer.class);
        try {
            new LecturasRabbitConsumer(sender, receiver, mock(IngestorLecturas.class),
                    props(4, null)).iniciar();
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("no se inicia el consumo"));
            verifyNoInteractions(receiver);
        } finally {
            ((Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class)).detachAppender(logs);
        }
    }

    @Test
    @DisplayName("BR-009/AF-02: configuración de particiones inválida → ERROR y ningún consumer")
    void br009_configInvalida() {
        ListAppender<ILoggingEvent> logs = capturarLog(LecturasRabbitConsumer.class);
        try {
            new LecturasRabbitConsumer(sender, receiver, mock(IngestorLecturas.class),
                    props(0, null)).iniciar();
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.ERROR
                    && e.getFormattedMessage().contains("configuracion de particiones invalida"));
            verifyNoInteractions(receiver);
            verify(sender, never()).declare(any(ExchangeSpecification.class));
        } finally {
            ((Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class)).detachAppender(logs);
        }
    }

    // ================= AF-03: partición caída no descarta =================

    @Test
    @DisplayName("AF-03: si una partición se cae, lo ya procesado se ackea y lo pendiente no se "
            + "descarta (no va a la DLQ, se reentrega al reconectar)")
    void af03_particionCaida() throws Exception {
        Channel canal = mock(Channel.class);
        IngestorLecturas ingestor = mock(IngestorLecturas.class);
        when(ingestor.procesar(any())).thenReturn(Mono.just(new IngestorLecturas.Resultado(SENSOR,
                Severidad.NORMAL, Calidad.OK, false, false)));
        when(receiver.consumeManualAck(eq("queue.sensor.lecturas.p1"), any(ConsumeOptions.class)))
                .thenReturn(Flux.concat(
                        Flux.just(entrega(canal, 1L, cuerpo(SENSOR, "5.0", "2026-09-11T10:00:00Z"))),
                        Flux.error(new RuntimeException("conexion perdida"))));

        new LecturasRabbitConsumer(sender, receiver, ingestor, props(4, List.of(1))).iniciar();

        esperarHasta(() -> {
            try {
                verify(canal, times(1)).basicAck(1L, false);
                return true;
            } catch (Exception e) {
                return false;
            }
        }, 3000);
        verify(sender, never()).send(any(Mono.class));   // la caída no manda nada a la DLQ
    }

    // ================= AF-04: redelivery idempotente =================

    @Test
    @DisplayName("AF-04: redelivery con la misma clave de idempotencia no duplica fila ni "
            + "re-emite la transición de severidad")
    void af04_redeliveryNoDuplica() throws Exception {
        Channel canal = mock(Channel.class);
        Instant ts = Instant.now();   // dentro de la ventana temporal de ingestion
        byte[] body = cuerpo(SENSOR, "7.0", ts.toString());   // clave natural estable
        when(receiver.consumeManualAck(eq("queue.sensor.lecturas.p3"), any(ConsumeOptions.class)))
                .thenReturn(Flux.just(entrega(canal, 1L, body), entrega(canal, 2L, body)));

        IngestorLecturas ingestor = realIngestor();
        ingestor.setUltimaSeveridad(SENSOR, Severidad.NORMAL);

        new LecturasRabbitConsumer(sender, receiver, ingestor, props(4, List.of(3))).iniciar();

        esperarHasta(() -> {
            try {
                verify(canal, times(2)).basicAck(anyLong(), eq(false));
                return true;
            } catch (Exception e) {
                return false;
            }
        }, 3000);

        assertThat(store.filas).as("una sola fila pese al redelivery").hasSize(1);
        assertThat(store.outboxes).as("una sola transición").hasSize(1);
        assertThat(store.claves).containsExactly(store.claves.get(0), store.claves.get(0));
        assertThat(store.claves.get(0)).isEqualTo("nat:" + SENSOR + ":" + ts.toEpochMilli());
    }

    // ================= ingestor real con puertos fake (BR-006 / AF-04) =================

    private FakeStore store;

    private static final class FakeSensores implements SensorConfigPort {
        @Override
        public Mono<SensorInfo> findById(UUID sensorId) {
            return sensorId.equals(SENSOR)
                    ? Mono.just(new SensorInfo(SENSOR, "PARANA-RECONQUISTA", "ACTIVO", "METROS",
                            new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
                            new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
                            new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10"))))
                    : Mono.empty();
        }
    }

    private final class FakeStore implements IngestaTransaccionalPort {
        final List<LecturaPersistida> filas = new ArrayList<>();
        final List<OutboxAlerta> outboxes = new ArrayList<>();
        final List<String> claves = new ArrayList<>();
        final Set<String> vistas = new HashSet<>();

        @Override
        public Mono<Resultado> persistir(LecturaPersistida lectura, String clave, OutboxAlerta outbox) {
            claves.add(clave);
            if (!vistas.add(clave)) {
                return Mono.just(new Resultado(false));
            }
            filas.add(lectura);
            if (outbox != null) {
                outboxes.add(outbox);
            }
            return Mono.just(new Resultado(true));
        }
    }

    private IngestorLecturas realIngestor() {
        store = new FakeStore();
        return new IngestorLecturas(new FakeSensores(), store, props(4, null));
    }
}
