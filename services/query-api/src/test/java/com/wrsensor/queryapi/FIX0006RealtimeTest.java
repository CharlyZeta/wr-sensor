package com.wrsensor.queryapi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.infrastructure.adapter.in.messaging.LecturasRealtimeConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-0006 — consumo tiempo real del payload versionado en `query-api`
 * (unit tests BR-006, BR-007, BR-009 y AC-010).
 */
class FIX0006RealtimeTest {

    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final String TS = "2026-09-13T10:00:00Z";

    private static byte[] body(String extra) {
        return ("{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"" + TS
                + "\",\"valor\":5.0,\"unidadMedida\":\"METROS\"" + (extra == null ? "" : extra) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-010: un evento v1 se entrega con su calidad")
    void ac010_calidadDelEvento() {
        LecturaConsulta l = LecturasRealtimeConsumer.parseLectura(body(
                ",\"schemaVersion\":\"1.0\",\"calidad\":{\"estado\":\"OK\",\"confianza\":0.95,"
                        + "\"codigosAnomalias\":[]}"));
        assertThat(l.sensorId()).isEqualTo(SENSOR);
        assertThat(l.calidad()).isEqualTo("OK");
        assertThat(l.severidad()).as("la severidad no viaja en sensor.lecturas").isNull();
    }

    @Test
    @DisplayName("AC-010 / BR-007: una versión mayor desconocida se entrega igual (con un WARN por versión)")
    void ac010_versionMayorTolerada() {
        Logger logger = (Logger) LoggerFactory.getLogger(LecturasRealtimeConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(LecturasRealtimeConsumer.parseLectura(body(",\"schemaVersion\":\"9.0\"")).valor())
                    .isNotNull();
            LecturasRealtimeConsumer.parseLectura(body(",\"schemaVersion\":\"9.0\""));
            LecturasRealtimeConsumer.parseLectura(body(",\"schemaVersion\":\"9.4\""));
            long warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("mayor desconocida")).count();
            assertThat(warns).as("un aviso por versión mayor").isEqualTo(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("BR-006: el payload legado y los campos desconocidos siguen funcionando")
    void br006_legadoYDesconocidos() {
        assertThat(LecturasRealtimeConsumer.parseLectura(body(null)).calidad()).isNull();
        assertThat(LecturasRealtimeConsumer.parseLectura(body(",\"futuro\":{\"x\":1}")).sensorId())
                .isEqualTo(SENSOR);
        assertThat(LecturasRealtimeConsumer.parseLectura(
                "{\"sensorId\" : \"00000000-0000-4000-8000-0000000000c1\" , \"timestamp\" : \""
                        .concat(TS).concat("\", \"valor\" : 5.0, \"unidadMedida\" : \"METROS\"}")
                        .getBytes(StandardCharsets.UTF_8)).valor())
                .as("con espacios: los regex lo rechazaban").isNotNull();
    }

    @Test
    @DisplayName("BR-009: un payload malformado se descarta con motivo y no rompe el stream")
    void br009_malformado() {
        assertThatThrownBy(() -> LecturasRealtimeConsumer.parseLectura("no-es-json".getBytes(
                StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformado");
        assertThatThrownBy(() -> LecturasRealtimeConsumer.parseLectura(
                "{\"sensorId\":\"x\"}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("payload incompleto");
        assertThatThrownBy(() -> LecturasRealtimeConsumer.parseLectura(
                ("{\"sensorId\":\"" + SENSOR + "\",\"timestamp\":\"2026-09-13T10:00:00\","
                        + "\"valor\":5.0,\"unidadMedida\":\"METROS\"}").getBytes(StandardCharsets.UTF_8)))
                .as("timestamp naive").hasMessageContaining("ISO-8601");
        assertThat(LecturasRealtimeConsumer.parseLectura(body(null)))
                .as("el parser sigue operativo después de los errores").isNotNull();
    }
}
