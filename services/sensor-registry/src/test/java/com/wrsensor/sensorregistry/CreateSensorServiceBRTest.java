package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.in.CreateSensorUseCase.CreateSensorCommand;
import com.wrsensor.sensorregistry.application.port.out.ContainsSensorWithCodePort;
import com.wrsensor.sensorregistry.application.port.out.PublishSensorCreatedPort;
import com.wrsensor.sensorregistry.application.port.out.SaveSensorPort;
import com.wrsensor.sensorregistry.application.service.CreateSensorService;
import com.wrsensor.sensorregistry.domain.model.InvalidCoordinatesException;
import com.wrsensor.sensorregistry.domain.model.InvalidEstadoException;
import com.wrsensor.sensorregistry.domain.model.InvalidFrecuenciaException;
import com.wrsensor.sensorregistry.domain.model.InvalidHisteresisException;
import com.wrsensor.sensorregistry.domain.model.InvalidRangesException;
import com.wrsensor.sensorregistry.domain.model.InvalidTipoException;
import com.wrsensor.sensorregistry.domain.model.InvalidUnidadException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorCodeDuplicatedException;
import com.wrsensor.sensorregistry.domain.model.SensorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — BR-001..BR-009 (FEAT-0001). Test ID: unit-test:FEAT-0001-brXXX.
 *
 * <p>Prueban el dominio/aplicacion (no Spring, no Testcontainers): instancian
 * {@link CreateSensorService} directamente con fakes de los ports
 * ({@link ContainsSensorWithCodePort}, {@link SaveSensorPort},
 * {@link PublishSensorCreatedPort}) asertando el {@link Mono} con
 * {@link StepVerifier}. Cero {@code .block()} en el test — el SUT ya es reactivo
 * y los fakes son sincronicos puros ({@code Mono.just}/{@code Mono.empty}).
 *
 * <p>Datos validos base = AC-001 (critical 0-10, warning 2-8, normal 4-6);
 * cada BR-XXX muta solo el campo que gobierna el criterio.
 */
class CreateSensorServiceBRTest {

    // --- Fakes ---
    private static final ContainsSensorWithCodePort EXISTS_TRUE = codigo -> Mono.just(true);
    private static final ContainsSensorWithCodePort EXISTS_FALSE = codigo -> Mono.just(false);
    private static final SaveSensorPort SAVE_IDENTITY = sensor -> Mono.just(sensor);
    private static final PublishSensorCreatedPort PUBLISH_NOOP = sensor -> Mono.empty();

    // --- Helpers ---
    private static BigDecimal d(String s) { return new BigDecimal(s); }
    private static Rango r(String min, String max) { return new Rango(d(min), d(max)); }

    /** Comando base con los datos validos de AC-001. */
    private static CreateSensorCommand validCommand() {
        return new CreateSensorCommand(
                "PARANA-RECONQUISTA", "Reconquista", "RIO",
                d("-29.15"), d("-59.65"), "METROS", "ACTIVO",
                d("0.5"), 60,
                r("4", "6"), r("2", "8"), r("0", "10"));
    }

    // with* helpers para mutar un solo campo del base.
    private static CreateSensorCommand withLat(CreateSensorCommand c, BigDecimal v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), v, c.longitud(),
                c.unidadMedida(), c.estado(), c.histeresis(), c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withLon(CreateSensorCommand c, BigDecimal v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), v,
                c.unidadMedida(), c.estado(), c.histeresis(), c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withHisteresis(CreateSensorCommand c, BigDecimal v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), c.longitud(),
                c.unidadMedida(), c.estado(), v, c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withFrecuencia(CreateSensorCommand c, int v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), c.longitud(),
                c.unidadMedida(), c.estado(), c.histeresis(), v,
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withEstado(CreateSensorCommand c, String v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), c.longitud(),
                c.unidadMedida(), v, c.histeresis(), c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withUnidad(CreateSensorCommand c, String v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), c.longitud(),
                v, c.estado(), c.histeresis(), c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withTipo(CreateSensorCommand c, String v) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), v, c.latitud(), c.longitud(),
                c.unidadMedida(), c.estado(), c.histeresis(), c.frecuenciaReporteSegundos(),
                c.rangoNormal(), c.rangoWarning(), c.rangoCritical());
    }
    private static CreateSensorCommand withRanges(CreateSensorCommand c, Rango n, Rango w, Rango k) {
        return new CreateSensorCommand(c.codigo(), c.nombre(), c.tipo(), c.latitud(), c.longitud(),
                c.unidadMedida(), c.estado(), c.histeresis(), c.frecuenciaReporteSegundos(),
                n, w, k);
    }

    // ===================== BR-001 =====================
    @Test
    @DisplayName("BR-001: codigo ya existe (ContainsSensorWithCodePort.existsByCodigo=true) "
            + "→ SensorCodeDuplicatedException code=SENSOR_CODE_DUPLICATED")
    void testBR001_codigoDuplicado_lanzaSensorCodeDuplicated() {
        var svc = new CreateSensorService(EXISTS_TRUE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(validCommand()))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(SensorCodeDuplicatedException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_CODE_DUPLICATED");
                });
    }

    @Test
    @DisplayName("BR-001: codigo unico (existsByCodigo=false) → no lanza (pasa al save/publish)")
    void testBR001_codigoUnico_noLanza() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(validCommand()))
                .expectNextMatches(sensor -> "PARANA-RECONQUISTA".equals(sensor.codigo()))
                .verifyComplete();
    }

    // ===================== BR-002 =====================
    @Test
    @DisplayName("BR-002 (valido): anidamiento inclusivo OK con datos AC-001 "
            + "(critical.min=0 < warning.max=8) → pasa (no falso rechazo)")
    void testBR002_anidamientoValido_pasa() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        // critical 0-10, warning 2-8, normal 4-6 => 0<=2<=4<=6<=8<=10 OK.
        StepVerifier.create(svc.createSensor(validCommand()))
                .expectNextMatches(s -> "PARANA-RECONQUISTA".equals(s.codigo()))
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-002 (invalido): AC-003 data (crit 5-9, warn 2-6, norm 3-5) "
            + "rompe critical.min(5) > warning.min(2) → InvalidRangesException code=SENSOR_INVALID_RANGES")
    void testBR002_anidamientoViolado_lanzaInvalidRanges() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        CreateSensorCommand cmd = withRanges(validCommand(),
                r("3", "5"),  // normal
                r("2", "6"),  // warning
                r("5", "9")); // critical — min=5 viola critical.min <= warning.min(2).

        StepVerifier.create(svc.createSensor(cmd))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidRangesException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_RANGES");
                });
    }

    // ===================== BR-003 =====================
    @Test
    @DisplayName("BR-003: latitud fuera [-90,90] (AC-004 lat=95) "
            + "→ InvalidCoordinatesException code=SENSOR_INVALID_COORDINATES")
    void testBR003_latitudFueraDeRango_lanzaInvalidCoordinates() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withLat(validCommand(), d("95"))))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidCoordinatesException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_COORDINATES");
                });
    }

    // ===================== BR-004 =====================
    @Test
    @DisplayName("BR-004: longitud fuera [-180,180] (AC-005 lon=200) "
            + "→ InvalidCoordinatesException code=SENSOR_INVALID_COORDINATES")
    void testBR004_longitudFueraDeRango_lanzaInvalidCoordinates() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withLon(validCommand(), d("200"))))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidCoordinatesException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_COORDINATES");
                });
    }

    // ===================== BR-005 =====================
    @Test
    @DisplayName("BR-005: histeresis negativa (AC-008 h=-0.5) "
            + "→ InvalidHisteresisException code=SENSOR_INVALID_HISTERESIS")
    void testBR005_histeresisNegativa_lanzaInvalidHisteresis() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withHisteresis(validCommand(), d("-0.5"))))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidHisteresisException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_HISTERESIS");
                });
    }

    @Test
    @DisplayName("BR-005 (limite): histeresis=0 es VALIDA (Ambiguity Log resuelto: cero desactiva el margen)")
    void testBR005_histeresisCero_esValida() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withHisteresis(validCommand(), d("0"))))
                .expectNextMatches(s -> "PARANA-RECONQUISTA".equals(s.codigo()))
                .verifyComplete();
    }

    // ===================== BR-006 =====================
    @Test
    @DisplayName("BR-006: frecuenciaReporteSegundos <= 0 (AC-009 f=0) "
            + "→ InvalidFrecuenciaException code=SENSOR_INVALID_FRECUENCIA")
    void testBR006_frecuenciaCero_lanzaInvalidFrecuencia() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withFrecuencia(validCommand(), 0)))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidFrecuenciaException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_FRECUENCIA");
                });
    }

    // ===================== BR-007 =====================
    @Test
    @DisplayName("BR-007: estado fuera {ACTIVO, INACTIVO, MANTENIMIENTO} (AC-010 DESCONOCIDO) "
            + "→ InvalidEstadoException code=SENSOR_INVALID_ESTADO")
    void testBR007_estadoInvalido_lanzaInvalidEstado() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withEstado(validCommand(), "DESCONOCIDO")))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidEstadoException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_ESTADO");
                });
    }

    // ===================== BR-008 =====================
    @Test
    @DisplayName("BR-008: unidadMedida fuera {METROS, CENTIMETROS} (AC-011 PULGADAS) "
            + "→ InvalidUnidadException code=SENSOR_INVALID_UNIDAD")
    void testBR008_unidadInvalida_lanzaInvalidUnidad() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withUnidad(validCommand(), "PULGADAS")))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidUnidadException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_UNIDAD");
                });
    }

    // ===================== BR-009 =====================
    @Test
    @DisplayName("BR-009: tipo fuera {RIO, ARROYO, BAÑADO} (AC-012 LAGO) "
            + "→ InvalidTipoException code=SENSOR_INVALID_TIPO")
    void testBR009_tipoInvalido_lanzaInvalidTipo() {
        var svc = new CreateSensorService(EXISTS_FALSE, SAVE_IDENTITY, PUBLISH_NOOP);

        StepVerifier.create(svc.createSensor(withTipo(validCommand(), "LAGO")))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidTipoException.class);
                    assertThat(((SensorException) err).code())
                            .isEqualTo("SENSOR_INVALID_TIPO");
                });
    }
}
