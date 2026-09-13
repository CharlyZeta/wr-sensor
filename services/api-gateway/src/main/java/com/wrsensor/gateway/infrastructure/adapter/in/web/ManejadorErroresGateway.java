package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Red de seguridad (FEAT-0007 BR-009): nunca se filtra un stacktrace ni un 500 con detalles
 * internos. Los errores ya mapeados por el proxy (502/504) y las rutas no declaradas (404) no
 * pasan por acá; esto cubre fallos inesperados del propio gateway.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ManejadorErroresGateway implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErroresGateway.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof ResponseStatusException rse) {
            log.warn("[gateway] error de request {} {}: {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), rse.getReason());
            return RespuestasGateway.escribirError(exchange,
                    HttpStatus.valueOf(rse.getStatusCode().value()),
                    CodigosError.ROUTE_NOT_FOUND.equals(rse.getReason())
                            ? CodigosError.ROUTE_NOT_FOUND : CodigosError.INTERNAL_ERROR,
                    rse.getReason() == null ? "error de request" : rse.getReason());
        }
        log.error("[gateway] error inesperado en {} {}: {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex.toString());
        return RespuestasGateway.escribirError(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                CodigosError.INTERNAL_ERROR, "error interno del gateway");
    }
}
