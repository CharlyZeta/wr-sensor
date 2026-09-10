package com.wrsensor.alerting.infrastructure.adapter.in.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.alerting.application.service.GestorAlertas;
import com.wrsensor.alerting.domain.EventoAlerta;
import com.wrsensor.alerting.domain.Severidad;
import com.wrsensor.alerting.infrastructure.config.AlertingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumer de `sensor.alertas` (Main Flow FEAT-0012). Malformados → DLQ con header
 * `x-rechazo` (AF-03/AC-008). Fifo por cola; el gestor serializa por sensor.
 */
@Component
public class AlertasRabbitConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertasRabbitConsumer.class);

    private final Sender sender;
    private final Receiver receiver;
    private final GestorAlertas gestor;
    private final AlertingProperties props;

    public AlertasRabbitConsumer(Sender sender, Receiver receiver, GestorAlertas gestor,
                                 AlertingProperties props) {
        this.sender = sender;
        this.receiver = receiver;
        this.gestor = gestor;
        this.props = props;
    }

    @jakarta.annotation.PostConstruct
    void iniciar() {
        String exchange = props.alertas().exchange();
        String queue = props.alertas().queue();
        String dlx = props.alertas().dlx();
        String dlq = props.alertas().dlqQueue();

        sender.declare(ExchangeSpecification.exchange(exchange).type("topic").durable(true))
                .then(sender.declare(ExchangeSpecification.exchange(dlx).type("fanout").durable(true)))
                .then(sender.declare(QueueSpecification.queue(queue).durable(true)
                        .arguments(java.util.Map.of("x-dead-letter-exchange", dlx))))
                .then(sender.declare(QueueSpecification.queue(dlq).durable(true)))
                .then(sender.bind(BindingSpecification.binding().exchange(exchange).queue(queue).routingKey("alerta.#")))
                .then(sender.bind(BindingSpecification.binding().exchange(dlx).queue(dlq).routingKey("")))
                .then(Mono.defer(this::consumir))
                .doOnError(err -> log.error("[alerting] setup rabbit fallo: {}", err.getMessage()))
                .subscribe();
    }

    private Mono<Void> consumir() {
        Flux<AcknowledgableDelivery> deliveries = receiver.consumeManualAck(props.alertas().queue(),
                new ConsumeOptions().qos(1));
        deliveries
                .flatMap(d -> procesar(d).thenReturn(d))
                .flatMap(d -> Mono.fromRunnable(() -> ack(d)))
                .doOnError(err -> log.error("[alerting] error consumo: {}", err.getMessage()))
                .subscribe();
        return Mono.empty();
    }

    private Mono<Void> procesar(AcknowledgableDelivery d) {
        Mono<Void> trabajo = Mono.defer(() -> {
            gestor.procesar(parseEvento(d.getBody()));
            return Mono.empty();
        });
        return trabajo
                .onErrorResume(e -> rechazar(d, e.getMessage()));
    }

    private Mono<Void> rechazar(AcknowledgableDelivery d, String motivo) {
        log.warn("[alerting] evento rechazado: {}", motivo);
        AMQP.BasicProperties p = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .headers(java.util.Map.of("x-rechazo", "PAYLOAD_INVALID".getBytes(StandardCharsets.UTF_8)))
                .build();
        return sender.send(Mono.just(new OutboundMessage(props.alertas().dlx(), "", p, d.getBody())));
    }

    private void ack(AcknowledgableDelivery d) {
        try {
            d.ack();
        } catch (Exception e) {
            log.warn("[alerting] ack fallo: {}", e.getMessage());
        }
    }

    // parse payload FEAT-0011: sensorId,timestamp,valorLectura,severidadAnterior,severidadNueva,cruceHisteresis
    private static final Pattern P_SENSOR = Pattern.compile("\"sensorId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern P_TS = Pattern.compile("\"timestamp\":\"([^\"]+)\"");
    private static final Pattern P_VALOR = Pattern.compile("\"valorLectura\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_ANTERIOR = Pattern.compile("\"severidadAnterior\":\"([A-Z_]+)\"");
    private static final Pattern P_NUEVA = Pattern.compile("\"severidadNueva\":\"([A-Z_]+)\"");
    private static final Pattern P_CRUCE = Pattern.compile("\"cruceHisteresis\":(true|false)");

    public static EventoAlerta parseEvento(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        Matcher s = P_SENSOR.matcher(json);
        Matcher t = P_TS.matcher(json);
        Matcher v = P_VALOR.matcher(json);
        Matcher a = P_ANTERIOR.matcher(json);
        Matcher n = P_NUEVA.matcher(json);
        if (!s.find() || !t.find() || !v.find() || !a.find() || !n.find()) {
            throw new IllegalArgumentException("payload incompleto");
        }
        UUID id = UUID.fromString(s.group(1));
        Instant ts = Instant.parse(t.group(1));
        BigDecimal valor = new BigDecimal(v.group(1)); // FIX-0002: valor real del payload (no BigDecimal.ONE)
        Severidad anterior = Severidad.valueOf(a.group(1));
        Severidad nueva = Severidad.valueOf(n.group(1));
        Matcher c = P_CRUCE.matcher(json);
        boolean cruceHisteresis = c.find() && Boolean.parseBoolean(c.group(1)); // FIX-0002: fiel; ausente → false (compatibilidad)
        return new EventoAlerta(id, ts, valor, anterior, nueva, cruceHisteresis);
    }
}
