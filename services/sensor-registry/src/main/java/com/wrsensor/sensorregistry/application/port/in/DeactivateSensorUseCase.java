package com.wrsensor.sensorregistry.application.port.in;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use case port (in): baja logica de sensor (Main Flow FEAT-0005). Semantica:
 * existe → 204 (si no esta INACTIVO persiste estado=INACTIVO; si ya lo esta,
 * no-op idempotente); inexistente → SensorNotFoundException (404).
 */
public interface DeactivateSensorUseCase {

    Mono<Void> deactivate(UUID id);
}
