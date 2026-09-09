package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.in.DeactivateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.application.service.DeactivateSensorService;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
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
 * Unit tests — FEAT-0005 BR-002/BR-004/BR-005 y AC-001/004/006 (capa de aplicacion
 * con fakes). Test IDs: unit-test:FEAT-0005-br00X, assertion:FEAT-0005-acXXX.
 */
class DeactivateSensorServiceTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static Sensor sensor(EstadoSensor estado) {
        return new Sensor(
                ID, "S-DET-A", "seed", TipoSensor.RIO, new BigDecimal("-29.15"), new BigDecimal("-59.65"),
                UnidadMedida.METROS, estado, new BigDecimal("0.5"), 60,
                Instant.parse("2024-05-01T00:00:00Z"),
                new Rango(new BigDecimal("4"), new BigDecimal("6")),
                new Rango(new BigDecimal("2"), new BigDecimal("8")),
                new Rango(new BigDecimal("0"), new BigDecimal("10")));
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
        int calls;

        @Override
        public Mono<Sensor> update(Sensor s) {
            calls++;
            this.updated = s;
            return Mono.just(s);
        }
    }

    @Test
    @DisplayName("AC-001 / BR-004: sensor ACTIVO → deactivate persiste estado INACTIVO (UPDATE, identidad intacta)")
    void testAC001_bajaLogica() {
        FakeFind find = new FakeFind();
        find.value = sensor(EstadoSensor.ACTIVO);
        FakeUpdate update = new FakeUpdate();
        DeactivateSensorUseCase svc = new DeactivateSensorService(find, update);

        StepVerifier.create(svc.deactivate(ID)).verifyComplete();

        assertThat(update.calls).isEqualTo(1);
        assertThat(update.updated).isNotNull();
        assertThat(update.updated.estado()).isEqualTo(EstadoSensor.INACTIVO);
        // BR-004: la fila nunca se borra; identidad/config intactas.
        assertThat(update.updated.id()).isEqualTo(ID);
        assertThat(update.updated.codigo()).isEqualTo("S-DET-A");
        assertThat(update.updated.histeresis()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("BR-005 / AF-05 / AC-006: sensor ya INACTIVO → no-op (sin UPDATE, completa)")
    void testAC006_reBajaIdempotente() {
        FakeFind find = new FakeFind();
        find.value = sensor(EstadoSensor.INACTIVO);
        FakeUpdate update = new FakeUpdate();
        DeactivateSensorUseCase svc = new DeactivateSensorService(find, update);

        StepVerifier.create(svc.deactivate(ID)).verifyComplete();
        assertThat(update.calls).isZero();
    }

    @Test
    @DisplayName("BR-002 / AF-04 / AC-004: sensor inexistente → SensorNotFoundException")
    void testBR002_inexistente() {
        FakeFind find = new FakeFind(); // value == null
        FakeUpdate update = new FakeUpdate();
        DeactivateSensorUseCase svc = new DeactivateSensorService(find, update);

        StepVerifier.create(svc.deactivate(ID))
                .expectError(SensorNotFoundException.class)
                .verify();
        assertThat(update.calls).isZero();
    }
}
