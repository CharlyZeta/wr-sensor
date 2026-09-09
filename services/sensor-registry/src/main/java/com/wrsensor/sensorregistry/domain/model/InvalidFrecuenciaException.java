package com.wrsensor.sensorregistry.domain.model;

/** BR-006: frecuenciaReporteSegundos <= 0 → 400 SENSOR_INVALID_FRECUENCIA (AC-009). */
public class InvalidFrecuenciaException extends SensorException {

    public InvalidFrecuenciaException() {
        super("SENSOR_INVALID_FRECUENCIA", "frecuenciaReporteSegundos debe ser > 0");
    }
}
