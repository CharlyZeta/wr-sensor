package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.GetSensorDetailUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorNotFoundException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Implementacion del caso de uso "Get Sensor Detail" (Main Flow FEAT-0003).
 *
 * <p>Orquesta el port out {@code findById}; si el port devuelve vacio (id valido
 * pero sensor inexistente) → {@link SensorNotFoundException} (BR-002: 404
 * SENSOR_NOT_FOUND). Sin validacion de formato de id aca: el adapter web la hace
 * antes (BR-001). Patron de {@code CreateSensorService}/{@code ListSensorsService}
 * — aplicacion sin Spring.
 */
public class GetSensorDetailService implements GetSensorDetailUseCase {

    private final FindSensorByIdPort findPort;

    public GetSensorDetailService(FindSensorByIdPort findPort) {
        this.findPort = findPort;
    }

    @Override
    public Mono<Sensor> getById(UUID id) {
        return findPort.findById(id)
                .switchIfEmpty(Mono.error(new SensorNotFoundException()));
    }
}
