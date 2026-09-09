package com.wrsensor.ingestion;

import com.wrsensor.ingestion.application.port.AlertaEventoPublisher;
import com.wrsensor.ingestion.application.port.LecturaStore;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.AlertaEvento;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import com.wrsensor.ingestion.infrastructure.adapter.in.messaging.LecturasRabbitConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0011 IngestorLecturas (fakes) + parseo del payload.
 * Cubre AC-001..AC-003, AC-005..AC-008 y AF-01..AF-05 (codes).
 */
class IngestorLecturasTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_INACTIVO = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static final SensorInfo ACTIVO = new SensorInfo(ID_A, "PARANA-RECONQUISTA", "ACTIVO", "METROS",
            new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
            new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
            new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));
    private static final SensorInfo INACTIVO = new SensorInfo(ID_INACTIVO, "BAJA", "INACTIVO", "METROS",
            new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
            new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
            new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));

    private static final IngestionProperties PROPS = new IngestionProperties(
            300L,
            new IngestionProperties.Registry("http://x", new IngestionProperties.Registry.Auth("a", "b")),
            new IngestionProperties.Lecturas("sensor.lecturas", "queue", "dlq", "dlx"),
            new IngestionProperties.Alertas("sensor.alertas"),
            new IngestionProperties.Messaging(3, "dlx"));

    private static final class FakeSensores implements SensorConfigPort {
        SensorInfo found = ACTIVO;

        @Override
        public Mono<SensorInfo> findById(UUID sensorId) {
            return found != null && sensorId.equals(found.id()) ? Mono.just(found) : Mono.empty();
        }
    }

    private static final class FakeStore implements LecturaStore {
        final List<LecturaPersistida> filas = new ArrayList<>();

        @Override
        public Mono<Void> insert(LecturaPersistida l) {
            filas.add(l);
            return Mono.empty();
        }
    }

    private static final class FakeAlertas implements AlertaEventoPublisher {
        final List<AlertaEvento> eventos = new ArrayList<>();

        @Override
        public Mono<Void> publish(AlertaEvento e) {
            eventos.add(e);
            return Mono.empty();
        }
    }

    private record H(IngestorLecturas ingestor, FakeStore store, FakeAlertas alertas) {}

    private static H newH(SensorInfo sensor) {
        FakeSensores sensores = new FakeSensores();
        sensores.found = sensor;
        FakeStore store = new FakeStore();
        FakeAlertas alertas = new FakeAlertas();
        IngestorLecturas ing = new IngestorLecturas(sensores, store, alertas, PROPS);
        return new H(ing, store, alertas);
    }

    private static LecturaEntrada lectura(UUID id, String valor) {
        return new LecturaEntrada(id, Instant.now(), new BigDecimal(valor), "METROS");
    }

    @Test
    @DisplayName("AC-001: primera lectura NORMAL se persiste y NO publica evento")
    void ac001_normalSinEvento() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "5.0")))
                .assertNext(r -> assertThat(r.eventoPublicado()).isFalse())
                .verifyComplete();
        assertThat(h.store().filas).hasSize(1);
        assertThat(h.store().filas.get(0).severidad()).isEqualTo(Severidad.NORMAL);
        assertThat(h.alertas().eventos).isEmpty();
    }

    @Test
    @DisplayName("AC-002: cambio NORMAL→WARNING persiste y publica evento key severidadNueva")
    void ac002_cambioAWarningPublicaEvento() {
        H h = newH(ACTIVO);
        h.ingestor().procesar(lectura(ID_A, "5.0")).block(); // NORMAL previo

        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "7.0")))
                .assertNext(r -> assertThat(r.eventoPublicado()).isTrue())
                .verifyComplete();
        assertThat(h.alertas().eventos).hasSize(1);
        AlertaEvento e = h.alertas().eventos.get(0);
        assertThat(e.severidadAnterior()).isEqualTo(Severidad.NORMAL);
        assertThat(e.severidadNueva()).isEqualTo(Severidad.WARNING);
        assertThat(e.cruceHisteresis()).isFalse();
    }

    @Test
    @DisplayName("AC-003: cambio WARNING→CRITICAL publica evento CRITICAL")
    void ac003_cambioACritical() {
        H h = newH(ACTIVO);
        h.ingestor().setUltimaSeveridad(ID_A, Severidad.WARNING);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "9.0")))
                .assertNext(r -> assertThat(r.eventoPublicado()).isTrue())
                .verifyComplete();
        assertThat(h.alertas().eventos.get(0).severidadNueva()).isEqualTo(Severidad.CRITICAL);
        assertThat(h.store().filas.get(0).severidad()).isEqualTo(Severidad.CRITICAL);
    }

    @Test
    @DisplayName("AF-02 / AC-005: sensor desconocido → SENSOR_UNKNOWN y no persiste")
    void af02_sensorDesconocido() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(UUID.randomUUID(), "5.0")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(RechazoLecturaException.class);
                    assertThat(((RechazoLecturaException) err).motivo)
                            .isEqualTo(RechazoLecturaException.SENSOR_UNKNOWN);
                })
                .verify();
        assertThat(h.store().filas).isEmpty();
    }

    @Test
    @DisplayName("AF-03 / AC-006: sensor INACTIVO → SENSOR_INACTIVE y no persiste")
    void af03_sensorInactivo() {
        H h = newH(INACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_INACTIVO, "5.0")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(RechazoLecturaException.class);
                    assertThat(((RechazoLecturaException) err).motivo)
                            .isEqualTo(RechazoLecturaException.SENSOR_INACTIVE);
                })
                .verify();
        assertThat(h.store().filas).isEmpty();
    }

    @Test
    @DisplayName("AF-05 / AC-008: timestamp fuera de ventana → TIMESTAMP_OUT_OF_WINDOW")
    void af05_ventana() {
        H h = newH(ACTIVO);
        Instant viejo = Instant.now().minusSeconds(3600);
        LecturaEntrada l = new LecturaEntrada(ID_A, viejo, new BigDecimal("5.0"), "METROS");
        StepVerifier.create(h.ingestor().procesar(l))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(RechazoLecturaException.class);
                    assertThat(((RechazoLecturaException) err).motivo)
                            .isEqualTo(RechazoLecturaException.TIMESTAMP_OUT_OF_WINDOW);
                })
                .verify();
    }

    @Test
    @DisplayName("AF-01 / BR-001 / AC-004: payload invalido → PAYLOAD_INVALID (parseo)")
    void af01_payloadInvalido() {
        String valido = "{\"sensorId\":\"00000000-0000-4000-8000-00000000000a\",\"timestamp\":\"2026-09-09T12:00:00Z\","
                + "\"valor\":5.1,\"unidadMedida\":\"METROS\"}";
        assertThat(LecturasRabbitConsumer.parseLectura(valido.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .satisfies(l -> {
                    assertThat(l.sensorId()).isEqualTo(ID_A);
                    assertThat(l.valor()).isEqualByComparingTo("5.1");
                });

        String[] invalidos = {
                "{\"timestamp\":\"2026-09-09T12:00:00Z\",\"valor\":5.1,\"unidadMedida\":\"METROS\"}",
                "no-json",
                "{\"sensorId\":\"no-uuid\",\"timestamp\":\"2026-09-09T12:00:00Z\",\"valor\":5.1,\"unidadMedida\":\"METROS\"}"
        };
        for (String bad : invalidos) {
            try {
                LecturasRabbitConsumer.parseLectura(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThat(false).as("deberia rechazar: " + bad).isTrue();
            } catch (RechazoLecturaException e) {
                assertThat(e.motivo).isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
            }
        }
    }
}
