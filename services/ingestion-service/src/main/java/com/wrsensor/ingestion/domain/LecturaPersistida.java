package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura lista para persistir en la hypertable `lectura` (BR-003 FEAT-0011).
 *
 * <p>FIX-0004: {@code calidad} marca la validez fisica del dato y {@code severidad} puede ser
 * {@code null} (no evaluada) cuando la lectura es {@code ERROR_SENSOR}.
 * FIX-0006: {@code secuencia} es la del emisor ({@code null} en payloads legados) y se usa para
 * detectar huecos de publicación.</p>
 */
public record LecturaPersistida(
        UUID sensorId,
        Instant ts,
        BigDecimal valor,
        String unidadMedida,
        Severidad severidad,
        Calidad calidad,
        Long secuencia
) {

    /** Constructor de conveniencia para lecturas evaluadas normalmente. */
    public LecturaPersistida(UUID sensorId, Instant ts, BigDecimal valor,
                             String unidadMedida, Severidad severidad) {
        this(sensorId, ts, valor, unidadMedida, severidad, Calidad.OK, null);
    }

    public LecturaPersistida(UUID sensorId, Instant ts, BigDecimal valor, String unidadMedida,
                             Severidad severidad, Calidad calidad) {
        this(sensorId, ts, valor, unidadMedida, severidad, calidad, null);
    }
}
