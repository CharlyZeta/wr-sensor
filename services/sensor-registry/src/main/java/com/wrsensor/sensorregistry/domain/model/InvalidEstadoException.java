package com.wrsensor.sensorregistry.domain.model;

/** BR-007: estado no ∈ {ACTIVO, INACTIVO, MANTENIMIENTO} → 400 SENSOR_INVALID_ESTADO (AC-010). */
public class InvalidEstadoException extends SensorException {
    public InvalidEstadoException() {
        super("SENSOR_INVALID_ESTADO", "estado ∈ {ACTIVO, INACTIVO, MANTENIMIENTO}");
    }
}
