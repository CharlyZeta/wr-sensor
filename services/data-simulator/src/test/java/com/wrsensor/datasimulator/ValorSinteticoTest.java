package com.wrsensor.datasimulator;

import com.wrsensor.datasimulator.application.service.ValorSintetico;
import com.wrsensor.datasimulator.domain.model.SensorSimulado;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0010 BR-003 y AC-009 (generador puro, deterministico con
 * ruido=0). Test IDs: unit-test:FEAT-0010-br003, assertion:FEAT-0010-ac009.
 */
class ValorSinteticoTest {

    private static final SensorSimulado SENSOR = new SensorSimulado(
            "PARANA-RECONQUISTA", "Reconquista", UnidadMedida.METROS,
            30, new BigDecimal("5.1"), new BigDecimal("7.0"));

    private static final BigDecimal SIGMA = new BigDecimal("0");

    private static Instant aLaHora(int hora) {
        return Instant.parse("2026-09-09T00:00:00Z").plusSeconds(hora * 3600L);
    }

    @Test
    @DisplayName("BR-003: sin ruido, el valor oscila ±0.5 m alrededor del nivel base (seno diario)")
    void testBR003_oscilacionDiaria() {
        assertThat(ValorSintetico.valor(SENSOR, aLaHora(0), SIGMA, null, 0))
                .isEqualByComparingTo("5.10"); // sin(0) = 0
        assertThat(ValorSintetico.valor(SENSOR, aLaHora(6), SIGMA, null, 0))
                .isEqualByComparingTo("5.60"); // sin(pi/2) = +1 → +0.5
        assertThat(ValorSintetico.valor(SENSOR, aLaHora(18), SIGMA, null, 0))
                .isEqualByComparingTo("4.60"); // sin(3pi/2) = -1 → -0.5
    }

    @Test
    @DisplayName("BR-003: la anomalia suma el salto configurado al valor")
    void testBR003_anomaliaSumaSalto() {
        BigDecimal conAnomalia = ValorSintetico.valor(SENSOR, aLaHora(0), SIGMA,
                new BigDecimal("2.0"), 0);
        assertThat(conAnomalia).isEqualByComparingTo("7.10");
    }

    @Test
    @DisplayName("AC-009: valores plausibles — escala de 2, dentro de [nivelBase-0.5, nivelBase+0.5] + salto")
    void testAC009_valoresPlausibles() {
        for (int h = 0; h < 24; h += 3) {
            BigDecimal v = ValorSintetico.valor(SENSOR, aLaHora(h), SIGMA, null, 0);
            assertThat(v).isBetween(new BigDecimal("4.50"), new BigDecimal("5.70"));
            assertThat(v.scale()).isEqualTo(2);
        }
        BigDecimal salto = ValorSintetico.valor(SENSOR, aLaHora(0), SIGMA, new BigDecimal("3.0"), 0);
        assertThat(salto).isGreaterThan(SENSOR.rangoNormalMax()); // supera la banda normal (AC-005)
    }
}
