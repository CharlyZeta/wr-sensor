package com.wrsensor.sensorregistry.domain.model;

/**
 * Superclase de los errores de dominio de Sensor. Cada subclase expone un
 * codigo estable (no el HTTP status) — el adapter web lo mapea a status.
 * Codigos alineados a los de los AC-XXX del Contract FEAT-0001.
 */
public abstract class SensorException extends RuntimeException {

    private final String code;

    protected SensorException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
