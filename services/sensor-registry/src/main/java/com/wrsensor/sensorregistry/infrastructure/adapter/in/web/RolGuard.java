package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.domain.model.Rol;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Guard reactivo de acceso (AF-04 FEAT-0001; BR-003/BR-006 FEAT-0002; FEAT-0005).
 *
 * <p>Dos vias de resolucion del rol:
 * <ul>
 *   <li><b>Por atributo del exchange</b> (produccion): {@link RolFilter} resuelve
 *       el rol UNA vez por request y lo guarda como atributo
 *       ({@link #putRol}). Los guards de controller reciben el exchange y leen el
 *       atributo — determinista, inmune a re-suscripciones del Mono del handler.</li>
 *   <li><b>Por Reactor Context</b> (unit tests): overloads {@code (Mono)} que leen
 *       {@link SecurityConfig#currentRol()}; los tests unitarios escriben el rol
 *       en el Context ({@code WriteRolContext.with}).</li>
 * </ul>
 */
public final class RolGuard {

    public static final String ATTR_ROL = "wrsensor.rol.attr";

    /** Excepcion de dominio para rol insuficiente / auth ausente; el GlobalErrorHandler mapea. */
    public static final class InsufficientRoleException extends RuntimeException {
        public final String code;
        public InsufficientRoleException(String code, String msg) { super(msg); this.code = code; }
    }

    private RolGuard() {}

    /** Guard de escritura (ADMIN) leyendo el rol del exchange (produccion). */
    public static <T> Mono<T> requireAdmin(ServerWebExchange exchange, Mono<T> downstream) {
        Rol rol = exchange.getAttribute(ATTR_ROL);
        if (rol == null) {
            return Mono.error(new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido"));
        }
        if (rol != Rol.ADMIN) {
            return Mono.error(new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN requerido"));
        }
        return downstream;
    }

    /** Guard de lectura ({ADMIN, VIEWER}) leyendo el rol del exchange (produccion). */
    public static <T> Mono<T> requireReader(ServerWebExchange exchange, Mono<T> downstream) {
        Rol rol = exchange.getAttribute(ATTR_ROL);
        if (rol == null) {
            return Mono.error(new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido"));
        }
        if (rol != Rol.ADMIN && rol != Rol.VIEWER) {
            return Mono.error(new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN o VIEWER requerido"));
        }
        return downstream;
    }

    /** Almacena el rol resuelto como atributo del exchange (lo usa RolFilter).
     *  Nunca limpia: un exchange sin auth simplemente no tiene el atributo. */
    public static void putRol(ServerWebExchange exchange, Rol rol) {
        if (rol != null) {
            exchange.getAttributes().put(ATTR_ROL, rol);
        }
    }

    // ===== via Reactor Context (unit tests) =====

    public static <T> Mono<T> requireAdmin(Mono<T> downstream) {
        return SecurityConfig.currentRol()
                .switchIfEmpty(Mono.error(new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido")))
                .flatMap(rol -> {
                    if (rol != Rol.ADMIN) {
                        return Mono.error(new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN requerido"));
                    }
                    return downstream;
                });
    }

    public static <T> Mono<T> requireReader(Mono<T> downstream) {
        return SecurityConfig.currentRol()
                .switchIfEmpty(Mono.error(new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido")))
                .flatMap(rol -> {
                    if (rol != Rol.ADMIN && rol != Rol.VIEWER) {
                        return Mono.error(new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN o VIEWER requerido"));
                    }
                    return downstream;
                });
    }
}
