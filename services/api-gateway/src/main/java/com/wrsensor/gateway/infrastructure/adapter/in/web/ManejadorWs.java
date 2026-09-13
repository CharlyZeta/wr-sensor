package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.TablaRutas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Puente WebSocket de una ruta (FEAT-0007 BR-011 / AF-05): conecta primero con el downstream y
 * recién entonces completa el handshake del cliente, de modo que si el downstream no está se
 * responde {@code 502 UPSTREAM_UNAVAILABLE} (y no un handshake aceptado que muere después).
 * Luego retransmite frames de texto en ambos sentidos, propagando el cierre en los dos lados.
 *
 * <p>No agrega autenticación: los WS de {@code alerting-service}/{@code query-api} no la tienen
 * hoy (brecha conocida, fuera de alcance; ver ADR-0016).</p>
 */
public class ManejadorWs implements HandlerFunction<ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ManejadorWs.class);

    private final TablaRutas.Ruta ruta;
    private final ReactorNettyWebSocketClient clienteWs;
    private final HandshakeWebSocketService handshake;

    public ManejadorWs(TablaRutas.Ruta ruta, ReactorNettyWebSocketClient clienteWs,
                       HandshakeWebSocketService handshake) {
        this.ruta = ruta;
        this.clienteWs = clienteWs;
        this.handshake = handshake;
    }

    @Override
    public Mono<ServerResponse> handle(ServerRequest request) {
        ServerWebExchange exchange = request.exchange();
        if (!esUpgrade(exchange)) {
            return RespuestasGateway.error(exchange, HttpStatus.UPGRADE_REQUIRED,
                    CodigosError.WS_UPGRADE_REQUIRED,
                    "la ruta " + ruta.patronTexto() + " requiere un upgrade a WebSocket");
        }
        URI destino = destino(exchange);
        return clienteWs.execute(destino, remota -> puentear(exchange, remota))
                .doOnError(e -> log.warn("[gateway] WS {} → {} fallo: {}", ruta.id(), destino,
                        e.getMessage()))
                .then(Mono.<ServerResponse>empty())
                .onErrorResume(e -> RespuestasGateway.errorSiNoCometida(exchange,
                        HttpStatus.BAD_GATEWAY, CodigosError.UPSTREAM_UNAVAILABLE,
                        "servicio destino no disponible para WebSocket"));
    }

    /**
     * Conecta el downstream y recién entonces hace el handshake del cliente.
     *
     * <p>Cuidado con el ciclo de vida: la estrategia de upgrade de Reactor Netty
     * <b>auto-suscribe</b> el handler, así que el Mono del handshake completa apenas termina el
     * upgrade. Sin esperar al relay, la sesión contra el downstream se cerraría de inmediato
     * (el cliente recibiría el saludo y nada más). Por eso el Mono exterior espera la señal de
     * fin del relay.</p>
     */
    private Mono<Void> puentear(ServerWebExchange exchange, WebSocketSession remota) {
        reactor.core.publisher.Sinks.Empty<Void> fin = reactor.core.publisher.Sinks.empty();
        return handshake.handleRequest(exchange, cliente -> retransmitir(cliente, remota)
                        .doFinally(senal -> fin.tryEmitEmpty()))
                .onErrorResume(e -> {
                    fin.tryEmitError(e);
                    return Mono.error(e);
                })
                .then(fin.asMono());
    }

    private Mono<Void> retransmitir(WebSocketSession cliente, WebSocketSession remota) {
        Mono<Void> subida = cliente.receive()
                .map(m -> remota.textMessage(m.getPayloadAsText()))
                .as(remota::send)
                .doFinally(s -> cerrar(remota));
        Mono<Void> bajada = remota.receive()
                .map(m -> cliente.textMessage(m.getPayloadAsText()))
                .as(cliente::send)
                .doFinally(s -> cerrar(cliente));
        return Mono.when(subida, bajada).then();
    }

    private static void cerrar(WebSocketSession sesion) {
        sesion.close().onErrorResume(e -> Mono.empty()).subscribe();
    }

    private URI destino(ServerWebExchange exchange) {
        String base = ruta.destino().endsWith("/")
                ? ruta.destino().substring(0, ruta.destino().length() - 1) : ruta.destino();
        String path = exchange.getRequest().getPath().value();
        String query = exchange.getRequest().getURI().getRawQuery();
        String ws = base.startsWith("https://") ? "wss://" + base.substring(8)
                : base.startsWith("http://") ? "ws://" + base.substring(7) : base;
        return URI.create(ws + path + (query == null || query.isEmpty() ? "" : "?" + query));
    }

    private static boolean esUpgrade(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        String upgrade = headers.getUpgrade();
        String connection = headers.getFirst("Connection");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade)
                && connection != null && connection.toLowerCase().contains("upgrade");
    }
}
