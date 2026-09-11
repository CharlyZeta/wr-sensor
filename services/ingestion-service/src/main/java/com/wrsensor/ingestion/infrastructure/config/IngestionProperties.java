package com.wrsensor.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Configuracion de ingestion (application.yml) — FEAT-0011 BR-005/BR-006,
 * FIX-0003 (outbox) y FIX-0004 (rango fisico).
 */
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        long ventanaSegundos,
        Registry registry,
        Lecturas lecturas,
        Alertas alertas,
        Messaging messaging,
        Outbox outbox,
        RangoFisico rangoFisico
) {

    public record Registry(String baseUrl, Auth auth) {
        public record Auth(String email, String password) {}
    }

    public record Lecturas(String exchange, String queue, String dlqQueue, String dlx) {}

    public record Alertas(String exchange) {}

    public record Messaging(int retryMaxAttempts, String deadLetterExchange) {}

    /**
     * Parametros del publisher de outbox (nunca hardcodeados, BR-007).
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

    /**
     * Rangos fisicos (FIX-0004 BR-001/BR-002): globales por unidad de medida y
     * overrides por sensor (clave = sensorId o codigo). Nunca hardcodeados.
     */
    public record RangoFisico(Map<String, Rango> unidades, Map<String, Rango> overrides) {

        public record Rango(BigDecimal min, BigDecimal max) {}
    }
}
