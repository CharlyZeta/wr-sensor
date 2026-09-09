package com.wrsensor.datasimulator.infrastructure.config;

import com.wrsensor.datasimulator.domain.model.SensorSimulado;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Configuracion del simulador (application.yml) — FEAT-0010 BR-006/BR-007.
 */
@ConfigurationProperties(prefix = "simulador")
public record SimuladorProperties(
        List<SensorSimulado> sensores,
        Ruido ruido,
        Anomalia anomalia,
        Integer frecuenciaReporteSegundos, // default global (null → 30)
        Lecturas lecturas
) {

    public record Ruido(BigDecimal sigmaMetros) {}

    public record Anomalia(long segundos, BigDecimal saltoMetros) {}

    public record Lecturas(String exchange) {}
}
