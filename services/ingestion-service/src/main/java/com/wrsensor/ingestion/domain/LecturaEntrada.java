package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura recibida de `sensor.lecturas` (payload FEAT-0010 BR-002).
 * Entrada pura del pipeline de ingestion.
 */
public record LecturaEntrada(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        String unidadMedida
) {
}
