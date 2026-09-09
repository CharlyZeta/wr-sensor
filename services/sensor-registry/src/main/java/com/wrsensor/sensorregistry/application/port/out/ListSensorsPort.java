package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Puerto de salida (out): listing keyset de sensores (FEAT-0002, BR-001 nunca OFFSET).
 *
 * <p>Devuelve hasta {@code limit} filas ordenadas por fecha_instalacion DESC, id ASC
 * (BR-004), a partir del cursor {@code (afterFecha, afterId)} — filas con
 * fecha_instalacion estrictamente menor, o igual fecha + id estrictamente mayor
 * (ASC desempate). Si {@code afterFecha == null} arranca desde el inicio (sin WHERE).
 * El caller pide {@code limit+1} para detectar mas paginas (BR-005).
 */
public interface ListSensorsPort {

    Flux<Sensor> listAfter(Instant afterFecha, UUID afterId, int limit);
}
