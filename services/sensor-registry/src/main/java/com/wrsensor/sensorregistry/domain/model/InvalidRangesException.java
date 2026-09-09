package com.wrsensor.sensorregistry.domain.model;

/**
 * BR-002: anidamiento inclusivo roto
 * (rangoCritical.min ≤ rangoWarning.min ≤ rangoNormal.min ≤ rangoNormal.max
 *  ≤ rangoWarning.max ≤ rangoCritical.max) → 400 SENSOR_INVALID_RANGES (AC-003).
 */
public class InvalidRangesException extends SensorException {

    public InvalidRangesException(String detail) {
        super("SENSOR_INVALID_RANGES", detail);
    }
}
