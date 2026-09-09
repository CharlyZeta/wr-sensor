package com.wrsensor.sensorregistry.domain.model;

/** BR-001: codigo ya existe → 409 SENSOR_CODE_DUPLICATED (AC-002). */
public class SensorCodeDuplicatedException extends SensorException {

    private final String codigo;

    public SensorCodeDuplicatedException(String codigo) {
        super("SENSOR_CODE_DUPLICATED", "Ya existe un sensor con codigo: " + codigo);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
