package com.wrsensor.datasimulator.domain.model;

import java.util.List;

/**
 * Marca de calidad que el emisor sintético adjunta a cada lectura (FIX-0006 BR-005).
 *
 * <p>Es **informativa**: `estado` sólo puede ser {@code OK} o {@code ERROR_SENSOR}
 * (el enum vigente del consumidor, FIX-0004) y no cambia severidad, alertas ni rango físico.
 * La anomalía inyectada se señala con {@code codigosAnomalias} y una `confianza` menor, no con
 * {@code ERROR_SENSOR} (una anomalía de valor debe seguir generando alertas).</p>
 */
public record CalidadEmisor(String estado, double confianza, List<String> codigosAnomalias) {

    public static final String OK = "OK";
    public static final String ERROR_SENSOR = "ERROR_SENSOR";
    public static final String ANOMALIA_INYECTADA = "ANOMALIA_INYECTADA";

    private static final double CONFIANZA_NORMAL = 0.95;
    private static final double CONFIANZA_ANOMALIA = 0.40;

    public CalidadEmisor {
        codigosAnomalias = codigosAnomalias == null ? List.of() : List.copyOf(codigosAnomalias);
        if (confianza < 0.0 || confianza > 1.0) {
            throw new IllegalArgumentException("confianza fuera de [0,1]: " + confianza);
        }
    }

    /** Lectura sin anomalía: confianza alta y sin códigos. */
    public static CalidadEmisor normal() {
        return new CalidadEmisor(OK, CONFIANZA_NORMAL, List.of());
    }

    /** Lectura dentro de la ventana de anomalía inyectada. */
    public static CalidadEmisor conAnomalia() {
        return new CalidadEmisor(OK, CONFIANZA_ANOMALIA, List.of(ANOMALIA_INYECTADA));
    }
}
