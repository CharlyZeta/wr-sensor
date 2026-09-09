package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.in.GetSensorDetailUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.service.GetSensorDetailService;
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
 * Unit tests — FEAT-0003 BR-002, BR-005, BR-006 (capa de aplicacion con fake del
 * port out). Test IDs: unit-test:FEAT-0003-br002/br005/br006.
 *
 * <p>BR-001 (parseo de id), BR-003/BR-004 (auth) y el mapeo HTTP viven en el
 * adapter web / GlobalErrorHandler: BR-001 y AC-006 se verifican end-to-end en
 * {@code FEAT0003MainFlowIT}; BR-003/BR-004 y AC-003/AC-004 usan el mismo
 * {@code RolGuard.requireReader} de FEAT-0002 (cubierto por {@code ListSensorsAuthTest}
 * y re-verificado e2e en {@code FEAT0003MainFlowIT}).
 */
class GetSensorDetailServiceTest {

    /** Fake del port out: emite el sensor configurado o vacio. */
    private static final class FakeFindPort implements FindSensorByIdPort {
        Sensor value; // null → no encontrado

        @Override
        public Mono<Sensor> findById(UUID id) {
            return value == null ? Mono.empty() : Mono.just(value);
        }
    }

    private static Sensor sensorConEstado(EstadoSensor estado) {
        return new Sensor(
                UUID.fromString("00000000-0000-4000-8000-00000000000a"), "S-DET-A", "seed",
                TipoSensor.RIO, new BigDecimal("-29.15"), new BigDecimal("-59.65"),
                UnidadMedida.METROS, estado, new BigDecimal("0.5"), 60,
                Instant.parse("2024-05-01T00:00:00Z"),
                new Rango(new BigDecimal("4"), new BigDecimal("6")),
                new Rango(new BigDecimal("2"), new BigDecimal("8")),
                new Rango(new BigDecimal("0"), new BigDecimal("10")));
    }

    @Test
    @DisplayName("BR-002 / AF-03: id UUID valido inexistente → SensorNotFoundException (SENSOR_NOT_FOUND)")
    void testBR002_inexistenteSensorNotFound() {
        FakeFindPort port = new FakeFindPort(); // value == null
        GetSensorDetailUseCase svc = new GetSensorDetailService(port);

        StepVerifier.create(svc.getById(UUID.randomUUID()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(SensorNotFoundException.class);
                    assertThat(((SensorNotFoundException) err).code()).isEqualTo("SENSOR_NOT_FOUND");
                })
                .verify();
    }

    @Test
    @DisplayName("BR-005: el detalle devuelve el sensor completo tal como lo entrega el port")
    void testBR005_devuelveSensorCompleto() {
        FakeFindPort port = new FakeFindPort();
        Sensor esperado = sensorConEstado(EstadoSensor.ACTIVO);
        port.value = esperado;
        GetSensorDetailUseCase svc = new GetSensorDetailService(port);

        StepVerifier.create(svc.getById(esperado.id()))
                .assertNext(s -> {
                    assertThat(s).isSameAs(esperado);
                    assertThat(s.codigo()).isEqualTo("S-DET-A");
                    assertThat(s.estado()).isEqualTo(EstadoSensor.ACTIVO);
                    assertThat(s.rangoNormal().min()).isEqualByComparingTo(new BigDecimal("4"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-006 / AF-05: sensor INACTIVO → se devuelve igual (el detalle no filtra por estado)")
    void testBR006_inactivoSeDevuelve() {
        FakeFindPort port = new FakeFindPort();
        Sensor inactivo = sensorConEstado(EstadoSensor.INACTIVO);
        port.value = inactivo;
        GetSensorDetailUseCase svc = new GetSensorDetailService(port);

        StepVerifier.create(svc.getById(inactivo.id()))
                .assertNext(s -> assertThat(s.estado()).isEqualTo(EstadoSensor.INACTIVO))
                .verifyComplete();
    }
}
