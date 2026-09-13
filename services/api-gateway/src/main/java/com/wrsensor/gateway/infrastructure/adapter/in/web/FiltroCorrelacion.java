package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.Correlacion;
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

import java.time.Duration;
import java.time.Instant;

/**
 * Correlación + log de acceso (FEAT-0007 BR-008): resuelve el {@code X-Correlation-Id}
 * (del cliente si es válido, generado si no), lo deja en el exchange para que el proxy lo
 * propague, lo refleja en la respuesta y registra una línea de acceso por request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FiltroCorrelacion implements WebFilter {

    /** Atributo del exchange con el id de correlación resuelto. */
    public static final String ATTR_CORRELACION = "gateway.correlacion";

    private static final Logger log = LoggerFactory.getLogger(FiltroCorrelacion.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = Correlacion.resolver(exchange.getRequest().getHeaders()
                .getFirst(Correlacion.HEADER));
        exchange.getAttributes().put(ATTR_CORRELACION, id);
        exchange.getResponse().getHeaders().set(Correlacion.HEADER, id);

        Instant inicio = Instant.now();
        String metodo = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();
        String ip = ManejadorRuta.ipPeer(exchange);

        return chain.filter(exchange).doFinally(senal -> {
            HttpStatus status = exchange.getResponse().getStatusCode() == null ? null
                    : HttpStatus.resolve(exchange.getResponse().getStatusCode().value());
            long ms = Duration.between(inicio, Instant.now()).toMillis();
            log.info("[gateway] {} {} → {} ({} ms) ip={} correlacion={}", metodo, path,
                    status == null ? "SIN-RESPUESTA" : status.value(), ms, ip, id);
        });
    }
}
