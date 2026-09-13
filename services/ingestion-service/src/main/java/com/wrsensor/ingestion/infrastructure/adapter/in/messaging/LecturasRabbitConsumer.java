package com.wrsensor.ingestion.infrastructure.adapter.in.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.application.service.RegistroEsquema;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import com.wrsensor.ingestion.infrastructure.config.ParticionesPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adapter in (messaging): consume `sensor.lecturas` **particionado por sensorId**
 * (FIX-0005, sobre FEAT-0011/FIX-0003/FIX-0004).
 *
 * <p>Topología (BR-003): el exchange topic `sensor.lecturas` reenvía con binding
 * exchange-to-exchange (`lectura.#`) al exchange `x-consistent-hash`
 * `sensor.lecturas.part`, que hashea la routing key (`lectura.{sensorId}`, publicada por
 * `data-simulator` sin cambios) y enruta cada mensaje a **una** de las `N` colas
 * `queue.sensor.lecturas.p{i}` (peso de binding "1"). Eso da afinidad
 * sensor → partición → instancia (BR-001/BR-004/BR-007).</p>
 *
 * <p>Cada instancia declara la topología **completa** (idempotente) y consume solo sus
 * particiones asignadas (BR-005), con **un** consumer por partición, `qos = 1` y
 * procesamiento secuencial (`concatMap`) — BR-002. Ack manual tras persistir, rechazos a la
 * DLQ con header `x-rechazo` (BR-006).</p>
 *
 * <p>Fail-fast (BR-009): si la configuración de particiones es inválida o la topología no se
 * puede declarar, se registra ERROR y **no** se inicia el consumo; nunca se degrada en
 * silencio a un consumo no particionado.</p>
 */
@Component
public class LecturasRabbitConsumer {

    private static final Logger log = LoggerFactory.getLogger(LecturasRabbitConsumer.class);

    private final Sender sender;
    private final Receiver receiver;
    private final IngestorLecturas ingestor;
    private final RegistroEsquema registroEsquema;
    private final IngestionProperties props;

    private final reactor.core.Disposable.Composite suscripciones = Disposables.composite();
    private volatile ParticionesPlan plan;

    @org.springframework.beans.factory.annotation.Autowired
    public LecturasRabbitConsumer(Sender sender, Receiver receiver,
                                  IngestorLecturas ingestor, RegistroEsquema registroEsquema,
                                  IngestionProperties props) {
        this.sender = sender;
        this.receiver = receiver;
        this.ingestor = ingestor;
        this.registroEsquema = registroEsquema;
        this.props = props;
    }

    /** Conveniencia (tests): usa la política de schema por default de la configuración. */
    public LecturasRabbitConsumer(Sender sender, Receiver receiver, IngestorLecturas ingestor,
                                  IngestionProperties props) {
        this(sender, receiver, ingestor, new RegistroEsquema(props.schema()), props);
    }

    @jakarta.annotation.PostConstruct
    public void iniciar() {
        ParticionesPlan planLocal;
        try {
            planLocal = ParticionesPlan.de(props.particiones());   // BR-004 / AF-01 / AF-02
        } catch (IllegalStateException e) {
            log.error("[ingestion] configuracion de particiones invalida, no se inicia el "
                    + "consumo (fail-fast): {}", e.getMessage());
            return;
        }
        this.plan = planLocal;

        if (!planLocal.cubreTodo()) {                              // BR-008
            log.warn("[ingestion] particiones sin consumer en esta instancia: {} "
                            + "(total={}, asignadas={}); sus mensajes quedan en cola, no se pierden",
                    planLocal.sinConsumer(), planLocal.total(), planLocal.asignadas());
        }

        Disposable consumo = declararTopologia(planLocal)
                .thenMany(consumir(planLocal))
                .doOnError(err -> log.error("[ingestion] topologia de particiones no declarada, "
                        + "no se inicia el consumo (fail-fast): {}", err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .subscribe();
        suscripciones.add(consumo);

        log.info("[ingestion] consumo particionado: total={} asignadas={} exchange={} colas={}",
                planLocal.total(), planLocal.asignadas(), planLocal.exchange(),
                planLocal.colasAsignadas());
    }

    /** Detiene el consumo (shutdown y tests). */
    @jakarta.annotation.PreDestroy
    public void detener() {
        if (!suscripciones.isDisposed()) {
            suscripciones.dispose();
        }
    }

    /** Plan activo (exposed para tests/observabilidad). */
    ParticionesPlan plan() {
        return plan;
    }

    // ============ topología (BR-003/BR-005/BR-006) ============

    private Mono<Void> declararTopologia(ParticionesPlan p) {
        String exchange = props.lecturas().exchange();
        String dlx = props.lecturas().dlx();
        String dlq = props.lecturas().dlqQueue();

        // 1) exchange topic de entrada + DLX/DLQ
        Mono<Void> base = sender.declare(ExchangeSpecification.exchange(exchange)
                        .type("topic").durable(true))
                .then(sender.declare(ExchangeSpecification.exchange(dlx).type("fanout").durable(true)))
                .then(sender.declare(QueueSpecification.queue(dlq).durable(true)))
                .then(sender.bind(BindingSpecification.binding().exchange(dlx).queue(dlq)
                        .routingKey("")))
                .then();

        // 2) exchange consistente-hash de particiones (requiere el exchange type del broker)
        Mono<Void> hashExchange = sender.declare(ExchangeSpecification.exchange(p.exchange())
                .type("x-consistent-hash").durable(true)).then();

        // 3) binding exchange-to-exchange: la routing key del sensor llega intacta al hash
        Mono<Void> e2e = sender.bindExchange(BindingSpecification.binding()
                .exchangeFrom(exchange).exchangeTo(p.exchange()).routingKey("lectura.#")).then();

        // 4) N colas de partición (todas, siempre) con su DLX, bindeadas con peso "1"
        Mono<Void> colas = Flux.fromIterable(p.colas())
                .concatMap(cola -> sender.declare(QueueSpecification.queue(cola).durable(true)
                                .arguments(java.util.Map.of("x-dead-letter-exchange", dlx)))
                        .then(sender.bind(BindingSpecification.binding()
                                .exchange(p.exchange()).queue(cola).routingKey("1")))
                        .then())
                .then();

        return base.then(hashExchange).then(e2e).then(colas);
    }

    // ============ consumo por partición (BR-001/BR-002/BR-005) ============

    private Flux<Void> consumir(ParticionesPlan p) {
        List<String> colas = p.colasAsignadas();
        if (colas.isEmpty()) {
            log.warn("[ingestion] ninguna particion asignada: no hay consumers");
            return Flux.empty();
        }
        // un consumer por partición, en paralelo entre particiones y secuencial dentro
        // de cada una (concatMap dentro de consumirParticion) — BR-002/BR-005.
        return Flux.fromIterable(colas).flatMap(this::consumirParticion);
    }

    private Flux<Void> consumirParticion(String cola) {
        return receiver.consumeManualAck(cola, new ConsumeOptions().qos(1))
                .concatMap(d -> procesar(d).then(Mono.<Void>fromRunnable(() -> ack(d))))
                .doOnError(err -> log.error("[ingestion] particion {} detenida: {}",
                        cola, err.getMessage()))
                // una partición caída no debe tumbar el resto: los mensajes no ackeados
                // quedan en su cola y se reentregan al reconectar (AF-03).
                .onErrorResume(err -> Mono.empty());
    }

    private Mono<Void> procesar(AcknowledgableDelivery d) {
        Mono<Void> trabajo = Mono.defer(() -> {
            // FIX-0006 BR-006: la versión del schema se valida ANTES de procesar; un rechazo
            // (versión inválida o no soportada sin tolerancia) viaja como RechazoLecturaException
            // y termina en la DLQ sin tocar el resto de la cola.
            LecturaEntrada lectura = parseLectura(d.getBody());
            registroEsquema.validar(lectura.esquemaVersion());
            return ingestor.procesar(lectura).then();
        });
        return trabajo
                .onErrorResume(RechazoLecturaException.class, e -> rechazar(d, e.motivo))
                .onErrorResume(e -> rechazar(d, RechazoLecturaException.INFRA_ERROR));
    }

    private Mono<Void> rechazar(AcknowledgableDelivery d, String motivo) {
        log.warn("[ingestion] lectura rechazada motivo={}: {}", motivo,
                new String(d.getBody(), StandardCharsets.UTF_8));
        AMQP.BasicProperties propsMsg = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .headers(java.util.Map.of("x-rechazo", motivo.getBytes(StandardCharsets.UTF_8)))
                .build();
        return sender.send(Mono.just(new OutboundMessage(props.lecturas().dlx(), "",
                propsMsg, d.getBody())));
    }

    private void ack(AcknowledgableDelivery d) {
        try {
            d.ack();
        } catch (Exception e) {
            log.warn("[ingestion] ack fallo: {}", e.getMessage());
        }
    }

    // ============ parseo payload (FIX-0006 BR-006: DTO + Jackson) ============
    //
    // Se reemplazaron las expresiones regulares: con regex, "soportar v2" no dependía del
    // schemaVersion sino de que los patrones siguieran matcheando (un simple `"valor" : 5.0` con
    // espacios se rechazaba). El mapper ignora propiedades desconocidas, que es lo que hace real
    // la tolerancia hacia adelante (BR-007).

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    public static LecturaEntrada parseLectura(byte[] body) {
        LecturaMensaje m;
        try {
            m = MAPPER.readValue(body, LecturaMensaje.class);
        } catch (Exception e) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload malformado: " + e.getMessage());
        }
        if (m == null) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID, "payload vacio");
        }
        if (m.sensorId() == null || m.sensorId().isBlank()) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload incompleto: falta sensorId");
        }
        if (m.timestamp() == null || m.timestamp().isBlank()) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload incompleto: falta timestamp");
        }
        if (m.valor() == null) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload incompleto: falta valor");
        }
        if (m.unidadMedida() == null || m.unidadMedida().isBlank()) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload incompleto: falta unidadMedida");
        }
        UUID id;
        Instant ts;
        try {
            id = UUID.fromString(m.sensorId().trim());
        } catch (IllegalArgumentException e) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "sensorId no es UUID: " + m.sensorId());
        }
        try {
            ts = Instant.parse(m.timestamp().trim());
        } catch (RuntimeException e) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "timestamp no es ISO-8601 con offset: " + m.timestamp());
        }
        String calidadEmisor = null;
        if (m.calidad() != null) {
            for (String aviso : m.calidad().advertencias()) {
                log.warn("[ingestion] campo informativo de calidad ignorado ({}) — la lectura se "
                        + "procesa igual", aviso);
            }
            calidadEmisor = m.calidad().estado();
        }
        // FIX-0006 BR-008: la secuencia es informativa acá; la detección de huecos vive en el ingestor
        Long secuencia = m.sequence() != null && m.sequence() >= 0 ? m.sequence() : null;
        return new LecturaEntrada(id, ts, m.valor(), m.unidadMedida().trim(), m.eventId(),
                calidadEmisor, m.schemaVersion(), secuencia);
    }
}
