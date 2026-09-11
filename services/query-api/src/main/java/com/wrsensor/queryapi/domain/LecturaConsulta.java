package com.wrsensor.queryapi.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lectura consultable (histórico/última/tiempo real). */
public record LecturaConsulta(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        String unidadMedida,
        String severidad,   // FIX-0004: puede ser null (lectura ERROR_SENSOR no evaluada)
        String calidad      // FIX-0004: OK | ERROR_SENSOR
) {
}

