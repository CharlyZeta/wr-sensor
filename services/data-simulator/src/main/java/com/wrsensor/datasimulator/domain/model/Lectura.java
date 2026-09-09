package com.wrsensor.datasimulator.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura sintetica publicada por data-simulator (FEAT-0010 BR-002): contrato de
 * salida {@code {sensorId, timestamp, valor, unidadMedida}} hacia
 * `sensor.lecturas` (la severidad la calcula ingestion en FEAT-0011).
 */
public record Lectura(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        UnidadMedida unidadMedida
) {
}
