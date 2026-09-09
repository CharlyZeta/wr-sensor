package com.wrsensor.sensorregistry.domain.model;

/** FEAT-0003 BR-002: sensor inexistente para un id UUID valido → 404 SENSOR_NOT_FOUND
 *  (AF-03, AC-005). Nunca 500 ni 200 con body vacio. */
public class SensorNotFoundException extends SensorException {

    public SensorNotFoundException() {
        super("SENSOR_NOT_FOUND", "sensor no encontrado");
    }
}
