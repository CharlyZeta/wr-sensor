package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wrsensor.sensorregistry.domain.model.Sensor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** DTO de response 201 Created (Main Flow FEAT-0001). */
public record SensorResponse(
        UUID id,
        String codigo,
        String nombre,
        String tipo,
        BigDecimal latitud,
        BigDecimal longitud,
        String unidadMedida,
        String estado,
        BigDecimal histeresis,
        Integer frecuenciaReporteSegundos,
        Instant fechaInstalacion,
        @JsonProperty("rangoNormal") RangoDto rangoNormal,
        @JsonProperty("rangoWarning") RangoDto rangoWarning,
        @JsonProperty("rangoCritical") RangoDto rangoCritical
) {

    public record RangoDto(BigDecimal min, BigDecimal max) {}

    public static SensorResponse from(Sensor s) {
        return new SensorResponse(
                s.id(), s.codigo(), s.nombre(), s.tipo().name(),
                s.latitud(), s.longitud(), s.unidadMedida().name(), s.estado().name(),
                s.histeresis(), s.frecuenciaReporteSegundos(), s.fechaInstalacion(),
                new RangoDto(s.rangoNormal().min(), s.rangoNormal().max()),
                new RangoDto(s.rangoWarning().min(), s.rangoWarning().max()),
                new RangoDto(s.rangoCritical().min(), s.rangoCritical().max())
        );
    }
}
