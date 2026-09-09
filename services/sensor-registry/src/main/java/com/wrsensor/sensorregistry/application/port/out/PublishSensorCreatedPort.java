package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

/**
 * Puerto de salida (out): publicar el evento "sensor creado" al bus de
 * mensajeria. El Contract no define exchange/routing-key/payload del evento
 * (Ambiguity Log abierto); este puerto abstrae ese detalle para que el adapter
 * de infra (RabbitMQ) lo resuelva. Si la publicacion no es posible en v1 tests,
 * el adapter puede ser un no-op, pero el puerto existe para que el dominio
 * permanezca agnostico.
 */
public interface PublishSensorCreatedPort {

    /** Publica el evento y completa el Mono al confirmar. */
    Mono<Void> publish(Sensor sensor);
}
