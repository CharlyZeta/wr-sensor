package com.wrsensor.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuración del gateway (FEAT-0007 BR-004/BR-012/BR-013): todo configurable y con defaults
 * documentados en {@code application.yml}; nada hardcodeado en el código.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(List<RutaCfg> rutas, RateLimitCfg rateLimit, CorsCfg cors, WsCfg ws,
                                SeguridadCfg seguridad) {

    /**
     * Ruta declarada: patrón de path, métodos (vacío = todos), destino y clase de límite.
     */
    public record RutaCfg(String id, String patron, List<String> metodos, String destino,
                          String claseLimite, Long timeoutMs) {

        public long timeoutMsOrDefault() {
            return timeoutMs == null ? 10_000L : timeoutMs;
        }

        public String claseLimiteOrDefault() {
            return claseLimite == null || claseLimite.isBlank() ? "default" : claseLimite.trim();
        }
    }

    /**
     * Rate limiting: clases con sus límites (login más estricta), expiración de claves y
     * confianza en {@code X-Forwarded-For} (por default false: el gateway es el primer salto).
     */
    public record RateLimitCfg(Boolean confiarForwardedFor, Long expiracionSegundos,
                               Map<String, LimiteCfg> clases) {

        public boolean confiarForwardedForOrDefault() {
            return confiarForwardedFor != null && confiarForwardedFor;
        }

        public long expiracionSegundosOrDefault() {
            return expiracionSegundos == null ? 300L : expiracionSegundos;
        }
    }

    public record LimiteCfg(Integer peticiones, Integer ventanaSegundos, Integer burst) {}

    /**
     * CORS (FEAT-0008 BR-001): lista de orígenes permitidos vacía = mismo origen únicamente (el
     * default seguro; el SPA en dev se habilita con {@code GATEWAY_CORS_ORIGENES}).
     */
    public record CorsCfg(List<String> origenes, List<String> metodos, List<String> headers,
                          List<String> headersExpuestos, Long maxAgeSegundos,
                          Boolean permitirCredenciales) {

        public List<String> origenesOrDefault() {
            return origenes == null ? List.of()
                    : origenes.stream().filter(o -> o != null && !o.isBlank())
                            .map(String::trim).toList();
        }

        public List<String> metodosOrDefault() {
            return metodos == null || metodos.isEmpty()
                    ? List.of("GET", "POST", "PUT", "DELETE", "OPTIONS") : List.copyOf(metodos);
        }

        public List<String> headersOrDefault() {
            return headers == null || headers.isEmpty()
                    ? List.of("Authorization", "Content-Type", "X-Correlation-Id")
                    : List.copyOf(headers);
        }

        public List<String> headersExpuestosOrDefault() {
            return headersExpuestos == null || headersExpuestos.isEmpty()
                    ? List.of("X-Correlation-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                            "Retry-After")
                    : List.copyOf(headersExpuestos);
        }

        public long maxAgeSegundosOrDefault() {
            return maxAgeSegundos == null ? 3600L : maxAgeSegundos;
        }

        public boolean permitirCredencialesOrDefault() {
            return permitirCredenciales != null && permitirCredenciales;
        }
    }

    /**
     * Handshake WebSocket autenticado (FEAT-0008 BR-003/BR-004): roles habilitados, nombre del query
     * param que transporta el token (los navegadores no pueden enviar {@code Authorization} en el
     * upgrade) y secreto HS256 compartido con {@code sensor-registry}.
     */
    public record WsCfg(List<String> rolesPermitidos, String parametroToken, String jwtSecreto) {

        public List<String> rolesPermitidosOrDefault() {
            return rolesPermitidos == null || rolesPermitidos.isEmpty()
                    ? List.of("ADMIN", "VIEWER") : List.copyOf(rolesPermitidos);
        }

        public String parametroTokenOrDefault() {
            return parametroToken == null || parametroToken.isBlank() ? "token" : parametroToken.trim();
        }
    }

    /**
     * Endurecimiento del punto de entrada (FIX-0008 BR-002/BR-003/BR-004/BR-006/BR-007): headers de
     * seguridad, CSP, rutas de cliente que resuelve el SPA y caché de los estáticos. Todo con default
     * documentado y overrides por entorno.
     *
     * @param perfilesDesarrollo perfiles en los que se tolera el secreto de desarrollo del WS
     * @param contentSecurityPolicy CSP aplicada a toda respuesta (null/blank = default seguro)
     * @param rutasCliente       prefijos de rutas que devuelven el índice del SPA (fallback de cliente)
     * @param staticLocation     raíz classpath de los estáticos (nunca se sale de acá)
     * @param cacheAssetsSegundos caché de los assets versionados por hash
     * @param cacheEstaticosSegundos caché del resto de los estáticos (no el índice)
     */
    public record SeguridadCfg(List<String> perfilesDesarrollo, String contentSecurityPolicy,
                               List<String> rutasCliente, String staticLocation,
                               Long cacheAssetsSegundos, Long cacheEstaticosSegundos,
                               String permisosPolitica, Boolean hsts, Long hstsMaxAgeSegundos) {

        public static final String SECRETO_DESARROLLO = "wrsensor-dev-secret-2026-no-usar-en-prod";

        public List<String> perfilesDesarrolloOrDefault() {
            return perfilesDesarrollo == null || perfilesDesarrollo.isEmpty()
                    ? List.of("dev", "local", "test") : List.copyOf(perfilesDesarrollo);
        }

        public String staticLocationOrDefault() {
            return staticLocation == null || staticLocation.isBlank() ? "static/" : staticLocation.trim();
        }

        public List<String> rutasClienteOrDefault() {
            return rutasCliente == null || rutasCliente.isEmpty()
                    ? List.of("/", "/login", "/mapa", "/sensores") : List.copyOf(rutasCliente);
        }

        public String permisosPoliticaOrDefault() {
            return permisosPolitica == null || permisosPolitica.isBlank()
                    ? "geolocation=(), camera=(), microphone=()" : permisosPolitica.trim();
        }

        public boolean hstsOrDefault() {
            return hsts == null || hsts;
        }

        public long hstsMaxAgeSegundosOrDefault() {
            return hstsMaxAgeSegundos == null ? 31_536_000L : hstsMaxAgeSegundos;
        }

        public long cacheAssetsSegundosOrDefault() {
            return cacheAssetsSegundos == null ? 31_536_000L : cacheAssetsSegundos;
        }

        public long cacheEstaticosSegundosOrDefault() {
            return cacheEstaticosSegundos == null ? 3_600L : cacheEstaticosSegundos;
        }

        /**
         * CSP por default: sin {@code unsafe-inline}/{@code unsafe-eval} en {@code script-src}
         * (ahí está el vector que roba el token), {@code img-src} acotado a lo propio, datos e
         * imágenes, y {@code connect-src 'self'} (cubre las WS del mismo origen).
         */
        public String contentSecurityPolicyOrDefault() {
            return contentSecurityPolicy == null || contentSecurityPolicy.isBlank()
                    ? "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data: https://tile.openstreetmap.org "
                            + "https://*.tile.openstreetmap.org; font-src 'self' data:; "
                            + "connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'; "
                            + "form-action 'self'; frame-ancestors 'none'"
                    : contentSecurityPolicy.trim();
        }
    }
}
