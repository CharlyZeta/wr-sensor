package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

/** Puerto de salida (out): persistir un sensor. Retorna el sensor persistido. */
public interface SaveSensorPort {

    Mono<Sensor> save(Sensor sensor);
}
