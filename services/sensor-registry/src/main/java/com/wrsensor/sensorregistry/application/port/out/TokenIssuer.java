package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Usuario;

/**
 * Port out: emision de JWT (FEAT-0006 BR-003). Firmado HS256 con claims sub/rol/iat/exp;
 * la expiracion en segundos del emisor se devuelve para el body del login.
 */
public interface TokenIssuer {

    record IssuedToken(String token, long expiresInSeconds) {}

    IssuedToken issue(Usuario usuario);
}
