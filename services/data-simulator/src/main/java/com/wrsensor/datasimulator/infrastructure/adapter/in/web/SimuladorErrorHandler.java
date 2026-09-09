package com.wrsensor.datasimulator.infrastructure.adapter.in.web;

import com.wrsensor.datasimulator.domain.model.SimuladorException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Mapea SimuladorException → HTTP (codes FEAT-0010). */
@RestControllerAdvice
public class SimuladorErrorHandler {

    @ExceptionHandler(SimuladorException.class)
    public ResponseEntity<Map<String, Object>> handle(SimuladorException ex) {
        HttpStatus status = switch (ex.code) {
            case SimuladorException.ALREADY_RUNNING, SimuladorException.NOT_RUNNING -> HttpStatus.CONFLICT;
            case SimuladorException.SENSOR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Map.of("code", ex.code, "message", ex.getMessage()));
    }
}
