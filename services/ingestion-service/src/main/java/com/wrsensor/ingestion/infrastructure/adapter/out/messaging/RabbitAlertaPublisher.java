package com.wrsensor.ingestion.infrastructure.adapter.out.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.ingestion.application.port.AlertaEventoPublisher;
import com.wrsensor.ingestion.domain.AlertaEvento;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

import java.nio.charset.StandardCharsets;

/**
 * Adapter out (messaging): publica AlertaEvento en `sensor.alertas` (topic, key
 * `alerta.{severidadNueva}`) via Reactor RabbitMQ (BR-004). JSON manual (sin libs).
 */
@Component
public class RabbitAlertaPublisher implements AlertaEventoPublisher {

    private final Sender sender;
    private final String exchange;

    public RabbitAlertaPublisher(Sender sender,
                                 @Value("${ingestion.alertas.exchange:sensor.alertas}") String exchange) {
        this.sender = sender;
        this.exchange = exchange;
    }

    @Override
    public Mono<Void> publish(AlertaEvento e) {
        String key = "alerta." + e.severidadNueva().name().toLowerCase();
        String body = "{\"sensorId\":\"" + e.sensorId()
                + "\",\"timestamp\":\"" + e.timestamp()
                + "\",\"valorLectura\":" + e.valorLectura().toPlainString()
                + ",\"severidadAnterior\":\"" + e.severidadAnterior()
                + "\",\"severidadNueva\":\"" + e.severidadNueva()
                + "\",\"cruceHisteresis\":" + e.cruceHisteresis() + "}";
        return sender.send(Mono.just(new OutboundMessage(exchange, key,
                new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                body.getBytes(StandardCharsets.UTF_8))));
    }
}
