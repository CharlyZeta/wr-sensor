package com.wrsensor.sensorregistry.infrastructure.adapter.out.messaging;

import com.wrsensor.sensorregistry.application.port.out.PublishSensorCreatedPort;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adapter out (messaging) que publica "sensor creado" a RabbitMQ.
 *
 * <p>Stack: Spring AMQP (`RabbitTemplate`) para publisher builds. (Spring no
 * ofrece un starter `reactor-rabbitmq` autoconfigurado en 4.x que reemplace
 * limpiamente el template reactivo; para publishers puntuales sin consumo,
 * RabbitTemplate en un scheduler elastico es aceptable. Para consumers
 * reactivos se usara Reactor RabbitMQ en otro adapter. Esto no mezcla bloqueante
 * en el flujo reactivo del use case: el template se corre en bounded-elastic
 * via `Mono.fromCallable(...).subscribeOn`; el caller nunca ve un `.block`.)
 *
 * <p>AC-001 aserta "se publica un evento de creacion de sensor a RabbitMQ" pero
 * el Ambiguity Log del Contract marca el exchange/routing-key/payload como no
 * definidos. El adapter publica como mejor-efforno intento: define defaults
 * (`sensor.created` topic, key `sensor.created.{id}`) en application.yml, y
 * el test de integracion solo verifica que al menos un mensaje llegue al bus
 * bajo ese convenio — si la decision cambia en Gate, solo se cambia este
 * adapter y la property, sin tocar el dominio.
 */
@Component
public class SensorCreatedPublisherAdapter implements PublishSensorCreatedPort {

    private final RabbitTemplate rabbitTemplate;
    private final Exchange exchange;
    private final String routingKeyPrefix;

    public SensorCreatedPublisherAdapter(RabbitTemplate rabbitTemplate,
                                         Exchange sensorCreatedExchange,
                                         @Value("${messaging.sensor-created.routing-key-prefix:sensor.created.}") String routingKeyPrefix) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = sensorCreatedExchange;
        this.routingKeyPrefix = routingKeyPrefix;
    }

    @Override
    public Mono<Void> publish(Sensor sensor) {
        return Mono.fromCallable(() -> {
                    String routingKey = routingKeyPrefix + sensor.id();
                    String payload = "{\"sensorId\":\"" + sensor.id() + "\",\"codigo\":\"" + sensor.codigo() + "\"}";
                    rabbitTemplate.convertAndSend(exchange.getName(), routingKey, payload);
                    return (Void) null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Configuration
    static class ExchangeConfig {
        @Bean
        Exchange sensorCreatedExchange(@Value("${messaging.sensor-created.exchange:sensor.created}") String exchangeName) {
            return ExchangeBuilder.topicExchange(exchangeName).durable(true).build();
        }
    }
}
