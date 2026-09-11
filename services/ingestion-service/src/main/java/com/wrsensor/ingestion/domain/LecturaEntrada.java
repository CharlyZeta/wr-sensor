package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lectura recibida de `sensor.lecturas`.
 * {@code eventId} es opcional (FIX-0003 BR-010: clave de idempotencia).
 * {@code calidadEmisor} es opcional (FIX-0004 BR-007: si un emisor futuro marca el dato
 * como {@code ERROR_SENSOR}, la lectura se excluye de severidad aunque el valor sea plausible).
 */
public record LecturaEntrada(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valor,
        String unidadMedida,
        String eventId,
        String calidadEmisor
) {

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor,
                          String unidadMedida, String eventId) {
        this(sensorId, timestamp, valor, unidadMedida, eventId, null);
    }

    public LecturaEntrada(UUID sensorId, Instant timestamp, BigDecimal valor, String unidadMedida) {
        this(sensorId, timestamp, valor, unidadMedida, null, null);
    }
}
