package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Lectura lista para persistir en la hypertable `lectura` (BR-003). */
public record LecturaPersistida(
        UUID sensorId,
        Instant ts,
        BigDecimal valor,
        String unidadMedida,
        Severidad severidad
) {
}
