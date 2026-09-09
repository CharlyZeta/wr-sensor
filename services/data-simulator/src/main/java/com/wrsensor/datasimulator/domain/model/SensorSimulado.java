package com.wrsensor.datasimulator.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Sensor simulado (config local, FEAT-0010 BR-006): espejo del dataset §9.5.
 * {@code frecuenciaReporteSegundos} opcional (null → default global de
 * application.yml). {@code nivelBase} ≈ altura de alerta tecnica de referencia.
 */
public record SensorSimulado(
        String codigo,
        String nombre,
        UnidadMedida unidadMedida,
        Integer frecuenciaReporteSegundos,
        BigDecimal nivelBase,
        BigDecimal rangoNormalMax
) {

    public SensorSimulado {
        Objects.requireNonNull(codigo, "codigo");
        Objects.requireNonNull(nombre, "nombre");
        Objects.requireNonNull(unidadMedida, "unidadMedida");
        Objects.requireNonNull(nivelBase, "nivelBase");
        Objects.requireNonNull(rangoNormalMax, "rangoNormalMax");
        codigo = codigo.trim().toUpperCase();
    }
}
