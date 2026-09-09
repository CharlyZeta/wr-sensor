package com.wrsensor.ingestion;

import com.wrsensor.ingestion.application.service.SeveridadEvaluador;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0011 BR-002: evaluacion por bandas (normal ⊆ warning ⊆ critical).
 */
class SeveridadEvaluadorTest {

    private static final SensorInfo SENSOR = new SensorInfo(
            UUID.fromString("00000000-0000-4000-8000-00000000000a"), "PARANA-RECONQUISTA", "ACTIVO", "METROS",
            new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
            new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
            new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));

    private static Severidad eval(String v) {
        return SeveridadEvaluador.evaluar(SENSOR, new BigDecimal(v));
    }

    @Test
    @DisplayName("BR-002: dentro de normal → NORMAL (bordes incluidos)")
    void testBR002_normal() {
        assertThat(eval("4")).isEqualTo(Severidad.NORMAL);
        assertThat(eval("6")).isEqualTo(Severidad.NORMAL);
        assertThat(eval("5.10")).isEqualTo(Severidad.NORMAL);
    }

    @Test
    @DisplayName("BR-002: fuera de normal pero dentro de warning → WARNING")
    void testBR002_warning() {
        assertThat(eval("3.5")).isEqualTo(Severidad.WARNING);
        assertThat(eval("7")).isEqualTo(Severidad.WARNING);
    }

    @Test
    @DisplayName("BR-002: fuera de warning → CRITICAL")
    void testBR002_critical() {
        assertThat(eval("1")).isEqualTo(Severidad.CRITICAL);
        assertThat(eval("9")).isEqualTo(Severidad.CRITICAL);
    }
}
