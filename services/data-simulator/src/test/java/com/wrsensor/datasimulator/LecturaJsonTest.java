package com.wrsensor.datasimulator;

import com.wrsensor.datasimulator.domain.model.CalidadEmisor;
import com.wrsensor.datasimulator.domain.model.Lectura;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import com.wrsensor.datasimulator.infrastructure.adapter.out.messaging.RabbitLecturaPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0010 BR-002 (formato base) y FIX-0006 BR-001..BR-005 (payload v1 versionado).
 */
class LecturaJsonTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID EVENTO = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant TS = Instant.parse("2026-09-09T12:00:00Z");

    private static Lectura lectura(CalidadEmisor calidad) {
        return new Lectura(ID, TS, new BigDecimal("5.10"), UnidadMedida.METROS, EVENTO, 7, calidad);
    }

    @Test
    @DisplayName("FIX-0006 BR-001..BR-005: el payload v1 lleva version, eventId, sequence y calidad")
    void testBR001_payloadV1() {
        String json = RabbitLecturaPublisher.serializeJson(lectura(CalidadEmisor.normal()), "1.0");

        assertThat(json).isEqualTo("{\"schemaVersion\":\"1.0\",\"eventId\":\"" + EVENTO + "\""
                + ",\"sensorId\":\"" + ID + "\",\"timestamp\":\"2026-09-09T12:00:00Z\""
                + ",\"valor\":5.10,\"unidadMedida\":\"METROS\",\"sequence\":7"
                + ",\"calidad\":{\"estado\":\"OK\",\"confianza\":0.95,\"codigosAnomalias\":[]}}");
        assertThat(json).doesNotContain("severidad");
    }

    @Test
    @DisplayName("FIX-0006 BR-001: la version del schema sale de configuración (no hardcodeada)")
    void testBR001_versionConfigurable() {
        String json = RabbitLecturaPublisher.serializeJson(lectura(CalidadEmisor.normal()), "2.1");
        assertThat(json).startsWith("{\"schemaVersion\":\"2.1\"");
    }

    @Test
    @DisplayName("FIX-0006 BR-005: la anomalía viaja como código con confianza menor, no como ERROR_SENSOR")
    void testBR005_calidadConAnomalia() {
        String json = RabbitLecturaPublisher.serializeJson(lectura(CalidadEmisor.conAnomalia()), "1.0");
        assertThat(json).contains("\"estado\":\"OK\"")
                .contains("\"confianza\":0.4")
                .contains("\"codigosAnomalias\":[\"ANOMALIA_INYECTADA\"]");
        assertThat(json).doesNotContain(CalidadEmisor.ERROR_SENSOR);
    }

    @Test
    @DisplayName("FIX-0006 BR-005: la confianza siempre está en [0,1]")
    void testBR005_confianzaValida() {
        assertThat(CalidadEmisor.normal().confianza()).isBetween(0.0, 1.0);
        assertThat(CalidadEmisor.conAnomalia().confianza()).isBetween(0.0, 1.0);
        assertThat(CalidadEmisor.normal().codigosAnomalias()).isEqualTo(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new CalidadEmisor("OK", 1.5, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confianza");
    }
}
