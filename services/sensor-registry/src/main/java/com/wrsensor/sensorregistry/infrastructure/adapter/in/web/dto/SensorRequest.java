package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO de request del endpoint POST /api/sensores (Main Flow FEAT-0001).
 * Validacion estructural via Bean Validation; las reglas de negocio
 * (BR-001..BR-009) se aplican en el caso de uso.
 */
public record SensorRequest(
        @NotBlank String codigo,
        @NotBlank String nombre,
        @NotBlank String tipo,
        @NotNull BigDecimal latitud,
        @NotNull BigDecimal longitud,
        @NotBlank String unidadMedida,
        @NotBlank String estado,
        @NotNull BigDecimal histeresis,
        @NotNull Integer frecuenciaReporteSegundos,
        @Valid @NotNull RangoDto rangoNormal,
        @Valid @NotNull RangoDto rangoWarning,
        @Valid @NotNull RangoDto rangoCritical
) {

    public record RangoDto(
            @NotNull BigDecimal min,
            @NotNull BigDecimal max
    ) {}
}
