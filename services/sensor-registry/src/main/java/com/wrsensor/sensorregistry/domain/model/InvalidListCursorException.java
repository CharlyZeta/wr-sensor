package com.wrsensor.sensorregistry.domain.model;

/** FEAT-0002 BR-003: cursor opaco malformado → 400 SENSOR_INVALID_CURSOR (AF-07, AC-010). */
public class InvalidListCursorException extends SensorException {

    public InvalidListCursorException() {
        super("SENSOR_INVALID_CURSOR", "cursor malformado");
    }
}
