package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Headers de seguridad del punto de entrada (FIX-0008 BR-003/BR-004).
 *
 * <p>Se ejecuta con la **máxima precedencia** y escribe los headers **antes** de delegar: así toda
 * respuesta sale con ellos, incluidas las que corta un filtro posterior (CORS {@code 403}, rate limit
 * {@code 429}, errores de dominio) y las que vienen del downstream. Los valores son configuración
 * ({@code gateway.seguridad.*}) y el gateway **no** deja pasar los del downstream (ver
 * {@link RespuestasGateway#propagable}), así que cada header aparece una sola vez.</p>
 *
 * <p>La CSP por default no permite {@code unsafe-inline} ni {@code unsafe-eval} en {@code script-src}:
 * ese es el vector que permitiría robar el token de la sesión. HSTS se emite sólo si la request llegó
 * por HTTPS (directo o vía {@code X-Forwarded-Proto} de un terminador TLS confiable).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroSeguridad implements WebFilter {

    private final GatewayProperties.SeguridadCfg cfg;

    public FiltroSeguridad(GatewayProperties props) {
        this.cfg = props.seguridad() == null
                ? new GatewayProperties.SeguridadCfg(null, null, null, null, null, null, null, null, null)
                : props.seguridad();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        headers(exchange);
        return chain.filter(exchange);
    }

    /** Escribe los headers de seguridad en la respuesta (idempotente: usa {@code set}). */
    void headers(ServerWebExchange exchange) {
        HttpHeaders h = exchange.getResponse().getHeaders();
        h.set("Content-Security-Policy", cfg.contentSecurityPolicyOrDefault());
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Frame-Options", "DENY");
        h.set("Permissions-Policy", cfg.permisosPoliticaOrDefault());
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        if (cfg.hstsOrDefault() && esHttps(exchange)) {
            h.set("Strict-Transport-Security",
                    "max-age=" + cfg.hstsMaxAgeSegundosOrDefault() + "; includeSubDomains");
        }
    }

    private static boolean esHttps(ServerWebExchange exchange) {
        String proto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        if (proto != null && !proto.isBlank()) {
            return "https".equalsIgnoreCase(proto.split(",")[0].trim());
        }
        return "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
    }
}