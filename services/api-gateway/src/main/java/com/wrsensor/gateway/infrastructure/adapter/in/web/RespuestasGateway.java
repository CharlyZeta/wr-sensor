package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.Correlacion;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Helpers HTTP del gateway (FEAT-0007 BR-009/BR-010): respuesta de error de dominio,
 * cabeceras de correlación y limpieza de headers hop-by-hop.
 */
public final class RespuestasGateway {

    /** Headers que no se propagan en ninguna dirección (BR-010). */
    private static final Set<String> NO_PROPAGAR = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "transfer-encoding", "upgrade", "host", "content-length");

    /**
     * Headers de seguridad que fija el gateway (FIX-0008 BR-003): se descartan los que venga del
     * downstream para que el valor configurado sea el único que ve el cliente (nunca duplicado y
     * nunca sobrescrito por un servicio comprometido).
     */
    private static final Set<String> SEGURIDAD = Set.of(
            "content-security-policy", "x-content-type-options", "referrer-policy", "x-frame-options",
            "permissions-policy", "cross-origin-resource-policy", "cross-origin-opener-policy",
            "strict-transport-security");

    private RespuestasGateway() {
    }

    public static boolean propagable(String nombreHeader) {
        String h = nombreHeader.toLowerCase(Locale.ROOT);
        return !NO_PROPAGAR.contains(h) && !SEGURIDAD.contains(h);
    }

    /** Respuesta de error {@code {"code","message"}} con el código de correlación (BR-008/BR-009). */
    public static Mono<ServerResponse> error(ServerWebExchange exchange, HttpStatus status,
                                            String code, String message) {
        String correlacion = (String) exchange.getAttributes()
                .get(FiltroCorrelacion.ATTR_CORRELACION);
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (correlacion != null) {
                        h.set(Correlacion.HEADER, correlacion);
                    }
                })
                .bodyValue(CodigosError.json(code, message));
    }

    /**
     * Igual que {@link #error} pero sin intentar escribir si la respuesta ya fue comprometida
     * (p. ej. un error en medio de un body ya streameado): en ese caso sólo se registra.
     */
    public static Mono<ServerResponse> errorSiNoCometida(ServerWebExchange exchange,
                                                        HttpStatus status, String code,
                                                        String message) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        return error(exchange, status, code, message);
    }

    /** Escribe el error directamente en el exchange (para filtros, que no devuelven ServerResponse). */
    public static Mono<Void> escribirError(ServerWebExchange exchange, HttpStatus status,
                                          String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = CodigosError.json(code, message).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    public static HttpHeaders copiar(HttpHeaders origen) {
        HttpHeaders destino = new HttpHeaders();
        origen.forEach((nombre, valores) -> {
            if (propagable(nombre)) {
                destino.put(nombre, valores);
            }
        });
        return destino;
    }
}
