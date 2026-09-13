package com.wrsensor.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuración del gateway (FEAT-0007 BR-004/BR-012/BR-013): todo configurable y con defaults
 * documentados en {@code application.yml}; nada hardcodeado en el código.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(List<RutaCfg> rutas, RateLimitCfg rateLimit) {

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
}
