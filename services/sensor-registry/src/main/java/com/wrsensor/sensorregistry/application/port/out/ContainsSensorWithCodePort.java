package com.wrsensor.sensorregistry.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Puerto de salida (out): chequear si ya existe un sensor con un codigo dado.
 * Usado por la aplicacion para validar BR-001 (unicidad de codigo) antes de
 * persistir. La aplicacion reacciona con 409 si existe.
 */
public interface ContainsSensorWithCodePort {

    Mono<Boolean> existsByCodigo(String codigo);
}
