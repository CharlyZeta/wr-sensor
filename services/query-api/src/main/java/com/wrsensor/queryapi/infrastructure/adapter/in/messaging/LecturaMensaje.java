package com.wrsensor.queryapi.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;

/**
 * DTO del payload de `sensor.lecturas` (FIX-0006 BR-006) deserializado con Jackson ignorando
 * propiedades desconocidas: un campo nuevo de una versión futura no rompe el consumo tiempo real.
 */
public record LecturaMensaje(
        String schemaVersion,
        String eventId,
        String sensorId,
        String timestamp,
        BigDecimal valor,
        String unidadMedida,
        Long sequence,
        CalidadMensaje calidad
) {

    public record CalidadMensaje(String estado, Object confianza, Object codigosAnomalias) {}
}
