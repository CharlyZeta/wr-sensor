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
        String severidad
) {
}
