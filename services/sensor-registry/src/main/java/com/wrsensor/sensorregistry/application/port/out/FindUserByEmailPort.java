package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Usuario;
import reactor.core.publisher.Mono;

/**
 * Port out: busqueda de Usuario por email (FEAT-0006 BR-002). El email se busca
 * normalizado a minusculas; {@code Mono.empty()} si no existe (la aplicacion
 * decide el 401 INVALID_CREDENTIALS, indistinguible de password incorrecta).
 */
public interface FindUserByEmailPort {

    Mono<Usuario> findByEmail(String email);
}
