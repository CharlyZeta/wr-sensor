package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Sirve el SPA y sus assets desde {@code classpath:/static/} (FIX-0008 BR-005/BR-006/BR-007).
 *
 * <p><b>Resolución contenida:</b> el path del request se valida y se resuelve **siempre** dentro de
 * la raíz configurada (`gateway.seguridad.static-location`): se rechaza cualquier intento de salir
 * (segmentos {@code ..} —incluidos los codificados, que Spring ya decodifica—, backslash, byte nulo o
 * ruta absoluta). El handler no usa query ni headers del request para decidir qué sirve.</p>
 *
 * <p><b>Nunca rompe el contrato de la API:</b> {@code /api/**} y {@code /ws/**} los resuelve el router
 * antes que este handler; acá un path con extensión de asset que no existe responde {@code 404}
 * (jamás el índice), y sólo las rutas de cliente configuradas devuelven {@code index.html}.</p>
 *
 * <p><b>Caché explícita:</b> el índice va con {@code no-store} (el SPA se actualiza al desplegar) y
 * los assets con hash de Vite, con caché inmutable.</p>
 */
public class ServidorSpa implements HandlerFunction<ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ServidorSpa.class);

    /** Extensión de archivo: si el path la tiene, es un asset y nunca cae al índice. */
    private static final Pattern EXTENSION = Pattern.compile(".*\\.[A-Za-z0-9]{1,10}$");
    private static final String INDICE = "index.html";

    private final GatewayProperties.SeguridadCfg cfg;

    public ServidorSpa(GatewayProperties.SeguridadCfg cfg) {
        this.cfg = cfg;
    }

    @Override
    public Mono<ServerResponse> handle(ServerRequest request) {
        ServerWebExchange exchange = request.exchange();
        String path = exchange.getRequest().getPath().value();
        if (!pathSeguro(path)) {
            log.warn("[gateway] path rechazado para estáticos: {}", describir(path));
            return noEncontrado(exchange);
        }
        // Defensa en profundidad (BR-006): los prefijos reservados de la API y del WebSocket NUNCA
        // se resuelven con el índice del SPA, aunque el router los deje pasar (p. ej. una ruta
        // /ws/** no declarada). Antes respondían 404 ROUTE_NOT_FOUND y eso no debe cambiar.
        if (esPrefijoReservado(path)) {
            return noEncontrado(exchange);
        }
        String relativo = normalizar(path);
        if (relativo.isEmpty() || esRutaDeCliente(path)) {
            return indice(exchange);
        }
        return recurso(relativo)
                .map(this::servir)
                .orElseGet(() -> EXTENSION.matcher(relativo).matches()
                        ? noEncontrado(exchange)
                        : indice(exchange));   // ruta de cliente: la resuelve el router del SPA
    }

    /**
     * Prefijos que pertenecen a la API o al WebSocket: nunca devuelven el índice del SPA. Es una
     * segunda barrera: el router ya los resuelve antes, pero un path no declarado debe seguir siendo
     * {@code 404 ROUTE_NOT_FOUND} y jamás {@code 200 text/html}.
     */
    public static boolean esPrefijoReservado(String path) {
        return path != null && (path.equals("/api") || path.startsWith("/api/")
                || path.equals("/ws") || path.startsWith("/ws/"));
    }

    /** ¿El path corresponde a una ruta del cliente (fallback al índice)? */
    public boolean esRutaDeCliente(String path) {
        String p = path == null ? "" : path;
        if (p.equals("/") || p.isEmpty()) {
            return true;
        }
        for (String prefijo : cfg.rutasClienteOrDefault()) {
            if (prefijo.equals("/")) {
                continue;   // ya cubierto por el caso raíz; "/" como prefijo de todo no aporta
            }
            if (p.equals(prefijo) || p.startsWith(prefijo + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Defensa en profundidad: sólo paths relativos, sin escape de la raíz y sin caracteres raros.
     * Spring ya decodifica el path, así que {@code %2e%2e} llega como {@code ..}.
     */
    public boolean pathSeguro(String path) {
        if (path == null || path.isBlank() || path.length() > 512) {
            return false;
        }
        String p = path.replace('\\', '/');
        if (p.contains("..") || p.contains("\0") || p.contains("//")) {
            return false;
        }
        for (String segmento : p.split("/")) {
            if (segmento.contains(":") || segmento.startsWith("~")) {
                return false;   // "C:" o "~/" no tienen sentido en una URL de assets
            }
        }
        return true;
    }

    private static String normalizar(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    private java.util.Optional<Resource> recurso(String relativo) {
        Resource r = new ClassPathResource(cfg.staticLocationOrDefault() + relativo);
        try {
            return r.exists() && r.isReadable() && !r.getFile().isDirectory()
                    ? java.util.Optional.of(r)
                    : java.util.Optional.empty();
        } catch (Exception e) {
            // Un directorio dentro de un jar no es "File": se trata como no servible.
            return java.util.Optional.empty();
        }
    }

    private Mono<ServerResponse> servir(Resource recurso) {
        MediaType tipo = MediaTypeFactory.getMediaType(recurso)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ServerResponse.ok()
                .contentType(tipo)
                .header(HttpHeaders.CACHE_CONTROL, cacheDe(recurso.getFilename()))
                .bodyValue(recurso);
    }

    private String cacheDe(String nombre) {
        String n = nombre == null ? "" : nombre;
        // Vite emite /assets/<nombre>-<hash>.<ext>: inmutable. El índice nunca se cachea.
        if (n.contains("-") && n.matches(".*-[A-Za-z0-9_]{8,}\\..{1,10}$")) {
            return "public, max-age=" + cfg.cacheAssetsSegundosOrDefault() + ", immutable";
        }
        return "public, max-age=" + cfg.cacheEstaticosSegundosOrDefault();
    }

    private Mono<ServerResponse> indice(ServerWebExchange exchange) {
        Resource indice = new ClassPathResource(cfg.staticLocationOrDefault() + INDICE);
        try {
            if (!indice.exists() || !indice.isReadable()) {
                log.warn("[gateway] no hay {} en {} (el SPA no está construido)",
                        INDICE, cfg.staticLocationOrDefault());
                return noEncontrado(exchange);
            }
        } catch (Exception e) {
            return noEncontrado(exchange);
        }
        return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .bodyValue(indice);
    }

    private Mono<ServerResponse> noEncontrado(ServerWebExchange exchange) {
        return RespuestasGateway.error(exchange, HttpStatus.NOT_FOUND, CodigosError.ROUTE_NOT_FOUND,
                "no existe el recurso pedido en el gateway");
    }

    private static String describir(String path) {
        String p = path == null ? "null" : path;
        return p.length() > 120 ? p.substring(0, 120) + "…" : p;
    }

    /** Rutas de cliente configuradas (para documentación y tests). */
    public List<String> rutasDeCliente() {
        return cfg.rutasClienteOrDefault();
    }
}