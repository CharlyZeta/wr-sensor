package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura recibida de `sensor.lecturas`.
 *
 * <p>FIX-0006: {@code esquemaVersion} (tal como vino, puede ser {@code null} = legado) y
 * {@code secuencia} (contador del emisor, {@code null} en payloads legados).
 * {@code eventId} es opcional (FIX-0003 BR-010/BR-002: clave de idempotencia explícita).
 * {@code calidadEmisor} es opcional (FIX-0004 BR-007 / FIX-0006 BR-005: marca informativa; si es
 * {@code ERROR_SENSOR}, la lectura se excluye de severidad aunque el valor sea plausible).</p>
 */
public record LecturaEntrada(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        String unidadMedida,
        String eventId,
        String calidadEmisor,
        String esquemaVersion,
        Long secuencia
) {

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor, String unidadMedida,
                          String eventId, String calidadEmisor) {
        this(sensorId, timestamp, valor, unidadMedida, eventId, calidadEmisor, null, null);
    }

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor,
                          String unidadMedida, String eventId) {
        this(sensorId, timestamp, valor, unidadMedida, eventId, null, null, null);
    }

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor, String unidadMedida) {
        this(sensorId, timestamp, valor, unidadMedida, null, null, null, null);
    }
}
