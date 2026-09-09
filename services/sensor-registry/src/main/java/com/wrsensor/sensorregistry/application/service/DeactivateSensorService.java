package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.DeactivateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorNotFoundException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Implementacion del caso de uso "Deactivate Sensor" (Main Flow FEAT-0005).
 *
 * <p>BR-002: sensor inexistente → {@link SensorNotFoundException} (404). BR-004:
 * baja logica — nunca DELETE fisico; se persiste {@code estado=INACTIVO} via
 * {@link UpdateSensorPort} (UPDATE). BR-005: idempotente — si ya esta INACTIVO,
 * no-op (completa sin cambios). BR-006/BR-007: no cambia visibilidad (listado/
 * detalle siguen mostrandolo); la reactivacion es del PUT FEAT-0004.
 */
public class DeactivateSensorService implements DeactivateSensorUseCase {

    private final FindSensorByIdPort findPort;
    private final UpdateSensorPort updatePort;

    public DeactivateSensorService(FindSensorByIdPort findPort, UpdateSensorPort updatePort) {
        this.findPort = findPort;
        this.updatePort = updatePort;
    }

    @Override
    public Mono<Void> deactivate(UUID id) {
        return Mono.defer(() -> findPort.findById(id)
                        .switchIfEmpty(Mono.error(new SensorNotFoundException()))
                        .flatMap(sensor -> {
                            if (sensor.estado() == EstadoSensor.INACTIVO) {
                                return Mono.empty(); // re-baja idempotente (BR-005)
                            }
                            return updatePort.update(inactivo(sensor)).then();
                        }));
    }

    /** BR-004: solo cambia el estado; identidad y configuracion intactas. */
    private static Sensor inactivo(Sensor s) {
        return new Sensor(
                s.id(), s.codigo(), s.nombre(), s.tipo(),
                s.latitud(), s.longitud(), s.unidadMedida(),
                EstadoSensor.INACTIVO, s.histeresis(), s.frecuenciaReporteSegundos(),
                s.fechaInstalacion(),
                s.rangoNormal(), s.rangoWarning(), s.rangoCritical());
    }
}
