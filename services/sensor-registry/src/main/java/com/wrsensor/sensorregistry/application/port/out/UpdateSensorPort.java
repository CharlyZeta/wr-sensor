package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

/**
 * Port out: update de la configuracion de un sensor (FEAT-0004). Persiste los
 * campos editables y devuelve el sensor refrescado. Sin evento en v1 (decision
 * HO-Gate: FEAT-0011 definira mensajeria).
 */
public interface UpdateSensorPort {

    Mono<Sensor> update(Sensor updated);
}
