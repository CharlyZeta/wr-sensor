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

    /** FIX-0006 BR-001: la versión del schema es configuración del publisher. */
    public record Lecturas(String exchange, String schemaVersion) {

        public Lecturas {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? "1.0" : schemaVersion.trim();
        }
    }
}
