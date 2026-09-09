package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.in.UpdateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.application.service.UpdateSensorService;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.InvalidEstadoException;
import com.wrsensor.sensorregistry.domain.model.InvalidFrecuenciaException;
import com.wrsensor.sensorregistry.domain.model.InvalidHisteresisException;
import com.wrsensor.sensorregistry.domain.model.InvalidRangesException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorNotFoundException;
import com.wrsensor.sensorregistry.domain.model.TipoSensor;
import com.wrsensor.sensorregistry.domain.model.UnidadMedida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0004 BR-002/BR-006/BR-007/BR-008 y AC-001/004/006/007/008
 * (capa de aplicacion con fakes). Test IDs: unit-test:FEAT-0004-br00X,
 * assertion:FEAT-0004-acXXX.
 */
class UpdateSensorServiceTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static Sensor sensorActual(EstadoSensor estado) {
        return new Sensor(
                ID, "S-DET-A", "seed", TipoSensor.RIO, new BigDecimal("-29.15"), new BigDecimal("-59.65"),
                UnidadMedida.METROS, estado, new BigDecimal("0.5"), 60,
                Instant.parse("2024-05-01T00:00:00Z"),
                new Rango(new BigDecimal("4"), new BigDecimal("6")),
                new Rango(new BigDecimal("2"), new BigDecimal("8")),
                new Rango(new BigDecimal("0"), new BigDecimal("10")));
    }

    private static final Rango NORMAL = new Rango(new BigDecimal("3"), new BigDecimal("7"));
    private static final Rango WARNING = new Rango(new BigDecimal("1"), new BigDecimal("9"));
    private static final Rango CRITICAL = new Rango(new BigDecimal("0"), new BigDecimal("10"));

    private static UpdateSensorUseCase.UpdateSensorCommand cmd(String estado) {
        return new UpdateSensorUseCase.UpdateSensorCommand(
                estado, new BigDecimal("1.0"), 120, NORMAL, WARNING, CRITICAL);
    }

    /** Fakes: port de busqueda y port de update (captura el sensor recibido). */
    private record Harness(FakeFind find, FakeUpdate update, UpdateSensorService svc) {
        static Harness of(Sensor actual) {
            FakeFind find = new FakeFind();
            find.value = actual;
            FakeUpdate update = new FakeUpdate();
            return new Harness(find, update, new UpdateSensorService(find, update));
        }
    }

    private static final class FakeFind implements FindSensorByIdPort {
        Sensor value;

        @Override
        public Mono<Sensor> findById(UUID id) {
            return value == null ? Mono.empty() : Mono.just(value);
        }
    }

    private static final class FakeUpdate implements UpdateSensorPort {
        Sensor updated;

        @Override
        public Mono<Sensor> update(Sensor s) {
            this.updated = s;
            return Mono.just(s);
        }
    }

    @Test
    @DisplayName("AC-001 / BR-008: update valido cambia la config y conserva la identidad")
    void testAC001_updateValidoConservaIdentidad() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));

        StepVerifier.create(h.svc().update(ID, cmd("ACTIVO")))
                .assertNext(s -> {
                    assertThat(s.histeresis()).isEqualByComparingTo("1.0");
                    assertThat(s.frecuenciaReporteSegundos()).isEqualTo(120);
                    assertThat(s.estado()).isEqualTo(EstadoSensor.ACTIVO);
                    assertThat(s.rangoNormal()).isEqualTo(NORMAL);
                })
                .verifyComplete();

        // BR-008/BR-004: el port recibe el sensor con identidad intacta y solo config nueva.
        Sensor updated = h.update().updated;
        assertThat(updated).isNotNull();
        assertThat(updated.id()).isEqualTo(ID);
        assertThat(updated.codigo()).isEqualTo("S-DET-A");
        assertThat(updated.nombre()).isEqualTo("seed");
        assertThat(updated.tipo()).isEqualTo(TipoSensor.RIO);
        assertThat(updated.latitud()).isEqualByComparingTo("-29.15");
        assertThat(updated.fechaInstalacion()).isEqualTo(sensorActual(EstadoSensor.ACTIVO).fechaInstalacion());
    }

    @Test
    @DisplayName("BR-002 / AF-04 / AC-004: sensor inexistente → SensorNotFoundException")
    void testBR002_sensorInexistente() {
        Harness h = Harness.of(null);
        StepVerifier.create(h.svc().update(ID, cmd("ACTIVO")))
                .expectError(SensorNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("BR-006 / AC-006: histeresis negativa → InvalidHisteresisException")
    void testAC006_histeresisNegativa() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));
        var cmd = new UpdateSensorUseCase.UpdateSensorCommand("ACTIVO", new BigDecimal("-0.5"), 120,
                NORMAL, WARNING, CRITICAL);
        StepVerifier.create(h.svc().update(ID, cmd))
                .expectError(InvalidHisteresisException.class)
                .verify();
    }

    @Test
    @DisplayName("BR-006 / AC-007: rangos que rompen la cadena inclusiva → InvalidRangesException")
    void testAC007_rangosInvalidos() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));
        var cmd = new UpdateSensorUseCase.UpdateSensorCommand("ACTIVO", new BigDecimal("1.0"), 120,
                NORMAL, WARNING, new Rango(new BigDecimal("5"), new BigDecimal("9"))); // critical.min > warning.min
        StepVerifier.create(h.svc().update(ID, cmd))
                .expectError(InvalidRangesException.class)
                .verify();
    }

    @Test
    @DisplayName("BR-007 / AC-008: estado INACTIVO en PUT → InvalidEstadoException")
    void testAC008_estadoInactivoRechazado() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));
        StepVerifier.create(h.svc().update(ID, cmd("INACTIVO")))
                .expectError(InvalidEstadoException.class)
                .verify();
    }

    @Test
    @DisplayName("BR-007: estado fuera del enum (o MANTENIMIENTO invalido inexistente) → InvalidEstadoException")
    void testBR007_estadoInvalido() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));
        StepVerifier.create(h.svc().update(ID, cmd("ACTIVO_TRANQUILO")))
                .expectError(InvalidEstadoException.class)
                .verify();
        // MANTENIMIENTO sí es editable.
        StepVerifier.create(h.svc().update(ID, cmd("MANTENIMIENTO")))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-006: frecuencia invalida (0) → InvalidFrecuenciaException")
    void testBR006_frecuenciaInvalida() {
        Harness h = Harness.of(sensorActual(EstadoSensor.ACTIVO));
        var cmd = new UpdateSensorUseCase.UpdateSensorCommand("ACTIVO", new BigDecimal("1.0"), 0,
                NORMAL, WARNING, CRITICAL);
        StepVerifier.create(h.svc().update(ID, cmd))
                .expectError(InvalidFrecuenciaException.class)
                .verify();
    }
}
