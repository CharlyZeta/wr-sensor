package com.wrsensor.alerting;

import com.wrsensor.alerting.domain.EventoAlerta;
import com.wrsensor.alerting.infrastructure.adapter.in.messaging.AlertasRabbitConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assertions — FIX-0002 AC-001..AC-003: el parser de `sensor.alertas` debe reflejar
 * fielmente `valorLectura` y `cruceHisteresis` (antes: BigDecimal.ONE / false fijos).
 */
class FIX0002AcTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static byte[] payload(String valorJson, String cruceJson) {
        return ("{\"sensorId\":\"" + ID + "\",\"timestamp\":\"2026-09-09T12:00:00Z\","
                + "\"valorLectura\":" + valorJson + ",\"severidadAnterior\":\"NORMAL\","
                + "\"severidadNueva\":\"WARNING\""
                + (cruceJson == null ? "" : ",\"cruceHisteresis\":" + cruceJson) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-001: valorLectura del payload se preserva (7.77, no 1)")
    void ac001_valorReal() {
        EventoAlerta e = AlertasRabbitConsumer.parseEvento(payload("7.77", "true"));
        assertThat(e.valorLectura()).isEqualByComparingTo("7.77");
        assertThat(e.valorLectura()).isNotEqualByComparingTo("1");
        assertThat(e.valorLectura().scale()).isEqualTo(2); // escala preservada
    }

    @Test
    @DisplayName("AC-002: cruceHisteresis fiel al payload; ausente → false (compatibilidad)")
    void ac002_cruceHisteresis() {
        assertThat(AlertasRabbitConsumer.parseEvento(payload("7.77", "true")).cruceHisteresis()).isTrue();
        assertThat(AlertasRabbitConsumer.parseEvento(payload("7.77", "false")).cruceHisteresis()).isFalse();
        assertThat(AlertasRabbitConsumer.parseEvento(payload("7.77", null)).cruceHisteresis())
                .as("emisor viejo sin el campo → false").isFalse();
    }

    @Test
    @DisplayName("AC-003 (regresión): sin valorLectura o no numérico → rechazo (→ DLQ)")
    void ac003_validacionPreservada() {
        String sinValor = "{\"sensorId\":\"" + ID + "\",\"timestamp\":\"2026-09-09T12:00:00Z\","
                + "\"severidadAnterior\":\"NORMAL\",\"severidadNueva\":\"WARNING\"}";
        assertThatThrownBy(() -> AlertasRabbitConsumer.parseEvento(sinValor.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AlertasRabbitConsumer.parseEvento(payload("\"abc\"", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
