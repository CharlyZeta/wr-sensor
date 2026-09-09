package com.wrsensor.sensorregistry.domain.model;

/** BR-008: unidadMedida no ∈ {METROS, CENTIMETROS} → 400 SENSOR_INVALID_UNIDAD (AC-011). */
public class InvalidUnidadException extends SensorException {
    public InvalidUnidadException() {
        super("SENSOR_INVALID_UNIDAD", "unidadMedida ∈ {METROS, CENTIMETROS}");
    }
}
