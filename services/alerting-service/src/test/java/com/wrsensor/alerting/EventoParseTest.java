package com.wrsensor.alerting;

import com.wrsensor.alerting.domain.EventoAlerta;
import com.wrsensor.alerting.domain.Severidad;
import com.wrsensor.alerting.infrastructure.adapter.in.messaging.AlertasRabbitConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit — parseo del payload de `sensor.alertas` (FEAT-0011). */
class EventoParseTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    @Test
    @DisplayName("BR-006/AF-03: payload valido parsea; malformado lanza (→ DLQ)")
    void parseOkYMalformado() {
        String valido = "{\"sensorId\":\"" + ID + "\",\"timestamp\":\"2026-09-09T12:00:00Z\","
                + "\"valorLectura\":7.0,\"severidadAnterior\":\"NORMAL\",\"severidadNueva\":\"WARNING\","
                + "\"cruceHisteresis\":false}";
        EventoAlerta e = AlertasRabbitConsumer.parseEvento(valido.getBytes(StandardCharsets.UTF_8));
        assertThat(e.sensorId()).isEqualTo(ID);
        assertThat(e.severidadAnterior()).isEqualTo(Severidad.NORMAL);
        assertThat(e.severidadNueva()).isEqualTo(Severidad.WARNING);

        assertThatThrownBy(() -> AlertasRabbitConsumer.parseEvento(
                "{\"sensorId\":\"no-uuid\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);
    }
}
