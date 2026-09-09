package com.wrsensor.datasimulator.infrastructure.adapter.out.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.datasimulator.application.port.out.LecturaPublisher;
import com.wrsensor.datasimulator.domain.model.Lectura;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

import java.nio.charset.StandardCharsets;

/**
 * Adapter out (messaging): publica lecturas en `sensor.lecturas` (topic) con key
 * `lectura.{sensorId}` via **Reactor RabbitMQ** (BR-002/BR-007; nunca
 * RabbitTemplate). Payload JSON construido a mano (campos fijos, sin libs:
 * mismo criterio que CursorCodec/JwtAdapter) —
 * {@code {"sensorId":"<uuid>","timestamp":"<iso>","valor":<n>,"unidadMedida":"<ENUM>"}}.
 */
@Component
public class RabbitLecturaPublisher implements LecturaPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitLecturaPublisher.class);

    private final Sender sender;
    private final String exchange;

    public RabbitLecturaPublisher(Sender sender,
                                  @Value("${simulador.lecturas.exchange:sensor.lecturas}") String exchange) {
        this.sender = sender;
        this.exchange = exchange;
    }

    @Override
    public Mono<Void> publish(Lectura lectura) {
        String key = "lectura." + lectura.sensorId();
        byte[] body = json(lectura).getBytes(StandardCharsets.UTF_8);
        return sender.send(Mono.just(new OutboundMessage(exchange, key, new AMQP.BasicProperties.Builder()
                .contentType("application/json").build(), body)));
    }

    static String json(Lectura l) {
        return "{\"sensorId\":\"" + l.sensorId()
                + "\",\"timestamp\":\"" + l.timestamp()
                + "\",\"valor\":" + l.valor().toPlainString()
                + ",\"unidadMedida\":\"" + l.unidadMedida().name() + "\"}";
    }

    /** Publica el payload para consumo del test de formato. */
    public static String serializeJson(Lectura l) {
        return json(l);
    }

    /** Declara el exchange topic durable al arrancar (idempotente). */
    @jakarta.annotation.PostConstruct
    void declareExchange() {
        sender.declare(ExchangeSpecification.exchange(exchange)
                        .type("topic").durable(true))
                .doOnError(err -> log.warn("[simulador] no se pudo declarar exchange {}: {}", exchange, err.getMessage()))
                .subscribe();
    }
}
