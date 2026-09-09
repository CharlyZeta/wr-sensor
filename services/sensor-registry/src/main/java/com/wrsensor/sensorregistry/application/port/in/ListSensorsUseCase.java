package com.wrsensor.sensorregistry.application.port.in;

import com.wrsensor.sensorregistry.domain.model.Sensor;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Use case port (in): listado de sensores con paginacion keyset (FEAT-0002).
 * Es el puerto que el adapter web invoca. El query lleva el limit (Integer,
 * null = default 100) y el cursor opaco (String, null = desde el inicio);
 * la aplicacion valida y devuelve una pagina + nextCursor opaco (o null si
 * no hay mas).
 */
public interface ListSensorsUseCase {

    record ListSensorsQuery(Integer limit, String cursor) {}

    record SensorPage(List<Sensor> items, String nextCursor) {}

    Mono<SensorPage> list(ListSensorsQuery query);
}
