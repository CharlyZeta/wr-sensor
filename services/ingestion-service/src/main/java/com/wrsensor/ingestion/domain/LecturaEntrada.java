package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura recibida de `sensor.lecturas`. {@code eventId} es opcional (FIX-0003
 * BR-010: el payload actual no lo trae; si llega en el futuro se usa como clave de
 * idempotencia).
 */
public record LecturaEntrada(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        String unidadMedida,
        String eventId
) {

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor, String unidadMedida) {
        this(sensorId, timestamp, valor, unidadMedida, null);
    }
}
