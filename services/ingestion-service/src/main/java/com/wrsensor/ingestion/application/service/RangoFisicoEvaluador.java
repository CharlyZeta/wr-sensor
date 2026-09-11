package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluador de rango fisico (FIX-0004 BR-001/BR-002/BR-006): funcion pura.
 *
 * <p>Rango aplicable: override por {@code sensorId} → override por {@code codigo}
 * (case-insensitive) → rango global de la **unidad** del sensor. Si no hay rango
 * para la unidad, el veredicto es {@code fuera=true} con motivo
 * {@code UNIDAD_SIN_RANGO} (nunca se aplica el rango de otra unidad).
 */
public final class RangoFisicoEvaluador {

    public record Veredicto(boolean fuera, String motivo) {}

    private RangoFisicoEvaluador() {}

    public static Veredicto evaluar(SensorInfo sensor, BigDecimal valor,
                                    IngestionProperties.RangoFisico cfg) {
        IngestionProperties.RangoFisico.Rango rango = rangoAplicable(sensor, cfg);
        if (rango == null || rango.min() == null || rango.max() == null) {
            return new Veredicto(true, "UNIDAD_SIN_RANGO:" + sensor.unidadMedida());
        }
        if (valor == null || valor.compareTo(rango.min()) < 0 || valor.compareTo(rango.max()) > 0) {
            return new Veredicto(true, "FUERA_DE_RANGO_FISICO:" + rango.min() + ".." + rango.max());
        }
        return new Veredicto(false, "OK");
    }

    /** Rango aplicable (override por sensor/codigo, o global de la unidad); null si no hay. */
    static IngestionProperties.RangoFisico.Rango rangoAplicable(SensorInfo sensor,
                                                               IngestionProperties.RangoFisico cfg) {
        if (cfg == null) return null;
        Map<String, IngestionProperties.RangoFisico.Rango> overrides = cfg.overrides();
        if (overrides != null) {
            IngestionProperties.RangoFisico.Rango porId = overrides.get(sensor.id().toString());
            if (porId != null) return porId;
            if (sensor.codigo() != null) {
                for (Map.Entry<String, IngestionProperties.RangoFisico.Rango> e : overrides.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(sensor.codigo())) return e.getValue();
                }
            }
        }
        Map<String, IngestionProperties.RangoFisico.Rango> unidades = cfg.unidades();
        if (unidades == null || sensor.unidadMedida() == null) return null;
        String unidad = sensor.unidadMedida().toUpperCase(Locale.ROOT);
        IngestionProperties.RangoFisico.Rango exacto = unidades.get(unidad);
        if (exacto != null) return exacto;
        for (Map.Entry<String, IngestionProperties.RangoFisico.Rango> e : unidades.entrySet()) {
            if (e.getKey().equalsIgnoreCase(unidad)) return e.getValue();
        }
        return null;
    }
}
