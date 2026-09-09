package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;

import java.math.BigDecimal;

/**
 * Evaluador de bandas (BR-002): cadena inclusiva normal ⊆ warning ⊆ critical.
 * Dentro de rangoNormal → NORMAL; fuera de normal pero dentro de rangoWarning →
 * WARNING; en otro caso → CRITICAL. Funcion pura, sin estado.
 */
public final class SeveridadEvaluador {

    private SeveridadEvaluador() {}

    public static Severidad evaluar(SensorInfo sensor, BigDecimal valor) {
        SensorInfo.Rango normal = sensor.rangoNormal();
        SensorInfo.Rango warning = sensor.rangoWarning();

        if (en(normal, valor)) return Severidad.NORMAL;
        if (en(warning, valor)) return Severidad.WARNING;
        return Severidad.CRITICAL;
    }

    private static boolean en(SensorInfo.Rango rango, BigDecimal v) {
        return v.compareTo(rango.min()) >= 0 && v.compareTo(rango.max()) <= 0;
    }
}
