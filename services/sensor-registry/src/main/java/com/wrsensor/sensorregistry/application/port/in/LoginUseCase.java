package com.wrsensor.sensorregistry.application.port.in;

import com.wrsensor.sensorregistry.domain.model.Rol;
import reactor.core.publisher.Mono;

/**
 * Use case port (in): login con email + password → JWT (Main Flow FEAT-0006).
 * La validacion estructural del body (email formato / password no vacia) ocurre
 * en el adapter web (Bean Validation → 400 SENSOR_INVALID_REQUEST); aca solo se
 * validan credenciales contra el repositorio.
 */
public interface LoginUseCase {

    record LoginCommand(String email, String password) {}

    record LoginResult(String token, Rol rol, long expiraEnSegundos) {}

    /** @return token firmado + rol + expiracion, o error InvalidCredentialsException. */
    Mono<LoginResult> login(LoginCommand command);
}
