package com.wrsensor.ingestion.domain;

/** Rechazo de una lectura con motivo estable para DLQ (AF-01..05, AC-004..008). */
public class RechazoLecturaException extends RuntimeException {

    public static final String PAYLOAD_INVALID = "PAYLOAD_INVALID";
    public static final String SENSOR_UNKNOWN = "SENSOR_UNKNOWN";
    public static final String SENSOR_INACTIVE = "SENSOR_INACTIVE";
    public static final String TIMESTAMP_OUT_OF_WINDOW = "TIMESTAMP_OUT_OF_WINDOW";
    public static final String INFRA_ERROR = "INFRA_ERROR";
    /** FIX-0006 BR-011: sólo con `ingestion.schema.tolerar-versiones-mayores: false`. */
    public static final String SCHEMA_UNSUPPORTED = "SCHEMA_UNSUPPORTED";
    /** FIX-0007 BR-005: el registry no responde y no hay config cacheada del sensor. */
    public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";

    public final String motivo;

    public RechazoLecturaException(String motivo, String message) {
        super(message);
        this.motivo = motivo;
    }
}
