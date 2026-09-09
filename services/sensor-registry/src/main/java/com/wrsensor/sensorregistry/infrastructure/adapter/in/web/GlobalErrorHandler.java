package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.domain.model.SensorException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.validation.FieldError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.RolGuard.InsufficientRoleException;

/**
 * Mapea las excepciones de dominio (SensorException) y de validacion
 *estructural (WebExchangeBindException) a respuestas HTTP con body
 * `{"code": "...", "message": "..."}`. Los codigos (`code`) son los que
 * los AC-XXX del Contract asertan.
 */
@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(SensorException.class)
    public ResponseEntity<Map<String, Object>> handleDomain(SensorException ex) {
        HttpStatus status = httpStatusFor(ex.code());
        return ResponseEntity.status(status).body(body(ex.code(), ex.getMessage()));
    }

    /** AF-04 / AC-006 (403) y AC-007 (401). */
    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(InsufficientRoleException ex) {
        HttpStatus status = "UNAUTHENTICATED".equals(ex.code)
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(body(ex.code, ex.getMessage()));
    }

    /** Validacion estructural (Bean Validation en el controller). */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleBind(WebExchangeBindException ex) {
        // Los AC-XXX esperan codigos especificos (p. ej. SENSOR_INVALID_FRECUENCIA);
        // la validacion estructural solo captura campos faltantes/tipo erroneo.
        // Si el campo invalid corresponde a uno de los enums/numeros, mapeamos al
        // codigo de dominio correspondiente para que el test AC-XXX lo vea.
        String code = deriveCodeFromBinding(ex);
        String msg = ex.getFieldErrors().stream()
                .map(FieldError::getField)
                .reduce((a, b) -> a + "," + b)
                .map(f -> "campos invalidos: " + f)
                .orElse("request invalido");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(code, msg));
    }

    private static String deriveCodeFromBinding(WebExchangeBindException ex) {
        // Mapeo de campo → codigo de error de dominio (cuando la validacion estructural
        // coincide con el campo que un BR gobierna). Default: SENSOR_INVALID_REQUEST.
        for (FieldError fe : ex.getFieldErrors()) {
            return switch (fe.getField()) {
                case "latitud", "longitud" -> "SENSOR_INVALID_COORDINATES";
                case "histeresis" -> "SENSOR_INVALID_HISTERESIS";
                case "frecuenciaReporteSegundos" -> "SENSOR_INVALID_FRECUENCIA";
                case "estado" -> "SENSOR_INVALID_ESTADO";
                case "unidadMedida" -> "SENSOR_INVALID_UNIDAD";
                case "tipo" -> "SENSOR_INVALID_TIPO";
                case "rangoNormal", "rangoWarning", "rangoCritical" -> "SENSOR_INVALID_RANGES";
                // ponytail: "codigo" deliberadamente NO mapea a SENSOR_CODE_DUPLICATED
                // (Resolucion Humana 2026-07-31); cae al default SENSOR_INVALID_REQUEST.
                case "codigo" -> "SENSOR_INVALID_REQUEST";
                default -> "SENSOR_INVALID_REQUEST";
            };
        }
        return "SENSOR_INVALID_REQUEST";
    }

    /** AF-06: limit no numerico (TypeMismatchException del @RequestParam Integer) → 400 SENSOR_INVALID_LIMIT. */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(TypeMismatchException ex) {
        // ponytail: si el @RequestParam "limit" no parsea a Integer, Spring lanza
        // MethodArgumentTypeMismatchException (subtipo de TypeMismatchException). Cualquier
        // otro tipo-mismatch cae al generico SENSOR_INVALID_REQUEST.
        String code = (ex instanceof MethodArgumentTypeMismatchException mtm && "limit".equals(mtm.getName()))
                ? "SENSOR_INVALID_LIMIT" : "SENSOR_INVALID_REQUEST";
        String msg = "SENSOR_INVALID_LIMIT".equals(code)
                ? "limit debe ser entero en [1,1000]" : "request invalido";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(code, msg));
    }

    /** AF-06 (FEAT-0004): body ilegible (JSON invalido o campo no editable con
     *  fail-on-unknown-properties) → 400 SENSOR_INVALID_REQUEST. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("SENSOR_INVALID_REQUEST", "request invalido"));
    }

    /** WebFlux lanza ServerWebInputException al decodificar bodies invalidos (unknown property). */
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> handleInput(org.springframework.web.server.ServerWebInputException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("SENSOR_INVALID_REQUEST", "request invalido"));
    }

    private static Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message == null ? "" : message);
    }

    /** Mapeo codigo de dominio → HTTP status. */
    private static HttpStatus httpStatusFor(String code) {
        return switch (code) {
            case "SENSOR_CODE_DUPLICATED" -> HttpStatus.CONFLICT;
            case "SENSOR_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_CREDENTIALS" -> HttpStatus.UNAUTHORIZED;
            case "SENSOR_INVALID_RANGES",
                 "SENSOR_INVALID_COORDINATES",
                 "SENSOR_INVALID_HISTERESIS",
                 "SENSOR_INVALID_FRECUENCIA",
                 "SENSOR_INVALID_ESTADO",
                 "SENSOR_INVALID_UNIDAD",
                 "SENSOR_INVALID_TIPO",
                 "SENSOR_INVALID_ID",
                 "SENSOR_INVALID_LIMIT",
                 "SENSOR_INVALID_CURSOR" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
