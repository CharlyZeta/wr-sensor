package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.domain.TablaRutas;
import com.wrsensor.gateway.domain.TokenBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Rate limiting (FEAT-0007 BR-003/BR-004/BR-005/BR-006): resuelve la ruta por método+path,
 * aplica la clase de límite de esa ruta con un token bucket por {@code clase|IP} y responde
 * {@code 429} con {@code Retry-After} sin reenviar la petición al downstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class FiltroRateLimit implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroRateLimit.class);

    private final TablaRutas tabla;
    private final Map<String, Limite> clases;
    private final RateLimiterEnMemoria limiter;
    private final boolean confiarForwardedFor;
    private final Clock reloj;

    @org.springframework.beans.factory.annotation.Autowired
    public FiltroRateLimit(TablaRutas tabla, Map<String, Limite> clasesLimite,
                           RateLimiterEnMemoria limiter,
                           com.wrsensor.gateway.infrastructure.config.GatewayProperties props) {
        this(tabla, clasesLimite, limiter,
                props.rateLimit() != null && props.rateLimit().confiarForwardedForOrDefault(),
                Clock.systemUTC());
    }

    /** Constructor con reloj inyectado (tests). */
    public FiltroRateLimit(TablaRutas tabla, Map<String, Limite> clasesLimite,
                           RateLimiterEnMemoria limiter, boolean confiarForwardedFor, Clock reloj) {
        this.tabla = tabla;
        this.clases = clasesLimite;
        this.limiter = limiter;
        this.confiarForwardedFor = confiarForwardedFor;
        this.reloj = reloj;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String metodo = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();
        TablaRutas.Ruta ruta = tabla.resolver(metodo, path).orElse(null);
        if (ruta == null) {
            return chain.filter(exchange);   // sin ruta declarada: la responde el router (404)
        }
        Limite limite = clases.get(ruta.claseLimite());
        if (limite == null) {
            return chain.filter(exchange);   // ya validado en el arranque; defensivo
        }
        String clave = ruta.claseLimite() + "|" + ipDe(exchange);
        TokenBucket.Veredicto veredicto = limiter.evaluar(clave, limite, Instant.now(reloj));

        var headers = exchange.getResponse().getHeaders();
        // X-RateLimit-Limit = cupo de la ventana (BR-004); Remaining = tokens disponibles
        headers.set("X-RateLimit-Limit", String.valueOf(limite.peticiones()));
        headers.set("X-RateLimit-Remaining", String.valueOf(veredicto.restante()));

        if (!veredicto.permitido()) {
            headers.set("Retry-After", String.valueOf(veredicto.retryAfterSegundos()));
            log.warn("[gateway] rate limit excedido ruta={} clase={} ip={} retry-after={}s",
                    ruta.id(), ruta.claseLimite(), ipDe(exchange), veredicto.retryAfterSegundos());
            return RespuestasGateway.escribirError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    CodigosError.RATE_LIMIT_EXCEEDED,
                    "cupo excedido para " + ruta.claseLimite() + ": reintentar en "
                            + veredicto.retryAfterSegundos() + " s");
        }
        return chain.filter(exchange);
    }

    /** Clave del límite: IP del peer por default; {@code X-Forwarded-For} solo si se confía. */
    String ipDe(ServerWebExchange exchange) {
        if (confiarForwardedFor) {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return ManejadorRuta.ipPeer(exchange);
    }
}
