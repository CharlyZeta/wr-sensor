package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.application.port.out.TokenVerifier;
import com.wrsensor.sensorregistry.domain.model.Rol;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFilter reactivo (FEAT-0006 BR-004): verifica el header
 * {@code Authorization: Bearer <jwt>} con {@link TokenVerifier} y propaga el rol
 * al Reactor Context (clave {@code wrsensor.rol}) para los guards
 * ({@link RolGuard#requireAdmin}/{@link RolGuard#requireReader} via
 * {@link SecurityConfig#currentRol()}).
 *
 * <p>Semantica: sin header o token no verificable (malformado/expirado/firma
 * invalida) → rol {@code null} (el guard responde 401 UNAUTHENTICATED). Token
 * verificable con rol ajeno a {ADMIN, VIEWER} → {@link Rol#OTHER} (403). No hay
 * camino de rol literal: un "Bearer ADMIN" sin firma JWT falla la verificacion.
 */
@Component
public class RolFilter implements WebFilter {

    private final TokenVerifier tokenVerifier;

    public RolFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        return verifyRol(header)
                .doOnNext(rol -> RolGuard.putRol(exchange, rol))
                .flatMap(rol -> chain.filter(exchange)
                        .contextWrite(SecurityConfig.WriteRolContext.with(rol)))
                .switchIfEmpty(Mono.defer(() ->
                        chain.filter(exchange)
                                .contextWrite(SecurityConfig.WriteRolContext.with(null))));
    }

    /** Rol del header; {@code Mono.empty()} = sin header o token no verificable (rol null → 401). */
    private Mono<Rol> verifyRol(String header) {
        if (header == null || header.isBlank()) return Mono.empty();
        String token = header.trim();
        if (token.startsWith("Bearer ")) token = token.substring(7).trim();
        if (token.isEmpty()) return Mono.empty();
        return tokenVerifier.verify(token); // empty si firma/exp invalidos
    }
}
