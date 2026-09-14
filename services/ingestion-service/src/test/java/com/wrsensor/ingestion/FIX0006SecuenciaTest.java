package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0006 — secuencia del emisor: persistencia y detección de huecos (unit tests BR-008, AF-03
 * y AC-008).
 */
class FIX0006SecuenciaTest {

    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private static final SensorInfo ACTIVO = new SensorInfo(SENSOR, "PARANA-RECONQUISTA", "ACTIVO",
            "METROS",
            new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
            new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
            new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));

    private static final IngestionProperties PROPS = new IngestionProperties(
            300L,
            new IngestionProperties.Registry("http://x",
                    new IngestionProperties.Registry.Auth("a", "b"), null, null, null, null),
            new IngestionProperties.Lecturas("sensor.lecturas", "dlq", "dlx"),
            new IngestionProperties.Alertas("sensor.alertas"),
            new IngestionProperties.Messaging(3, "dlx"),
            new IngestionProperties.Outbox(null, null, null, null, null, null, null),
            new IngestionProperties.RangoFisico(
                    Map.of("METROS", new IngestionProperties.RangoFisico.Rango(
                            new BigDecimal("-1.0"), new BigDecimal("15.0"))),
                    Map.of()),
            new IngestionProperties.Particiones(4, null, "sensor.lecturas.part",
                    "queue.sensor.lecturas.p{i}"),
            new IngestionProperties.Schema("1.0", true));

    private static final class FakeSensores implements SensorConfigPort {
        @Override
        public Mono<SensorInfo> findById(UUID sensorId) {
            return sensorId.equals(SENSOR) ? Mono.just(ACTIVO) : Mono.empty();
        }
    }

    private static final class FakeStore implements IngestaTransaccionalPort {
        final List<LecturaPersistida> filas = new ArrayList<>();

        @Override
        public Mono<Resultado> persistir(LecturaPersistida lectura, String clave, OutboxAlerta outbox) {
            filas.add(lectura);
            return Mono.just(new Resultado(true));
        }
    }

    private record H(IngestorLecturas ingestor, FakeStore store) {}

    private static H newH() {
        FakeStore store = new FakeStore();
        return new H(new IngestorLecturas(new FakeSensores(), store, PROPS), store);
    }

    private static LecturaEntrada lectura(long sequence) {
        return new LecturaEntrada(SENSOR, Instant.now(), new BigDecimal("5.0"), "METROS",
                null, null, "1.0", sequence);
    }

    private static ListAppender<ILoggingEvent> capturarLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(IngestorLecturas.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void soltarLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(IngestorLecturas.class)).detachAppender(appender);
    }

    // ===== AC-008: huecos =====

    @Test
    @DisplayName("AC-008: secuencias 1 y 3 → WARN de hueco y la lectura se persiste igual")
    void ac008_huecoDetectado() {
        H h = newH();
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            h.ingestor().procesar(lectura(1)).block();
            h.ingestor().procesar(lectura(3)).block();

            assertThat(h.store().filas).as("un hueco no descarta la lectura").hasSize(2);
            assertThat(h.store().filas.get(1).secuencia()).as("AC-009: la secuencia se persiste")
                    .isEqualTo(3L);
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("hueco de secuencia")
                    && e.getFormattedMessage().contains("faltantes=1"));
        } finally {
            soltarLog(logs);
        }
    }

    @Test
    @DisplayName("AC-008 / AF-03: secuencia que retrocede (publisher reiniciado) → INFO, sin WARN de hueco")
    void ac008_secuenciaReiniciada() {
        H h = newH();
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            h.ingestor().procesar(lectura(5)).block();
            h.ingestor().procesar(lectura(1)).block();

            assertThat(h.store().filas).hasSize(2);
            assertThat(h.store().filas.get(1).secuencia()).isEqualTo(1L);
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("secuencia reiniciada"));
            assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("hueco de secuencia"));
        } finally {
            soltarLog(logs);
        }
    }

    @Test
    @DisplayName("BR-008: secuencia consecutiva no genera ninguna advertencia")
    void br008_secuenciaContinua() {
        H h = newH();
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            h.ingestor().procesar(lectura(1)).block();
            h.ingestor().procesar(lectura(2)).block();
            h.ingestor().procesar(lectura(3)).block();

            assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("secuencia"));
            assertThat(h.store().filas).hasSize(3);
            assertThat(h.store().filas.stream().map(LecturaPersistida::secuencia))
                    .containsExactly(1L, 2L, 3L);
        } finally {
            soltarLog(logs);
        }
    }

    @Test
    @DisplayName("BR-008: un evento legado (sin sequence) no participa de la detección de huecos")
    void br008_eventoLegado() {
        H h = newH();
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            LecturaEntrada legado = new LecturaEntrada(SENSOR, Instant.now(),
                    new BigDecimal("5.0"), "METROS");
            h.ingestor().procesar(legado).block();
            h.ingestor().procesar(lectura(7)).block();

            assertThat(h.store().filas.get(0).secuencia()).as("AC-009: legado → NULL").isNull();
            assertThat(h.store().filas.get(1).secuencia()).isEqualTo(7L);
            assertThat(logs.list).as("la primera secuencia conocida no es un hueco")
                    .noneMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("hueco de secuencia"));
        } finally {
            soltarLog(logs);
        }
    }

    @Test
    @DisplayName("BR-008: la comparación es por sensor (dos sensores con la misma secuencia no chocan)")
    void br008_porSensor() {
        H h = newH();
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            h.ingestor().procesar(lectura(1)).block();
            h.ingestor().procesar(lectura(2)).block();
            assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("hueco de secuencia"));
        } finally {
            soltarLog(logs);
        }
    }
}
