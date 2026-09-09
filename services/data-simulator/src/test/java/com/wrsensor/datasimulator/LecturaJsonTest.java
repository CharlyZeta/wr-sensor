package com.wrsensor.datasimulator;

import com.wrsensor.datasimulator.domain.model.Lectura;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import com.wrsensor.datasimulator.infrastructure.adapter.out.messaging.RabbitLecturaPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit — FEAT-0010 BR-002: formato del payload de salida. */
class LecturaJsonTest {

    @Test
    @DisplayName("BR-002: el payload JSON tiene exactamente sensorId/timestamp/valor/unidadMedida")
    void testBR002_payloadFormato() {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000001");
        Lectura l = new Lectura(id, Instant.parse("2026-09-09T12:00:00Z"),
                new BigDecimal("5.10"), UnidadMedida.METROS);
        String json = RabbitLecturaPublisher.serializeJson(l);

        assertThat(json).isEqualTo("{\"sensorId\":\"" + id + "\",\"timestamp\":\"2026-09-09T12:00:00Z\","
                + "\"valor\":5.10,\"unidadMedida\":\"METROS\"}");
        assertThat(json).doesNotContain("severidad");
    }
}
