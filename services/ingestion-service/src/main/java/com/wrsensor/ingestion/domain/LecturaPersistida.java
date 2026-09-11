package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura lista para persistir en la hypertable `lectura` (BR-003 FEAT-0011).
 * FIX-0004: {@code calidad} marca la validez fisica del dato y {@code severidad}
 * puede ser {@code null} (no evaluada) cuando la lectura es {@code ERROR_SENSOR}.
 */
public record LecturaPersistida(
        UUID sensorId,
        Instant ts,
        BigDecimal valor,
        String unidadMedida,
        Severidad severidad,
        Calidad calidad
) {

    /** Constructor de conveniencia para lecturas evaluadas normalmente. */
    public LecturaPersistida(UUID sensorId, Instant ts, BigDecimal valor,
                             String unidadMedida, Severidad severidad) {
        this(sensorId, ts, valor, unidadMedida, severidad, Calidad.OK);
    }
}
