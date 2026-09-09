package com.wrsensor.datasimulator.application.port.out;

import com.wrsensor.datasimulator.domain.model.Lectura;
import reactor.core.publisher.Mono;

/** Port out: publicacion de una lectura en `sensor.lecturas` (FEAT-0010 BR-002). */
public interface LecturaPublisher {

    Mono<Void> publish(Lectura lectura);
}
