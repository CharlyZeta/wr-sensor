package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Port out: lectura de un sensor por id (FEAT-0003 BR-002). Devuelve
 * {@code Mono.empty()} si no existe (la capa de aplicacion decide el 404).
 */
public interface FindSensorByIdPort {

    Mono<Sensor> findById(UUID id);
}
