package com.wrsensor.ingestion.application.port;

import com.wrsensor.ingestion.domain.AlertaEvento;
import reactor.core.publisher.Mono;

/** Port out: publicacion de AlertaEvento en `sensor.alertas` (BR-004). */
public interface AlertaEventoPublisher {

    Mono<Void> publish(AlertaEvento evento);
}
