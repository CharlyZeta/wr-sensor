package com.wrsensor.ingestion.application.port;

import com.wrsensor.ingestion.domain.LecturaPersistida;
import reactor.core.publisher.Mono;

/** Port out: persistencia en la hypertable `lectura` (BR-003/BR-008). */
public interface LecturaStore {

    Mono<Void> insert(LecturaPersistida lectura);
}
