package com.wrsensor.queryapi.domain;

/** Error de dominio de query-api (codes estables). */
public class QueryException extends RuntimeException {

    public static final String SENSOR_INVALID_LIMIT = "SENSOR_INVALID_LIMIT";
    public static final String SENSOR_INVALID_CURSOR = "SENSOR_INVALID_CURSOR";
    public static final String SENSOR_INVALID_ID = "SENSOR_INVALID_ID";
    public static final String INVALID_RANGE = "INVALID_RANGE";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";
    public static final String SENSOR_NOT_FOUND = "SENSOR_NOT_FOUND";

    public final String code;

    public QueryException(String code, String message) {
        super(message);
        this.code = code;
    }
}
