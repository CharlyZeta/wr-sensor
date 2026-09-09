package com.wrsensor.sensorregistry.domain.model;

/** BR-003 (latitud ∈ [-90,90]) o BR-004 (longitud ∈ [-180,180]) → 400 SENSOR_INVALID_COORDINATES (AC-004/005). */
public class InvalidCoordinatesException extends SensorException {

    public InvalidCoordinatesException(String detail) {
        super("SENSOR_INVALID_COORDINATES", detail);
    }
}
