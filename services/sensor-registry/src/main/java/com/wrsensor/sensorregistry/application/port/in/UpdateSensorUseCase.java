package com.wrsensor.sensorregistry.application.port.in;

import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Use case port (in): edicion de configuracion de un sensor (Main Flow FEAT-0004).
 * El command lleva el subset config editable COMPLETO (BR-005): estado
 * (string, parseo en el servicio), histeresis, frecuencia y los 3 rangos.
 * Identidad inmutable (BR-004); ausencia → SensorNotFoundException (BR-002).
 */
public interface UpdateSensorUseCase {

    record UpdateSensorCommand(
            String estado,
            BigDecimal histeresis,
            int frecuenciaReporteSegundos,
            Rango rangoNormal,
            Rango rangoWarning,
            Rango rangoCritical
    ) {}

    /** @return el sensor persistido y actualizado. */
    Mono<Sensor> update(UUID id, UpdateSensorCommand command);
}
