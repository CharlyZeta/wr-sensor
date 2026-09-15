package com.wrsensor.queryapi.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Metadata de un sensor tal como la publica {@code sensor-registry} en {@code GET /api/sensores}
 * (FEAT-0008 BR-005): el subconjunto que necesita el mapa. Es una proyección de sólo lectura, sin
 * rangos ni histéresis, para no acoplar query-api a la config completa del registry.
 */
public record SensorMetadata(UUID id, String codigo, String nombre, String tipo, BigDecimal latitud,
                             BigDecimal longitud, String estado, String unidadMedida) {
}
