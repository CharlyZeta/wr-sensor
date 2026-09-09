package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.domain.model.Rol;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Seguridad reactiva (Spring Security WebFlux). Desde FEAT-0006, el
 * {@link RolFilter} (componente propio) verifica el JWT real del header
 * {@code Authorization: Bearer <jwt>} y propaga el rol a la Reactor Context;
 * los guards ({@link RolGuard}) y el downstream lo consumen con
 * {@link #currentRol()}. Sin OAuth externo (resolucion del Ambiguity Log FEAT-0001).
 *
 * <p>Sin rol literal: un "Bearer ADMIN" sin firma JWT no verifica → rol null →
 * 401 (AC-008 FEAT-0006).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, RolFilter rolFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .addFilterAt(rolFilter, SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }

    /** Escribe/lee el rol en el Reactor Context (clave "wrsensor.rol"). */
    public static final class WriteRolContext {
        private static final String KEY = "wrsensor.rol";

        public static Context with(Rol rol) {
            return rol == null ? Context.empty() : Context.of(KEY, rol);
        }

        public static Mono<Rol> currentRol() {
            return Mono.deferContextual(ctx -> {
                if (!ctx.hasKey(KEY)) return Mono.empty();
                return Mono.just((Rol) ctx.get(KEY));
            });
        }
    }

    public static Mono<Rol> currentRol() {
        return WriteRolContext.currentRol();
    }
}
