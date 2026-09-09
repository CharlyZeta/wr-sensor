package com.wrsensor.queryapi.infrastructure.adapter.in.web;

import com.wrsensor.queryapi.domain.QueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Mapea QueryException → HTTP (codes FEAT-0013). */
@RestControllerAdvice
public class QueryErrorHandler {

    @ExceptionHandler(QueryException.class)
    public ResponseEntity<Map<String, Object>> handle(QueryException ex) {
        HttpStatus status = switch (ex.code) {
            case QueryException.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case QueryException.INSUFFICIENT_ROLE -> HttpStatus.FORBIDDEN;
            case QueryException.SENSOR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST; // SENSOR_INVALID_*, INVALID_RANGE
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.code, "message", ex.getMessage()));
    }
}
