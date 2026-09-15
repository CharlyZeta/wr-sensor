package com.wrsensor.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuración del gateway (FEAT-0007 BR-004/BR-012/BR-013): todo configurable y con defaults
 * documentados en {@code application.yml}; nada hardcodeado en el código.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(List<RutaCfg> rutas, RateLimitCfg rateLimit, CorsCfg cors, WsCfg ws) {

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
}
