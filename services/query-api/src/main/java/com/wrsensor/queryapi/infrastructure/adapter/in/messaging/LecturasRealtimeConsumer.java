package com.wrsensor.queryapi.infrastructure.adapter.in.messaging;

import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.infrastructure.realtime.LecturaRealtimeBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
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
 * Consumer de `sensor.lecturas` (cola propia de query-api) para el WS tiempo real
 * (FEAT-0013 AC-007). Solo broadcast: no persiste. Malformados → log + ack.
 */
@Component
public class LecturasRealtimeConsumer {

    private static final Logger log = LoggerFactory.getLogger(LecturasRealtimeConsumer.class);

    private final Sender sender;
    private final Receiver receiver;
    private final LecturaRealtimeBus bus;
    private final String exchange;
    private final String queue;

    public LecturasRealtimeConsumer(Sender sender, Receiver receiver, LecturaRealtimeBus bus,
                                    @Value("${query.lecturas.exchange:sensor.lecturas}") String exchange,
                                    @Value("${query.lecturas.queue:query.sensor.lecturas}") String queue) {
        this.sender = sender;
        this.receiver = receiver;
        this.bus = bus;
        this.exchange = exchange;
        this.queue = queue;
    }

    @jakarta.annotation.PostConstruct
    void iniciar() {
        sender.declare(ExchangeSpecification.exchange(exchange).type("topic").durable(true))
                .then(sender.declare(QueueSpecification.queue(queue).durable(true)))
                .then(sender.bind(BindingSpecification.binding().exchange(exchange).queue(queue).routingKey("lectura.#")))
                .then(Mono.defer(this::consumir))
                .doOnError(err -> log.error("[query] setup rabbit fallo: {}", err.getMessage()))
                .subscribe();
    }

    private Mono<Void> consumir() {
        Flux<AcknowledgableDelivery> deliveries = receiver.consumeManualAck(queue, new ConsumeOptions().qos(50));
        deliveries
                .flatMap(d -> Mono.fromRunnable(() -> procesar(d)).thenReturn(d))
                .flatMap(d -> Mono.fromRunnable(() -> ack(d)))
                .doOnError(err -> log.error("[query] error consumo realtime: {}", err.getMessage()))
                .subscribe();
        return Mono.empty();
    }

    private void procesar(AcknowledgableDelivery d) {
        try {
            LecturaConsulta l = parseLectura(d.getBody());
            bus.publicar(l);
        } catch (RuntimeException e) {
            log.warn("[query] lectura realtime descartada: {}", e.getMessage());
        }
    }

    private void ack(AcknowledgableDelivery d) {
        try {
            d.ack();
        } catch (Exception e) {
            log.warn("[query] ack fallo: {}", e.getMessage());
        }
    }

    private static final Pattern P_SENSOR = Pattern.compile("\"sensorId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern P_TS = Pattern.compile("\"timestamp\":\"([^\"]+)\"");
    private static final Pattern P_VALOR = Pattern.compile("\"valor\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern P_UNIDAD = Pattern.compile("\"unidadMedida\":\"([A-Z_]+)\"");

    static LecturaConsulta parseLectura(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        Matcher s = P_SENSOR.matcher(json);
        Matcher t = P_TS.matcher(json);
        Matcher v = P_VALOR.matcher(json);
        Matcher u = P_UNIDAD.matcher(json);
        if (!s.find() || !t.find() || !v.find() || !u.find()) {
            throw new IllegalArgumentException("payload incompleto");
        }
        return new LecturaConsulta(UUID.fromString(s.group(1)), Instant.parse(t.group(1)),
                new BigDecimal(v.group(1)), u.group(1), null);
    }
}
