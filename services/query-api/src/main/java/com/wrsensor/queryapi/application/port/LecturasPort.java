package com.wrsensor.queryapi.application.port;

import com.wrsensor.queryapi.domain.LecturaConsulta;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Port out: consultas sobre la hypertable `lectura` (BR-001/BR-004). */
public interface LecturasPort {

    /** Filas (sensor, rango, estrictamente antes de afterTs) DESC por ts, hasta limit. */
    Flux<LecturaConsulta> listar(UUID sensorId, Instant desde, Instant hasta, Instant afterTs, int limit);

    /** Ultima lectura (ts maximo); empty si no hay. */
    Mono<LecturaConsulta> ultima(UUID sensorId);
}
