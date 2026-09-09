package com.wrsensor.sensorregistry.domain.model;

/** FEAT-0003 BR-001: path {id} no parseable como UUID → 400 SENSOR_INVALID_ID
 *  (AF-04, AC-006). Decision humana 2026-09-09 (HO-Gate FEAT-0003). */
public class InvalidSensorIdException extends SensorException {

    public InvalidSensorIdException() {
        super("SENSOR_INVALID_ID", "id debe ser un UUID valido");
    }
}
