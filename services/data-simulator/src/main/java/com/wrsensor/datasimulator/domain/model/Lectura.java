package com.wrsensor.datasimulator.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura sintetica publicada por data-simulator.
 *
 * <p>FEAT-0010 BR-002 (contrato base) + FIX-0006: el evento lleva ahora
 * {@code eventId} (trazabilidad e idempotencia explícita), {@code sequence}
 * (contador creciente por sensor) y {@code calidad} informativa. El `schemaVersion` no vive acá
 * porque es configuración del publisher, no del dato.</p>
 */
public record Lectura(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        UnidadMedida unidadMedida,
        UUID eventId,
        long sequence,
        CalidadEmisor calidad
) {
}
