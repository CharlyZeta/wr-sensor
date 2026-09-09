package com.wrsensor.sensorregistry.domain.model;

/** BR-005: histeresis < 0 → 400 SENSOR_INVALID_HISTERESIS (AC-008). */
public class InvalidHisteresisException extends SensorException {

    public InvalidHisteresisException() {
        super("SENSOR_INVALID_HISTERESIS", "histeresis debe ser >= 0");
    }
}
