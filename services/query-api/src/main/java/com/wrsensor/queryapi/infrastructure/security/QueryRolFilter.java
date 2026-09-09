package com.wrsensor.queryapi.infrastructure.security;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Resuelve el rol una vez por request (attr) — patrón ADR-0006. */
@Component
public class QueryRolFilter implements WebFilter {

    private final QueryAuthGuard guard;

    public QueryRolFilter(QueryAuthGuard guard) {
        this.guard = guard;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        String rol = guard.rolDe(header);
        if (rol != null) {
            exchange.getAttributes().put(QueryAuthGuard.ATTR_ROL, rol);
        }
        return chain.filter(exchange);
    }
}
