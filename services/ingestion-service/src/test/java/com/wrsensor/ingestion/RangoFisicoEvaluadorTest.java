package com.wrsensor.ingestion;

import com.wrsensor.ingestion.application.service.RangoFisicoEvaluador;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FIX-0004 BR-001/BR-002/BR-006 (rango fisico por unidad + override por sensor).
 * Cubre AC-001, AC-004 y AC-005 a nivel de evaluador.
 */
class RangoFisicoEvaluadorTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static SensorInfo sensor(String codigo, String unidad) {
        return new SensorInfo(ID_A, codigo, "ACTIVO", unidad,
                new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
                new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
                new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));
    }

    private static IngestionProperties.RangoFisico cfg(Map<String, IngestionProperties.RangoFisico.Rango> overrides) {
        return new IngestionProperties.RangoFisico(
                Map.of("METROS", new IngestionProperties.RangoFisico.Rango(new BigDecimal("-1.0"), new BigDecimal("15.0")),
                        "CENTIMETROS", new IngestionProperties.RangoFisico.Rango(new BigDecimal("-100"), new BigDecimal("1500"))),
                overrides);
    }

    @Test
    @DisplayName("AC-001 / BR-001: valor dentro del rango global de la unidad → fuera=false")
    void ac001_dentroDeRango() {
        var v = RangoFisicoEvaluador.evaluar(sensor("S-A", "METROS"), new BigDecimal("3.4"), cfg(Map.of()));
        assertThat(v.fuera()).isFalse();
    }

    @Test
    @DisplayName("AC-002 (lógica) / BR-003: valor fuera del rango físico → fuera=true con motivo")
    void ac002_fueraDeRango() {
        var v = RangoFisicoEvaluador.evaluar(sensor("S-A", "METROS"), new BigDecimal("-50.0"), cfg(Map.of()));
        assertThat(v.fuera()).isTrue();
        assertThat(v.motivo()).contains("FUERA_DE_RANGO_FISICO");
    }

    @Test
    @DisplayName("AC-004 / BR-002: el override por codigo es más restrictivo que el global")
    void ac004_overridePorCodigo() {
        var conOverride = RangoFisicoEvaluador.evaluar(sensor("SALADO-SANJUSTO", "METROS"),
                new BigDecimal("9.5"),
                cfg(Map.of("SALADO-SANJUSTO", new IngestionProperties.RangoFisico.Rango(
                        new BigDecimal("0.0"), new BigDecimal("8.0")))));
        assertThat(conOverride.fuera()).as("9.5 fuera del override 0..8").isTrue();

        var sinOverride = RangoFisicoEvaluador.evaluar(sensor("OTRO-SENSOR", "METROS"),
                new BigDecimal("9.5"), cfg(Map.of("SALADO-SANJUSTO", new IngestionProperties.RangoFisico.Rango(
                        new BigDecimal("0.0"), new BigDecimal("8.0")))));
        assertThat(sinOverride.fuera()).as("otro sensor usa el global -1..15").isFalse();
    }

    @Test
    @DisplayName("AC-005 / BR-006: se aplica el rango de la unidad del sensor (CENTIMETROS)")
    void ac005_unidadCentimetros() {
        var v = RangoFisicoEvaluador.evaluar(sensor("S-C", "CENTIMETROS"), new BigDecimal("340"), cfg(Map.of()));
        assertThat(v.fuera()).as("340 cm está dentro de -100..1500 cm").isFalse();

        var fuera = RangoFisicoEvaluador.evaluar(sensor("S-C", "CENTIMETROS"), new BigDecimal("9000"), cfg(Map.of()));
        assertThat(fuera.fuera()).isTrue();
    }

    @Test
    @DisplayName("BR-006: unidad sin rango configurado → fuera=true (UNIDAD_SIN_RANGO), nunca otro rango")
    void br006_unidadSinRango() {
        var v = RangoFisicoEvaluador.evaluar(sensor("S-D", "PIES"), new BigDecimal("5"), cfg(Map.of()));
        assertThat(v.fuera()).isTrue();
        assertThat(v.motivo()).startsWith("UNIDAD_SIN_RANGO");
    }
}
