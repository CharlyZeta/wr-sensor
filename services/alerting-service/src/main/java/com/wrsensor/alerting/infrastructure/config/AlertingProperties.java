package com.wrsensor.alerting.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Config de alerting (application.yml) — FEAT-0012 BR-005/BR-006. */
@ConfigurationProperties(prefix = "alerting")
public record AlertingProperties(
        long histeresisSegundos,
        Alertas alertas,
        Messaging messaging
) {

    public record Alertas(String exchange, String queue, String dlqQueue, String dlx) {}

    public record Messaging(int retryMaxAttempts, String deadLetterExchange) {}
}
