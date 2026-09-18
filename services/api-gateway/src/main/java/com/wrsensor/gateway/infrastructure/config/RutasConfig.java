package com.wrsensor.gateway.infrastructure.config;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.TablaRutas;
import com.wrsensor.gateway.infrastructure.adapter.in.web.ManejadorRuta;
import com.wrsensor.gateway.infrastructure.adapter.in.web.ManejadorWs;
import com.wrsensor.gateway.infrastructure.adapter.in.web.RespuestasGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.client.HttpClient;

import io.netty.channel.ChannelOption;

import java.time.Duration;
import java.util.List;

/**
 * Tabla de rutas → {@link RouterFunction} (FEAT-0007 BR-001/BR-002).
 *
 * <p>Las rutas declaradas bajo {@code /ws/} se atienden con {@link ManejadorWs} (puente
 * WebSocket); el resto con {@link ManejadorRuta} (proxy REST). Al final hay un catch-all que
 * responde {@code 404 ROUTE_NOT_FOUND} para cualquier path no declarado (AF-01) — el gateway
 * nunca reenvía a un destino por defecto.</p>
 */
@Configuration
public class RutasConfig {

    @Bean
    RouterFunction<ServerResponse> rutasGateway(TablaRutas tabla, GatewayProperties props,
                                               com.wrsensor.gateway.infrastructure.adapter.in.web.AutenticadorWs autenticadorWs) {
        boolean confiar = props.rateLimit() != null && props.rateLimit().confiarForwardedForOrDefault();
        RouterFunctions.Builder builder = RouterFunctions.route();

        // El RouterFunction resuelve por PRIMER match: hay que registrar las rutas de más
        // específica a menos específica para que el ruteo coincida con TablaRutas.resolver
        // (si no, /api/sensores/** ganaría sobre /api/sensores/*/lecturas).
        List<TablaRutas.Ruta> ordenadas = tabla.rutas().stream()
                .sorted(java.util.Comparator.comparing(TablaRutas.Ruta::patron,
                        org.springframework.web.util.pattern.PathPattern.SPECIFICITY_COMPARATOR))
                .toList();

        for (TablaRutas.Ruta ruta : ordenadas) {
            if (esWs(ruta)) {
                ManejadorWs manejador = new ManejadorWs(ruta, clienteWs(ruta),
                        new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy()),
                        autenticadorWs);
                builder = builder.route(path(ruta), manejador);
            } else {
                ManejadorRuta manejador = new ManejadorRuta(ruta, clienteRest(ruta), confiar);
                if (ruta.metodos().isEmpty()) {
                    builder = builder.route(path(ruta), manejador);
                } else {
                    for (String metodo : ruta.metodos()) {
                        builder = builder.route(RequestPredicates.method(
                                org.springframework.http.HttpMethod.valueOf(metodo))
                                .and(path(ruta)), manejador);
                    }
                }
            }
        }

        // catch-all de la API: ruta no declarada → 404 ROUTE_NOT_FOUND (sin fallback a un destino).
        // Se registra ANTES del handler del SPA para que el fallback de rutas del cliente nunca
        // pueda devolver HTML a un path de la API (FIX-0008 BR-006).
        builder = builder.route(RequestPredicates.path("/api/**"), request ->
                RespuestasGateway.error(request.exchange(), HttpStatus.NOT_FOUND,
                        CodigosError.ROUTE_NOT_FOUND,
                        "ruta no declarada en el gateway: " + request.path()));

        // SPA (FIX-0008 BR-005/BR-006/BR-007): estáticos de classpath:/static/ + índice para las
        // rutas del cliente. Resolución contenida y 404 para assets inexistentes.
        builder = builder.route(RequestPredicates.path("/**"),
                new com.wrsensor.gateway.infrastructure.adapter.in.web.ServidorSpa(
                        props.seguridad() == null
                                ? new GatewayProperties.SeguridadCfg(null, null, null, null, null, null,
                                        null, null, null)
                                : props.seguridad()));

        return builder.build();
    }

    static boolean esWs(TablaRutas.Ruta ruta) {
        return ruta.patronTexto().startsWith("/ws/");
    }

    private static RequestPredicate path(TablaRutas.Ruta ruta) {
        return RequestPredicates.path(ruta.patronTexto());
    }

    /** Cliente REST por ruta: el response timeout es el timeout declarado de la ruta (BR-009). */
    private static org.springframework.web.reactive.function.client.WebClient clienteRest(
            TablaRutas.Ruta ruta) {
        HttpClient http = HttpClient.create()
                .compress(false)
                .followRedirect(false)
                .responseTimeout(Duration.ofMillis(ruta.timeoutMs()));
        return org.springframework.web.reactive.function.client.WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .build();
    }

    /** Cliente WS por ruta: solo timeout de conexión (la sesión es de larga duración). */
    private static ReactorNettyWebSocketClient clienteWs(TablaRutas.Ruta ruta) {
        return new ReactorNettyWebSocketClient(HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) ruta.timeoutMs()));
    }

    /** Rutas declaradas (observabilidad/arranque). */
    @Bean
    List<String> rutasDeclaradas(TablaRutas tabla) {
        return tabla.rutas().stream().map(r -> r.id() + " " + r.patronTexto() + " → " + r.destino())
                .toList();
    }
}
