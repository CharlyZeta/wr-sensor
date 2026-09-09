package com.wrsensor.sensorregistry.domain.model;

/** FEAT-0002 BR-002: limit fuera de [1,1000] (o no numerico) → 400 SENSOR_INVALID_LIMIT
 *  (AF-03 default 100; AF-04/05/06 fuera de rango / no numerico; AC-006 limit>1000; AC-007 limit<1). */
public class InvalidListLimitException extends SensorException {

    public InvalidListLimitException() {
        super("SENSOR_INVALID_LIMIT", "limit debe ser entero en [1,1000]");
    }
}
