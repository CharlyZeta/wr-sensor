package com.wrsensor.ingestion.infrastructure.adapter.in.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter in (messaging): consume `sensor.lecturas` (Main Flow FEAT-0011).
 * Ack manual tras persistir (BR-005); mensajes rechazados van a la DLQ con header
 * `x-rechazo` (AF-01..05); DLX/DLQ y retry declarados/configurables (BR-005/006).
 */
@Component
public class LecturasRabbitConsumer {

    private static final Logger log = LoggerFactory.getLogger(LecturasRabbitConsumer.class);

    private final Sender sender;
    private final Receiver receiver;
    private final IngestorLecturas ingestor;
    private final IngestionProperties props;

    public LecturasRabbitConsumer(Sender sender, Receiver receiver,
                                  IngestorLecturas ingestor, IngestionProperties props) {
        this.sender = sender;
        this.receiver = receiver;
        this.ingestor = ingestor;
        this.props = props;
    }

    @jakarta.annotation.PostConstruct
    void iniciar() {
        String exchange = props.lecturas().exchange();
        String queue = props.lecturas().queue();
        String dlx = props.lecturas().dlx();
        String dlq = props.lecturas().dlqQueue();

        sender.declare(ExchangeSpecification.exchange(exchange).type("topic").durable(true))
                .then(sender.declare(ExchangeSpecification.exchange(dlx).type("fanout").durable(true)))
                .then(sender.declare(QueueSpecification.queue(queue).durable(true)
                        .arguments(java.util.Map.of("x-dead-letter-exchange", dlx))))
                .then(sender.declare(QueueSpecification.queue(dlq).durable(true)))
                .then(sender.bind(BindingSpecification.binding().exchange(exchange).queue(queue).routingKey("lectura.#")))
                .then(sender.bind(BindingSpecification.binding().exchange(dlx).queue(dlq).routingKey("")))
                .then(Mono.defer(this::consumir))
                .doOnError(err -> log.error("[ingestion] setup rabbit fallo: {}", err.getMessage()))
                .subscribe();
    }

    private Mono<Void> consumir() {
        Flux<AcknowledgableDelivery> deliveries = receiver.consumeManualAck(props.lecturas().queue(),
                new ConsumeOptions().qos(1));
        deliveries
                .flatMap(d -> procesar(d).thenReturn(d))
                .flatMap(d -> Mono.fromRunnable(() -> ack(d)))
                .doOnError(err -> log.error("[ingestion] error en loop de consumo: {}", err.getMessage()))
                .subscribe();
        return Mono.empty();
    }

    private Mono<Void> procesar(AcknowledgableDelivery d) {
        Mono<Void> trabajo = Mono.defer(() -> ingestor.procesar(parseLectura(d.getBody())).then());
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

    // ============ parseo payload (formato FEAT-0010 BR-002) ============

    private static final Pattern P_SENSOR = Pattern.compile("\"sensorId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern P_TS = Pattern.compile("\"timestamp\":\"([^\"]+)\"");
    private static final Pattern P_VALOR = Pattern.compile("\"valor\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_UNIDAD = Pattern.compile("\"unidadMedida\":\"([A-Z_]+)\"");
    private static final Pattern P_EVENT = Pattern.compile("\"eventId\":\"([^\"]+)\""); // FIX-0003 BR-001/BR-010 (opcional)

    public static LecturaEntrada parseLectura(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        try {
            Matcher s = P_SENSOR.matcher(json);
            Matcher t = P_TS.matcher(json);
            Matcher v = P_VALOR.matcher(json);
            Matcher u = P_UNIDAD.matcher(json);
            if (!s.find() || !t.find() || !v.find() || !u.find()) {
                throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID, "payload incompleto");
            }
            UUID id = UUID.fromString(s.group(1));
            Instant ts = Instant.parse(t.group(1));
            BigDecimal valor = new BigDecimal(v.group(1));
            Matcher ev = P_EVENT.matcher(json);
            String eventId = ev.find() ? ev.group(1) : null; // FIX-0003 BR-001/BR-010 (opcional)
            return new LecturaEntrada(id, ts, valor, u.group(1), eventId);
        } catch (RechazoLecturaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RechazoLecturaException(RechazoLecturaException.PAYLOAD_INVALID,
                    "payload malformado: " + e.getMessage());
        }
    }
}


