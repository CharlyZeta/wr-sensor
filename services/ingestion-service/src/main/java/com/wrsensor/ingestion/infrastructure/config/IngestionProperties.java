package com.wrsensor.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de ingestion (application.yml) — FEAT-0011 BR-005/BR-006 y
 * FIX-0003 BR-004..BR-008 (outbox).
 */
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        long ventanaSegundos,
        Registry registry,
        Lecturas lecturas,
        Alertas alertas,
        Messaging messaging,
        Outbox outbox
) {

    public record Registry(String baseUrl, Auth auth) {
        public record Auth(String email, String password) {}
    }

    public record Lecturas(String exchange, String queue, String dlqQueue, String dlx) {}

    public record Alertas(String exchange) {}

    public record Messaging(int retryMaxAttempts, String deadLetterExchange) {}

    /**
     * Parametros del publisher de outbox (nunca hardcodeados, BR-007).
     * Constructor compacto: aplica defaults cuando la config no los provee.
     */
    public record Outbox(Long intervaloMs, Integer tamanoLote, Integer maxIntentos,
                         Long backoffInicialMs, Double multiplicador, Long backoffMaxMs,
                         Integer retencionDias) {

        public Outbox {
            intervaloMs = intervaloMs == null ? 1000L : intervaloMs;
            tamanoLote = tamanoLote == null ? 50 : tamanoLote;
            maxIntentos = maxIntentos == null ? 5 : maxIntentos;
            backoffInicialMs = backoffInicialMs == null ? 1000L : backoffInicialMs;
            multiplicador = multiplicador == null ? 2.0 : multiplicador;
            backoffMaxMs = backoffMaxMs == null ? 30_000L : backoffMaxMs;
            retencionDias = retencionDias == null ? 90 : retencionDias;
        }
    }
}
