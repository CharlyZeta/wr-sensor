package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.Correlacion;
import com.wrsensor.gateway.domain.TablaRutas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Proxy REST de una ruta (FEAT-0007 BR-009/BR-010): reenvía método, path, query, body y
 * {@code Authorization}; agrega los {@code X-Forwarded-*}; devuelve el status y los headers del
 * downstream tal cual (menos hop-by-hop) y mapea caídas/timeouts a errores de dominio.
 */
public class ManejadorRuta implements HandlerFunction<ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ManejadorRuta.class);

    private final TablaRutas.Ruta ruta;
    private final WebClient cliente;
    private final boolean confiarForwardedFor;
    private final Duration timeout;

    public ManejadorRuta(TablaRutas.Ruta ruta, WebClient cliente, boolean confiarForwardedFor) {
        this.ruta = ruta;
        this.cliente = cliente;
        this.confiarForwardedFor = confiarForwardedFor;
        this.timeout = Duration.ofMillis(ruta.timeoutMs());
    }

    @Override
    public Mono<ServerResponse> handle(ServerRequest request) {
        ServerWebExchange exchange = request.exchange();
        URI destino = destino(exchange);
        String correlacion = (String) exchange.getAttributes()
                .get(FiltroCorrelacion.ATTR_CORRELACION);

        org.springframework.web.reactive.function.client.WebClient.RequestBodySpec spec =
                cliente.method(request.method())
                        .uri(destino)
                        .headers(h -> {
                            h.addAll(RespuestasGateway.copiar(exchange.getRequest().getHeaders()));
                            agregarForwarded(exchange, h);
                            if (correlacion != null) {
                                h.set(Correlacion.HEADER, correlacion);
                            }
                        });
        var conBody = conCuerpo(request.method())
                ? spec.body(org.springframework.web.reactive.function.BodyInserters
                        .fromDataBuffers(request.bodyToFlux(DataBuffer.class)))
                : spec;

        return conBody.exchangeToMono(respuesta -> respuesta
                        .bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        // El body se materializa DENTRO del exchange: con exchangeToMono la
                        // respuesta se libera al completar el Mono, así que devolver un
                        // publisher perezoso dejaría el body vacío. Los payloads del proyecto
                        // son JSON chicos (limit máx 1000); el streaming queda para WS.
                        .flatMap(cuerpo -> ServerResponse.status(respuesta.statusCode())
                                .headers(h -> {
                                    h.addAll(RespuestasGateway.copiar(
                                            respuesta.headers().asHttpHeaders()));
                                    if (correlacion != null) {
                                        h.set(Correlacion.HEADER, correlacion);
                                    }
                                })
                                .bodyValue(cuerpo)))
                .timeout(timeout)
                .onErrorResume(this::esTimeout, e -> {
                    log.warn("[gateway] timeout en ruta {} → {} ({} ms)", ruta.id(), destino,
                            ruta.timeoutMs());
                    return RespuestasGateway.errorSiNoCometida(exchange, HttpStatus.GATEWAY_TIMEOUT,
                            CodigosError.UPSTREAM_TIMEOUT,
                            "el servicio destino no respondio en " + ruta.timeoutMs() + " ms");
                })
                .onErrorResume(WebClientRequestException.class, e -> {
                    log.warn("[gateway] destino inalcanzable en ruta {} → {}: {}", ruta.id(),
                            destino, e.getMessage());
                    return RespuestasGateway.errorSiNoCometida(exchange, HttpStatus.BAD_GATEWAY,
                            CodigosError.UPSTREAM_UNAVAILABLE,
                            "servicio destino no disponible");
                });
    }

    private static boolean conCuerpo(org.springframework.http.HttpMethod metodo) {
        return metodo == org.springframework.http.HttpMethod.POST
                || metodo == org.springframework.http.HttpMethod.PUT
                || metodo == org.springframework.http.HttpMethod.PATCH
                || metodo == org.springframework.http.HttpMethod.DELETE;
    }

    private URI destino(ServerWebExchange exchange) {
        String base = ruta.destino().endsWith("/")
                ? ruta.destino().substring(0, ruta.destino().length() - 1) : ruta.destino();
        String path = exchange.getRequest().getPath().value();
        String query = exchange.getRequest().getURI().getRawQuery();
        return URI.create(base + path + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    private void agregarForwarded(ServerWebExchange exchange, org.springframework.http.HttpHeaders h) {
        String peer = ipPeer(exchange);
        String previo = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (confiarForwardedFor && previo != null && !previo.isBlank()) {
            h.set("X-Forwarded-For", previo + ", " + peer);
        } else {
            h.set("X-Forwarded-For", peer);
        }
        h.set("X-Forwarded-Proto", exchange.getRequest().getURI().getScheme());
        String host = exchange.getRequest().getHeaders().getFirst("Host");
        if (host != null) {
            h.set("X-Forwarded-Host", host);
        }
    }

    static String ipPeer(ServerWebExchange exchange) {
        var remoto = exchange.getRequest().getRemoteAddress();
        return remoto == null || remoto.getAddress() == null ? "desconocida"
                : remoto.getAddress().getHostAddress();
    }

    private boolean esTimeout(Throwable error) {
        Throwable actual = error;
        while (actual != null) {
            if (actual instanceof TimeoutException
                    || actual instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return true;
            }
            actual = actual.getCause();
        }
        return false;
    }
}
