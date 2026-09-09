package com.wrsensor.sensorregistry.application.port.out;

import com.wrsensor.sensorregistry.domain.model.Rol;
import reactor.core.publisher.Mono;

/**
 * Port out: verificacion de JWT (FEAT-0006 BR-004). Firma HS256 y exp validos →
 * rol del claim (ADMIN/VIEWER); firma/exp invalidos o token malformado →
 * {@code Mono.empty()} (el guard responde 401); firma valida con rol ajeno a
 * {ADMIN, VIEWER} → {@link Rol#OTHER} (403).
 */
public interface TokenVerifier {

    Mono<Rol> verify(String token);
}
