package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wrsensor.ingestion.application.service.RegistroEsquema;
import com.wrsensor.ingestion.domain.EsquemaLectura;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-0006 — política de versiones del schema (unit tests BR-006, BR-007, BR-011, AF-02 y
 * AC-006, AC-011).
 */
class FIX0006EsquemaTest {

    private static IngestionProperties.Schema cfg(String version, Boolean tolerar) {
        return new IngestionProperties.Schema(version, tolerar);
    }

    private static ListAppender<ILoggingEvent> capturarLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(RegistroEsquema.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void soltarLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(RegistroEsquema.class)).detachAppender(appender);
    }

    // ===== BR-006: resolución de versiones =====

    @Test
    @DisplayName("BR-006: sin schemaVersion es LEGADO (0.0); 1.x es SOPORTADA; 2.0 es MAYOR_DESCONOCIDA")
    void br006_resolucion() {
        assertThat(EsquemaLectura.resolver(null, "1.0").estado()).isEqualTo(EsquemaLectura.Estado.LEGADO);
        assertThat(EsquemaLectura.resolver("  ", "1.0").estado()).isEqualTo(EsquemaLectura.Estado.LEGADO);
        assertThat(EsquemaLectura.resolver("1.0", "1.0").estado())
                .isEqualTo(EsquemaLectura.Estado.SOPORTADA);
        assertThat(EsquemaLectura.resolver("1", "1.0").estado())
                .as("'1' se interpreta como 1.0").isEqualTo(EsquemaLectura.Estado.SOPORTADA);
        assertThat(EsquemaLectura.resolver("1.7.3", "1.0").estado())
                .as("el menor/parche no cambia el mayor").isEqualTo(EsquemaLectura.Estado.SOPORTADA);
        assertThat(EsquemaLectura.resolver("2.0", "1.0").estado())
                .isEqualTo(EsquemaLectura.Estado.MAYOR_DESCONOCIDA);
        assertThat(EsquemaLectura.resolver("uno", "1.0").estado())
                .isEqualTo(EsquemaLectura.Estado.INVALIDA);
        assertThat(EsquemaLectura.resolver("v1", "1.0").estado())
                .isEqualTo(EsquemaLectura.Estado.INVALIDA);
    }

    @Test
    @DisplayName("BR-011: la versión soportada es configurable (2.0 sin WARN cuando se soporta 2.0)")
    void br011_versionSoportadaConfigurable() {
        EsquemaLectura.Resolucion r = EsquemaLectura.resolver("2.0", "2.0");
        assertThat(r.estado()).isEqualTo(EsquemaLectura.Estado.SOPORTADA);
        assertThat(r.procesable(true)).isTrue();

        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            new RegistroEsquema(cfg("2.0", true)).validar("2.0");
            assertThat(logs.list).as("sin WARN: la versión está soportada")
                    .noneMatch(e -> e.getLevel() == Level.WARN);
        } finally {
            soltarLog(logs);
        }
    }

    // ===== BR-007 / AC-005 / AC-006: tolerancia hacia adelante =====

    @Test
    @DisplayName("AC-006: una versión mayor desconocida se procesa y avisa UNA sola vez")
    void ac006_mayorDesconocidaTolerada() {
        RegistroEsquema registro = new RegistroEsquema(cfg("1.0", true));
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            assertThat(registro.validar("2.0").estado())
                    .isEqualTo(EsquemaLectura.Estado.MAYOR_DESCONOCIDA);
            registro.validar("2.0");
            registro.validar("2.1");
            registro.validar("3.0");

            long warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("mayor desconocida")).count();
            assertThat(warns).as("un aviso por versión mayor (2 y 3, no por cada evento)")
                    .isEqualTo(2);
            assertThat(registro.eventosVersionDesconocida()).isEqualTo(4);
        } finally {
            soltarLog(logs);
        }
    }

    @Test
    @DisplayName("AC-005 / BR-007: el payload legado se procesa y se contabiliza")
    void ac005_legadoContabilizado() {
        RegistroEsquema registro = new RegistroEsquema(cfg("1.0", true));
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            assertThat(registro.validar(null).estado()).isEqualTo(EsquemaLectura.Estado.LEGADO);
            registro.validar(null);
            assertThat(registro.eventosLegado()).isEqualTo(2);
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("evento legado")
                    && e.getFormattedMessage().contains("total=1"));
        } finally {
            soltarLog(logs);
        }
    }

    // ===== AF-02 / AC-007 / AC-011: rechazos =====

    @Test
    @DisplayName("AF-02: schemaVersion malformada → PAYLOAD_INVALID (a DLQ, sin romper la cola)")
    void af02_versionMalformada() {
        RegistroEsquema registro = new RegistroEsquema(cfg("1.0", true));
        assertThatThrownBy(() -> registro.validar("uno"))
                .isInstanceOf(RechazoLecturaException.class)
                .hasMessageContaining("schemaVersion invalida")
                .extracting(e -> ((RechazoLecturaException) e).motivo)
                .isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
    }

    @Test
    @DisplayName("AC-011: con la tolerancia desactivada una mayor desconocida va a la DLQ con SCHEMA_UNSUPPORTED")
    void ac011_toleranciaDesactivada() {
        RegistroEsquema estricto = new RegistroEsquema(cfg("1.0", false));
        assertThatThrownBy(() -> estricto.validar("2.0"))
                .isInstanceOf(RechazoLecturaException.class)
                .hasMessageContaining("tolerancia desactivada")
                .extracting(e -> ((RechazoLecturaException) e).motivo)
                .isEqualTo(RechazoLecturaException.SCHEMA_UNSUPPORTED);
        assertThatThrownBy(() -> estricto.validar("uno"))
                .as("una versión inválida se rechaza con o sin tolerancia")
                .extracting(e -> ((RechazoLecturaException) e).motivo)
                .isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
        assertThat(estricto.validar("1.0").estado())
                .as("la versión soportada sigue procesándose")
                .isEqualTo(EsquemaLectura.Estado.SOPORTADA);
        assertThat(estricto.validar(null).estado())
                .as("el legado no depende de la tolerancia").isEqualTo(EsquemaLectura.Estado.LEGADO);
    }
}
