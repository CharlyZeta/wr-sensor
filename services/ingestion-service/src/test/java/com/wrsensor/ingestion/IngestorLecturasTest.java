package com.wrsensor.ingestion;

import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.application.service.ClaveIdempotencia;
import com.wrsensor.ingestion.application.service.IngestorLecturas;
import com.wrsensor.ingestion.domain.Calidad;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;
import com.wrsensor.ingestion.infrastructure.adapter.in.messaging.LecturasRabbitConsumer;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0011 (severidad/persistencia) + FIX-0003 (outbox e idempotencia)
 * con fakes del port transaccional.
 * Cubre AC-001..003 (FEAT-0011), AF-01..05, y FIX-0003 BR-001/BR-002/BR-004/BR-010, AC-001.
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
            new IngestionProperties.Registry("http://x", new IngestionProperties.Registry.Auth("a", "b"), null, null, null, null),
            new IngestionProperties.Lecturas("sensor.lecturas", "dlq", "dlx"),
            new IngestionProperties.Alertas("sensor.alertas"),
            new IngestionProperties.Messaging(3, "dlx"),
            new IngestionProperties.Outbox(null, null, null, null, null, null, null),
            new IngestionProperties.RangoFisico(
                    java.util.Map.of(
                            "METROS", new IngestionProperties.RangoFisico.Rango(new BigDecimal("-1.0"), new BigDecimal("15.0")),
                            "CENTIMETROS", new IngestionProperties.RangoFisico.Rango(new BigDecimal("-100"), new BigDecimal("1500"))),
                    java.util.Map.of("SALADO-SANJUSTO", new IngestionProperties.RangoFisico.Rango(new BigDecimal("0.0"), new BigDecimal("8.0")))),
            new IngestionProperties.Particiones(4, null, "sensor.lecturas.part", "queue.sensor.lecturas.p{i}"),
            new IngestionProperties.Schema(null, null));

    private static final class FakeSensores implements SensorConfigPort {
        SensorInfo found = ACTIVO;

        @Override
        public Mono<SensorInfo> findById(UUID sensorId) {
            return found != null && sensorId.equals(found.id()) ? Mono.just(found) : Mono.empty();
        }
    }

    /** Fake transaccional: registra lo pedido y permite simular duplicado. */
    private static final class FakeTxStore implements IngestaTransaccionalPort {
        boolean duplicada;
        final List<LecturaPersistida> filas = new ArrayList<>();
        final List<OutboxAlerta> outboxes = new ArrayList<>();
        final List<String> claves = new ArrayList<>();

        @Override
        public Mono<Resultado> persistir(LecturaPersistida lectura, String clave, OutboxAlerta outbox) {
            claves.add(clave);
            if (duplicada) {
                return Mono.just(new Resultado(false));
            }
            filas.add(lectura);
            if (outbox != null) outboxes.add(outbox);
            return Mono.just(new Resultado(true));
        }
    }

    private record H(IngestorLecturas ingestor, FakeTxStore store) {}

    private static H newH(SensorInfo sensor) {
        FakeSensores sensores = new FakeSensores();
        sensores.found = sensor;
        FakeTxStore store = new FakeTxStore();
        return new H(new IngestorLecturas(sensores, store, PROPS), store);
    }

    private static LecturaEntrada lectura(UUID id, String valor) {
        return new LecturaEntrada(id, Instant.now(), new BigDecimal(valor), "METROS");
    }

    // ===== FEAT-0011 =====

    @Test
    @DisplayName("AC-001 (FEAT-0011): primera lectura NORMAL se persiste y NO encola outbox")
    void ac001_normalSinOutbox() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "5.0")))
                .assertNext(r -> {
                    assertThat(r.eventoEncolado()).isFalse();
                    assertThat(r.duplicado()).isFalse();
                })
                .verifyComplete();
        assertThat(h.store().filas).hasSize(1);
        assertThat(h.store().filas.get(0).severidad()).isEqualTo(Severidad.NORMAL);
        assertThat(h.store().outboxes).isEmpty();
    }

    @Test
    @DisplayName("AC-002 (FEAT-0011) / BR-004 (FIX-0003): cambio NORMAL→WARNING persiste y encola outbox (sin publicar)")
    void ac002_cambioEncolaOutbox() {
        H h = newH(ACTIVO);
        h.ingestor().procesar(lectura(ID_A, "5.0")).block(); // NORMAL previo

        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "7.0")))
                .assertNext(r -> assertThat(r.eventoEncolado()).isTrue())
                .verifyComplete();
        assertThat(h.store().outboxes).hasSize(1);
        IngestaTransaccionalPort.OutboxAlerta ob = h.store().outboxes.get(0);
        assertThat(ob.sensorId()).isEqualTo(ID_A);
        assertThat(ob.routingKey()).isEqualTo("alerta.warning");
        assertThat(ob.payload())
                .contains("\"severidadAnterior\":\"NORMAL\"")
                .contains("\"severidadNueva\":\"WARNING\"")
                .contains("\"valorLectura\":7.0");
    }

    @Test
    @DisplayName("AC-003 (FEAT-0011): cambio a CRITICAL encola con routing key alerta.critical")
    void ac003_cambioCritical() {
        H h = newH(ACTIVO);
        h.ingestor().setUltimaSeveridad(ID_A, Severidad.WARNING);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "9.0")))
                .assertNext(r -> assertThat(r.eventoEncolado()).isTrue())
                .verifyComplete();
        assertThat(h.store().outboxes.get(0).routingKey()).isEqualTo("alerta.critical");
        assertThat(h.store().filas.get(0).severidad()).isEqualTo(Severidad.CRITICAL);
    }

    // ===== FIX-0003 =====

    @Test
    @DisplayName("FIX-0003 AC-001 / BR-002: redelivery (clave ya procesada) → duplicado, sin outbox ni persistencia")
    void fix0003_ac001_redelivery() {
        H h = newH(ACTIVO);
        h.store().duplicada = true;
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "7.0")))
                .assertNext(r -> {
                    assertThat(r.duplicado()).isTrue();
                    assertThat(r.eventoEncolado()).isFalse();
                })
                .verifyComplete();
        assertThat(h.store().filas).isEmpty();
        assertThat(h.store().outboxes).isEmpty();
    }

    @Test
    @DisplayName("FIX-0003 BR-001: clave = eventId si viene; si no, natural (sensorId, timestamp)")
    void fix0003_br001_clave() {
        Instant ts = Instant.parse("2026-09-10T12:00:00Z");
        LecturaEntrada conEvento = new LecturaEntrada(ID_A, ts, new BigDecimal("5.0"), "METROS", "evt-123");
        LecturaEntrada sinEvento = new LecturaEntrada(ID_A, ts, new BigDecimal("5.0"), "METROS");

        assertThat(ClaveIdempotencia.de(conEvento)).isEqualTo("evt:evt-123");
        assertThat(ClaveIdempotencia.de(sinEvento)).isEqualTo("nat:" + ID_A + ":" + ts.toEpochMilli());
    }

    @Test
    @DisplayName("FIX-0003 BR-010: el parser acepta payload actual (sin eventId) y también con eventId")
    void fix0003_br010_payloadCompatibilidad() {
        String json = "{\"sensorId\":\"" + ID_A + "\",\"timestamp\":\"2026-09-10T12:00:00Z\","
                + "\"valor\":5.1,\"unidadMedida\":\"METROS\"}";
        LecturaEntrada l = LecturasRabbitConsumer.parseLectura(json.getBytes(StandardCharsets.UTF_8));
        assertThat(l.eventId()).isNull();
        assertThat(ClaveIdempotencia.de(l)).startsWith("nat:");

        String conEvento = json.replace("{\"sensorId\"", "{\"eventId\":\"abc-1\",\"sensorId\"");
        assertThat(LecturasRabbitConsumer.parseLectura(conEvento.getBytes(StandardCharsets.UTF_8)).eventId())
                .isEqualTo("abc-1");
    }

    // ===== FIX-0004: rango fisico y calidad del dato =====

    @Test
    @DisplayName("FIX-0004 AC-001: valor dentro del rango fisico -> calidad OK y severidad evaluada")
    void fix0004_ac001_dentroDeRango() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "5.0")))
                .assertNext(r -> {
                    assertThat(r.calidad()).isEqualTo(Calidad.OK);
                    assertThat(r.severidad()).isEqualTo(Severidad.NORMAL); // dentro de rangoNormal 4..6
                })
                .verifyComplete();
        assertThat(h.store().filas.get(0).calidad()).isEqualTo(Calidad.OK);
    }

    @Test
    @DisplayName("FIX-0004 AC-002 / BR-003: valor fuera de rango fisico -> ERROR_SENSOR, severidad null y sin outbox")
    void fix0004_ac002_errorSensor() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "-50.0")))
                .assertNext(r -> {
                    assertThat(r.calidad()).isEqualTo(Calidad.ERROR_SENSOR);
                    assertThat(r.severidad()).isNull();
                    assertThat(r.eventoEncolado()).isFalse();
                })
                .verifyComplete();
        assertThat(h.store().filas).hasSize(1);
        assertThat(h.store().filas.get(0).severidad()).as("no evaluada").isNull();
        assertThat(h.store().outboxes).as("nunca encola alerta").isEmpty();
    }

    @Test
    @DisplayName("FIX-0004 AC-003 / BR-005: la lectura ERROR_SENSOR no altera la ultima severidad conocida")
    void fix0004_ac003_estadoNoAlterado() {
        H h = newH(ACTIVO);
        h.ingestor().procesar(lectura(ID_A, "5.0")).block();
        h.ingestor().procesar(lectura(ID_A, "-50.0")).block();

        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "7.0")))
                .assertNext(r -> assertThat(r.eventoEncolado())
                        .as("se evalua contra NORMAL -> WARNING").isTrue())
                .verifyComplete();
        assertThat(h.store().outboxes).hasSize(1);
        assertThat(h.store().outboxes.get(0).payload()).contains("\"severidadAnterior\":\"NORMAL\"");
    }

    @Test
    @DisplayName("FIX-0004 AC-004 / BR-002: el override por codigo es mas restrictivo que el global")
    void fix0004_ac004_override() {
        SensorInfo conOverride = new SensorInfo(ID_A, "SALADO-SANJUSTO", "ACTIVO", "METROS",
                new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
                new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
                new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10")));
        H h = newH(conOverride);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "9.5")))
                .assertNext(r -> assertThat(r.calidad()).isEqualTo(Calidad.ERROR_SENSOR))
                .verifyComplete();
    }

    @Test
    @DisplayName("FIX-0004 AC-005 / BR-006: sensor en CENTIMETROS usa el rango de centimetros")
    void fix0004_ac005_centimetros() {
        SensorInfo cm = new SensorInfo(ID_A, "PARANA-RECONQUISTA", "ACTIVO", "CENTIMETROS",
                new SensorInfo.Rango(new BigDecimal("400"), new BigDecimal("600")),
                new SensorInfo.Rango(new BigDecimal("200"), new BigDecimal("800")),
                new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("1000")));
        H h = newH(cm);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_A, "340")))
                .assertNext(r -> assertThat(r.calidad()).isEqualTo(Calidad.OK))
                .verifyComplete();
    }

    @Test
    @DisplayName("FIX-0004 AC-006 / BR-007: marca de calidad del emisor excluye aunque el valor sea plausible")
    void fix0004_ac006_calidadEmisor() {
        H h = newH(ACTIVO);
        LecturaEntrada marcada = new LecturaEntrada(ID_A, Instant.now(), new BigDecimal("5.0"),
                "METROS", null, "ERROR_SENSOR");
        StepVerifier.create(h.ingestor().procesar(marcada))
                .assertNext(r -> {
                    assertThat(r.calidad()).isEqualTo(Calidad.ERROR_SENSOR);
                    assertThat(r.severidad()).isNull();
                    assertThat(r.eventoEncolado()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("FIX-0004 AC-007 / BR-008: se emite log WARN con el sensorId y el motivo")
    void fix0004_ac007_logWarn() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IngestorLecturas.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            H h = newH(ACTIVO);
            h.ingestor().procesar(lectura(ID_A, "-50.0")).block();
            assertThat(appender.list)
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains(ID_A.toString())
                            && e.getFormattedMessage().contains("ERROR_SENSOR"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ===== AF (FEAT-0011) =====

    @Test
    @DisplayName("AF-02 / AC-005: sensor desconocido → SENSOR_UNKNOWN y no persiste")
    void af02_sensorDesconocido() {
        H h = newH(ACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(UUID.randomUUID(), "5.0")))
                .expectErrorSatisfies(err -> assertThat(((RechazoLecturaException) err).motivo)
                        .isEqualTo(RechazoLecturaException.SENSOR_UNKNOWN))
                .verify();
        assertThat(h.store().filas).isEmpty();
    }

    @Test
    @DisplayName("AF-03 / AC-006: sensor INACTIVO → SENSOR_INACTIVE y no persiste")
    void af03_sensorInactivo() {
        H h = newH(INACTIVO);
        StepVerifier.create(h.ingestor().procesar(lectura(ID_INACTIVO, "5.0")))
                .expectErrorSatisfies(err -> assertThat(((RechazoLecturaException) err).motivo)
                        .isEqualTo(RechazoLecturaException.SENSOR_INACTIVE))
                .verify();
        assertThat(h.store().filas).isEmpty();
    }

    @Test
    @DisplayName("AF-05 / AC-008: timestamp fuera de ventana → TIMESTAMP_OUT_OF_WINDOW")
    void af05_ventana() {
        H h = newH(ACTIVO);
        LecturaEntrada vieja = new LecturaEntrada(ID_A, Instant.now().minusSeconds(3600),
                new BigDecimal("5.0"), "METROS");
        StepVerifier.create(h.ingestor().procesar(vieja))
                .expectErrorSatisfies(err -> assertThat(((RechazoLecturaException) err).motivo)
                        .isEqualTo(RechazoLecturaException.TIMESTAMP_OUT_OF_WINDOW))
                .verify();
    }

    @Test
    @DisplayName("AF-01 / BR-001 (FEAT-0011): payload invalido → PAYLOAD_INVALID (parseo)")
    void af01_payloadInvalido() {
        String valido = "{\"sensorId\":\"" + ID_A + "\",\"timestamp\":\"2026-09-10T12:00:00Z\","
                + "\"valor\":5.1,\"unidadMedida\":\"METROS\"}";
        assertThat(LecturasRabbitConsumer.parseLectura(valido.getBytes(StandardCharsets.UTF_8)).valor())
                .isEqualByComparingTo("5.1");

        String[] invalidos = {
                "{\"timestamp\":\"2026-09-10T12:00:00Z\",\"valor\":5.1,\"unidadMedida\":\"METROS\"}",
                "no-json",
                "{\"sensorId\":\"no-uuid\",\"timestamp\":\"2026-09-10T12:00:00Z\",\"valor\":5.1,\"unidadMedida\":\"METROS\"}"
        };
        for (String bad : invalidos) {
            try {
                LecturasRabbitConsumer.parseLectura(bad.getBytes(StandardCharsets.UTF_8));
                assertThat(false).as("deberia rechazar: " + bad).isTrue();
            } catch (RechazoLecturaException e) {
                assertThat(e.motivo).isEqualTo(RechazoLecturaException.PAYLOAD_INVALID);
            }
        }
    }
}



