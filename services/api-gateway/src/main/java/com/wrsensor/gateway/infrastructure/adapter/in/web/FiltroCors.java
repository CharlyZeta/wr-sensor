package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CORS del gateway (FEAT-0008 BR-001/BR-002).
 *
 * <p>Se ejecuta **antes** que la correlación y el rate limiting, así que el preflight se responde
 * acá mismo: no consume cupo, no llega al downstream y no aparece como request de negocio en los
 * logs. Los orígenes permitidos son configuración ({@code gateway.cors.origenes}); con la lista
 * vacía el gateway es *same-origin only* (CORS deshabilitado), que es el default seguro en
 * producción.</p>
 *
 * <p><b>Same-origin no es CORS</b>: el navegador manda {@code Origin} también en peticiones del
 * mismo origen (POST y, sobre todo, el handshake WebSocket), así que un request cuyo {@code Origin}
 * coincide con el host del propio gateway pasa sin headers CORS y sin bloqueo. Sin esta regla, un
 * SPA servido por el gateway no podría ni loguearse ni abrir un WS.</p>
 *
 * <p>Un origen cruzado no permitido se rechaza con {@code 403} **sin** headers {@code Access-Control-*}
 * (así el navegador no puede leer la respuesta y un cliente no navegador no obtiene datos).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FiltroCors implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(FiltroCors.class);

    private final GatewayProperties.CorsCfg cfg;

    public FiltroCors(GatewayProperties props) {
        this.cfg = props.cors() == null
                ? new GatewayProperties.CorsCfg(null, null, null, null, null, null) : props.cors();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String origen = exchange.getRequest().getHeaders().getOrigin();
        if (origen == null || origen.isBlank()) {
            return chain.filter(exchange);   // request no-CORS: no toca nada
        }
        if (esMismoOrigen(exchange, origen)) {
            // El navegador incluye Origin en el handshake WS y en POST/PUT del propio origen:
            // no es un request CORS, no se le agregan headers ni se bloquea (BR-001).
            return chain.filter(exchange);
        }
        boolean esPreflight = HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())
                && exchange.getRequest().getHeaders()
                        .getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;

        if (!permitido(origen)) {
            log.warn("[gateway] origen no permitido {} en {} {}", origen,
                    exchange.getRequest().getMethod().name(),
                    exchange.getRequest().getPath().value());
            // 403 SIN headers CORS (AF-01/AC-002): el navegador no debe poder leer la respuesta
            return RespuestasGateway.escribirError(exchange, HttpStatus.FORBIDDEN,
                    CodigosError.ORIGIN_NOT_ALLOWED,
                    "origen no permitido: " + origen);
        }

        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origen);
        headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        if (cfg.permitirCredencialesOrDefault()) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        if (esPreflight) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", cfg.metodosOrDefault()));
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                    String.join(", ", headersPermitidos(exchange)));
            headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE,
                    String.valueOf(cfg.maxAgeSegundosOrDefault()));
            exchange.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();   // 204: ni rate limit ni downstream
        }

        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                String.join(", ", cfg.headersExpuestosOrDefault()));
        return chain.filter(exchange);
    }

    boolean permitido(String origen) {
        List<String> permitidos = cfg.origenesOrDefault();
        return permitidos.contains("*") || permitidos.contains(origen);
    }

    /**
     * ¿El {@code Origin} es el del propio gateway? Se compara contra {@code Host} (o
     * {@code X-Forwarded-Host}/{@code -Proto} si hay un terminador TLS adelante) normalizando
     * mayúsculas y puertos por defecto. Si el esquema del propio request no se puede determinar
     * (proxy que no manda {@code X-Forwarded-Proto}), se acepta el match de host:puerto — pasar algo
     * por same-origin sólo significa *no agregar* headers CORS, así que el navegador sigue
     * aplicando su propia política.
     */
    boolean esMismoOrigen(ServerWebExchange exchange, String origen) {
        URI uriOrigen;
        try {
            uriOrigen = URI.create(origen.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (uriOrigen.getHost() == null) {
            return false;
        }
        var request = exchange.getRequest();
        String hostPropio = primero(request.getHeaders().getFirst("X-Forwarded-Host"));
        if (hostPropio == null || hostPropio.isBlank()) {
            hostPropio = primero(request.getHeaders().getFirst(HttpHeaders.HOST));
        }
        if (hostPropio == null || hostPropio.isBlank()) {
            URI uri = request.getURI();
            if (uri.getHost() == null) {
                return false;   // sin host no se puede afirmar same-origin
            }
            hostPropio = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        }
        if (!mismaAutoridad(hostPropio, uriOrigen)) {
            return false;
        }
        String esquemaPropio = primero(request.getHeaders().getFirst("X-Forwarded-Proto"));
        if (esquemaPropio == null || esquemaPropio.isBlank()) {
            esquemaPropio = request.getURI().getScheme();
        }
        return esquemaPropio == null || esquemaPropio.isBlank()
                || esquemaPropio.equalsIgnoreCase(uriOrigen.getScheme());
    }

    private static boolean mismaAutoridad(String hostPropio, URI origen) {
        String hp = hostPropio.trim().toLowerCase(Locale.ROOT);
        String host = hp;
        int puerto = -1;
        int cierre = hp.startsWith("[") ? hp.indexOf(']') : -1;   // IPv6: [::1]:8084
        int dosPuntos = hp.indexOf(':', cierre < 0 ? 0 : cierre);
        if (dosPuntos >= 0) {
            host = hp.substring(0, dosPuntos);
            try {
                puerto = Integer.parseInt(hp.substring(dosPuntos + 1));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (!host.equalsIgnoreCase(origen.getHost())) {
            return false;
        }
        return puertoEfectivo(puerto, origen.getScheme())
                == puertoEfectivo(origen.getPort(), origen.getScheme());
    }

    private static int puertoEfectivo(int puerto, String esquema) {
        if (puerto >= 0) {
            return puerto;
        }
        return "https".equalsIgnoreCase(esquema) || "wss".equalsIgnoreCase(esquema) ? 443 : 80;
    }

    private static String primero(String valor) {
        return valor == null ? null : valor.split(",")[0].trim();
    }

    /** Configurados + los que el navegador pida en el preflight (práctica habitual). */
    private List<String> headersPermitidos(ServerWebExchange exchange) {
        Set<String> todos = new LinkedHashSet<>(cfg.headersOrDefault());
        String pedidos = exchange.getRequest().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        if (pedidos != null && !pedidos.isBlank()) {
            for (String h : pedidos.split(",")) {
                if (!h.isBlank()) {
                    todos.add(h.trim());
                }
            }
        }
        return new ArrayList<>(todos);
    }
}
