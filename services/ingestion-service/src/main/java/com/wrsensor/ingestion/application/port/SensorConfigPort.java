package com.wrsensor.ingestion.application.port;

import com.wrsensor.ingestion.domain.SensorInfo;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Port out: config del sensor (fuente: sensor-registry REST, decision HO-Gate).
 * {@code Mono.empty()} si el sensor no existe (404) → SENSOR_UNKNOWN (AF-02).
 */
public interface SensorConfigPort {

    Mono<SensorInfo> findById(UUID sensorId);
}
