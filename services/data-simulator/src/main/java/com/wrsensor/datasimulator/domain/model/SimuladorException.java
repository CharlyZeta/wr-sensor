package com.wrsensor.datasimulator.domain.model;

/** Error de dominio del simulador; el GlobalErrorHandler mapea code → HTTP. */
public class SimuladorException extends RuntimeException {

    public static final String ALREADY_RUNNING = "SIMULATOR_ALREADY_RUNNING";
    public static final String NOT_RUNNING = "SIMULATOR_NOT_RUNNING";
    public static final String SENSOR_NOT_FOUND = "SENSOR_NOT_FOUND";

    public final String code;

    public SimuladorException(String code, String message) {
        super(message);
        this.code = code;
    }
}
