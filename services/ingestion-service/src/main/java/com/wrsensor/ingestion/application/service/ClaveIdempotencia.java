package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.domain.LecturaEntrada;

/**
 * Clave de idempotencia (FIX-0003 BR-001): usa {@code eventId} si el payload lo trae;
 * en caso contrario la clave natural {@code (sensorId, timestamp)} (epoch ms).
 * Determinista y estable entre redeliveries del mismo mensaje.
 */
public final class ClaveIdempotencia {

    private ClaveIdempotencia() {}

    public static String de(LecturaEntrada lectura) {
        if (lectura.eventId() != null && !lectura.eventId().isBlank()) {
            return "evt:" + lectura.eventId().trim();
        }
        return "nat:" + lectura.sensorId() + ":" + lectura.timestamp().toEpochMilli();
    }
}
