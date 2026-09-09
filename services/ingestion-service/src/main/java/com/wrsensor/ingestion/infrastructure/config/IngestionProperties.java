package com.wrsensor.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de ingestion (application.yml) — FEAT-0011 BR-005/BR-006.
 */
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        long ventanaSegundos,
        Registry registry,
        Lecturas lecturas,
        Alertas alertas,
        Messaging messaging
) {

    public record Registry(String baseUrl, Auth auth) {
        public record Auth(String email, String password) {}
    }

    public record Lecturas(String exchange, String queue, String dlqQueue, String dlx) {}

    public record Alertas(String exchange) {}

    public record Messaging(int retryMaxAttempts, String deadLetterExchange) {}
}
