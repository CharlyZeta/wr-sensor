package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.domain.AlertaEvento;

/**
 * Serializacion del payload de outbox hacia `sensor.alertas` (FIX-0003 BR-004).
 * JSON manual (formato fijo, sin libs extra) — mismo criterio que los otros servicios.
 */
public final class AlertaEventoJson {

    private AlertaEventoJson() {}

    public static String de(AlertaEvento e) {
        return "{\"sensorId\":\"" + e.sensorId()
                + "\",\"timestamp\":\"" + e.timestamp()
                + "\",\"valorLectura\":" + e.valorLectura().toPlainString()
                + ",\"severidadAnterior\":\"" + e.severidadAnterior()
                + "\",\"severidadNueva\":\"" + e.severidadNueva()
                + "\",\"cruceHisteresis\":" + e.cruceHisteresis() + "}";
    }

    public static String routingKey(AlertaEvento e) {
        return "alerta." + e.severidadNueva().name().toLowerCase();
    }
}
