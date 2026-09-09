package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body de PUT /api/sensores/{id} (FEAT-0004): subset config editable COMPLETO.
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} rechaza campos fuera del
 * conjunto editable (BR-004/AC-009: campos inmutables → 400 SENSOR_INVALID_REQUEST
 * via HttpMessageNotReadableException). Reutiliza {@link SensorRequest.RangoDto}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SensorUpdateRequest(
        @NotBlank String estado,
        @NotNull BigDecimal histeresis,
        @NotNull Integer frecuenciaReporteSegundos,
        @NotNull SensorRequest.RangoDto rangoNormal,
        @NotNull SensorRequest.RangoDto rangoWarning,
        @NotNull SensorRequest.RangoDto rangoCritical
) {
}
