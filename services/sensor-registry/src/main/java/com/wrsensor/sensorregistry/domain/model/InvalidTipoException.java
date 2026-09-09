package com.wrsensor.sensorregistry.domain.model;

/** BR-009: tipo no ∈ {RIO, ARROYO, BAÑADO} → 400 SENSOR_INVALID_TIPO (AC-012). */
public class InvalidTipoException extends SensorException {
    public InvalidTipoException() {
        super("SENSOR_INVALID_TIPO", "tipo ∈ {RIO, ARROYO, BAÑADO}");
    }
}
