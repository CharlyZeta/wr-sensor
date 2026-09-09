package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Config del sensor (rangos/estado) — vista remota de sensor-registry. */
public record SensorInfo(
        UUID id,
        String codigo,
        String estado,
        String unidadMedida,
        Rango rangoNormal,
        Rango rangoWarning,
        Rango rangoCritical
) {

    public record Rango(BigDecimal min, BigDecimal max) {}
}
