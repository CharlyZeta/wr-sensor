package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wrsensor.ingestion.application.service.ClaveIdempotencia;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.adapter.in.messaging.LecturasRabbitConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-0006 — parseo del payload con DTO + Jackson (unit tests BR-004, BR-005, BR-006, AF-01,
 * AF-04, AF-05 y AC-004, AC-005).
 */
class FIX0006ParseoTest {

    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final String TS = "2026-09-13T10:00:00Z";

    private static byte[] v1(String extra) {
        return ("{\"schemaVersion\":\"1.0\",\"eventId\":\"evt-abc\",\"sensorId\":\"" + SENSOR + "\","
                + "\"timestamp\":\"" + TS + "\",\"valor\":5.0,\"unidadMedida\":\"METROS\""
                + (extra == null ? "" : extra) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static LecturaEntrada parse(String json) {
        return LecturasRabbitConsumer.parseLectura(json.getBytes(StandardCharsets.UTF_8));
    }

    // ===== AC-004 / BR-006: estructura, espacios y campos desconocidos =====

    @Test
    @DisplayName("AC-004: un payload con espacios y campos desconocidos se parsea (los regex lo rechazaban)")
    void ac004_espaciosYCamposDesconocidos() {
        LecturaEntrada l = parse("""
                {
                  "sensorId" : "00000000-0000-4000-8000-0000000000a1" ,
                  "timestamp" : "2026-09-13T10:00:00Z",
                  "valor" : 5.0,
                  "unidadMedida" : "METROS",
                  "futuro" : { "x" : 1 },
                  "otroCampoNuevo" : [1, 2, 3]
                }
                """);

        assertThat(l.sensorId()).isEqualTo(SENSOR);
        assertThat(l.timestamp()).isEqualTo(Instant.parse(TS));
        assertThat(l.valor()).isEqualByComparingTo("5.0");
        assertThat(l.unidadMedida()).isEqualTo("METROS");
        assertThat(l.esquemaVersion()).as("sin schemaVersion: legado").isNull();
    }

    @Test
    @DisplayName("BR-004: timestamp naive (sin offset) → PAYLOAD_INVALID")
    void br004_timestampSinOffset() {
        assertThatThrownBy(() -> parse("{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"2026-09-13T10:00:00\","
                + "\"valor\":5.0,\"unidadMedida\":\"METROS\"}"))
                .isInstanceOf(RechazoLecturaException.class)
                .hasMessageContaining("ISO-8601")
                .extracting(e -> ((RechazoLecturaException) e).motivo)
                .isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("BR-006: campos requeridos ausentes o malformados → PAYLOAD_INVALID")
    void br006_requeridos() {
        assertThatThrownBy(() -> parse("{}"))
                .isInstanceOf(RechazoLecturaException.class)
                .hasMessageContaining("sensorId");
        assertThatThrownBy(() -> parse("{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"" + TS + "\"}"))
                .hasMessageContaining("falta valor");
        assertThatThrownBy(() -> parse("{\"sensorId\":\"no-es-uuid\",\"timestamp\":\"" + TS + "\","
                + "\"valor\":1,\"unidadMedida\":\"METROS\"}"))
                .hasMessageContaining("no es UUID");
        assertThatThrownBy(() -> parse("esto no es json"))
                .hasMessageContaining("malformado")
                .extracting(e -> ((RechazoLecturaException) e).motivo)
                .isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
    }

    // ===== BR-004 / BR-005 / BR-003: campos del payload v1 =====

    @Test
    @DisplayName("BR-005: la marca de calidad del emisor llega al dominio; la secuencia también")
    void br005_calidadYSecuencia() {
        LecturaEntrada l = parse(new String(v1(",\"sequence\":42,\"calidad\":{\"estado\":\"ERROR_SENSOR\","
                + "\"confianza\":0.2,\"codigosAnomalias\":[\"X\"]}"), StandardCharsets.UTF_8));

        assertThat(l.calidadEmisor()).isEqualTo("ERROR_SENSOR");
        assertThat(l.secuencia()).isEqualTo(42L);
        assertThat(l.esquemaVersion()).isEqualTo("1.0");
        assertThat(l.eventId()).isEqualTo("evt-abc");
    }

    @Test
    @DisplayName("AF-04: evento v1 sin calidad se procesa (campo opcional)")
    void af04_sinCalidad() {
        LecturaEntrada l = parse(new String(v1(",\"sequence\":1"), StandardCharsets.UTF_8));
        assertThat(l.calidadEmisor()).isNull();
        assertThat(l.secuencia()).isEqualTo(1L);
    }

    @Test
    @DisplayName("BR-005: una secuencia negativa se descarta (queda sin secuencia, sin romper el parseo)")
    void br005_secuenciaNegativa() {
        assertThat(parse(new String(v1(",\"sequence\":-3"), StandardCharsets.UTF_8)).secuencia()).isNull();
    }

    @Test
    @DisplayName("AF-05: confianza fuera de rango y codigosAnomalias no-array → WARN y la lectura se procesa")
    void af05_calidadInvalidaTolerada() {
        Logger logger = (Logger) LoggerFactory.getLogger(LecturasRabbitConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            LecturaEntrada l = parse(new String(v1(",\"calidad\":{\"estado\":\"OK\",\"confianza\":1.7,"
                    + "\"codigosAnomalias\":\"no-es-array\"}"), StandardCharsets.UTF_8));

            assertThat(l.valor()).isEqualByComparingTo("5.0");
            assertThat(l.calidadEmisor()).as("el estado válido se conserva").isEqualTo("OK");
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("campo informativo de calidad ignorado"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ===== AF-01: eventId como clave de idempotencia explícita =====

    @Test
    @DisplayName("AF-01: con eventId la clave de idempotencia es evt:<eventId> (un redelivery deduplica)")
    void af01_eventIdClaveIdempotencia() {
        LecturaEntrada primera = parse(new String(v1(null), StandardCharsets.UTF_8));
        LecturaEntrada redelivery = parse(new String(v1(null), StandardCharsets.UTF_8));
        assertThat(ClaveIdempotencia.de(primera)).isEqualTo("evt:evt-abc");
        assertThat(ClaveIdempotencia.de(redelivery)).isEqualTo(ClaveIdempotencia.de(primera));

        LecturaEntrada sinEvento = parse("{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"" + TS
                + "\",\"valor\":5.0,\"unidadMedida\":\"METROS\"}");
        assertThat(ClaveIdempotencia.de(sinEvento))
                .as("sin eventId sigue aplicando la clave natural (FIX-0003)")
                .startsWith("nat:" + SENSOR + ":");
    }
}
