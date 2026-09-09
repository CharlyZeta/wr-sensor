package com.wrsensor.sensorregistry.application.port.in;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use case port (in): detalle de un sensor por id (Main Flow FEAT-0003).
 * La aplicacion resuelve ausencia con {@code SensorNotFoundException} (404
 * SENSOR_NOT_FOUND); el adapter web solo parsea el path param y mapea.
 */
public interface GetSensorDetailUseCase {

    /** @return el sensor si existe; error {@code SensorNotFoundException} si no. */
    Mono<Sensor> getById(UUID id);
}
