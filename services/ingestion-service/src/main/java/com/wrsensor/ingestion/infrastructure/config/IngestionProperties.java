package com.wrsensor.ingestion.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Configuracion de ingestion (application.yml) — FEAT-0011 BR-005/BR-006,
 * FIX-0003 (outbox), FIX-0004 (rango fisico) y FIX-0005 (particiones).
 */
@ConfigurationProperties(prefix = "ingestion")
public record IngestionProperties(
        long ventanaSegundos,
        Registry registry,
        Lecturas lecturas,
        Alertas alertas,
        Messaging messaging,
        Outbox outbox,
        RangoFisico rangoFisico,
        Particiones particiones,
        Schema schema
) {

    public record Registry(String baseUrl, Auth auth, Long timeoutMs, Long conexionTimeoutMs,
                          Cache cache, Circuito circuito) {

        public record Auth(String email, String password) {}

        public long timeoutMsOrDefault() {
            return timeoutMs == null ? 2000L : timeoutMs;
        }

        public long conexionTimeoutMsOrDefault() {
            return conexionTimeoutMs == null ? 1000L : conexionTimeoutMs;
        }

        /** FIX-0007 BR-004: TTL de la cache de config de sensores. */
        public record Cache(Long ttlSegundos) {
            public long ttlSegundosOrDefault() {
                return ttlSegundos == null ? 300L : ttlSegundos;
            }
        }

        /** FIX-0007 BR-002: política del circuit breaker. */
        public record Circuito(Integer fallosParaAbrir, Long segundosAbierto, Integer exitosParaCerrar) {
            public int fallosParaAbrirOrDefault() {
                return fallosParaAbrir == null ? 5 : fallosParaAbrir;
            }

            public long segundosAbiertoOrDefault() {
                return segundosAbierto == null ? 30L : segundosAbierto;
            }

            public int exitosParaCerrarOrDefault() {
                return exitosParaCerrar == null ? 2 : exitosParaCerrar;
            }
        }
    }

    /**
     * Topología de lecturas (FIX-0005): el exchange topic y la DLX/DLQ. La cola de consumo
     * ya no es un valor único: se deriva de {@link Particiones#patron()} (BR-003).
     */
    public record Lecturas(String exchange, String dlqQueue, String dlx) {}

    /**
     * Particionamiento del consumo por sensorId (FIX-0005 BR-003/BR-004/BR-005).
     * Nunca hardcodeado: {@code total} y {@code asignadas} se configuran por YAML o entorno.
     *
     * @param total     cantidad de particiones (default 4)
     * @param asignadas particiones que consume esta instancia (default: todas)
     * @param exchange  exchange {@code x-consistent-hash} (default {@code sensor.lecturas.part})
     * @param patron    patrón de nombre de cola, con {@code {i}} (default
     *                  {@code queue.sensor.lecturas.p{i}})
     */
    public record Particiones(Integer total, List<Integer> asignadas, String exchange,
                             String patron) {}

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

    /**
     * Política de versiones del schema del evento (FIX-0006 BR-006/BR-007/BR-011).
     *
     * @param versionSoportada         versión que este consumer entiende (default "1.0")
     * @param tolerarVersionesMayores  true (default) = tolerancia hacia adelante; false =
     *                                 rechazo a la DLQ con motivo SCHEMA_UNSUPPORTED
     */
    public record Schema(String versionSoportada, Boolean tolerarVersionesMayores) {

        public Schema {
            versionSoportada = versionSoportada == null || versionSoportada.isBlank()
                    ? "1.0" : versionSoportada.trim();
        }

        public boolean tolerarMayores() {
            return tolerarVersionesMayores == null || tolerarVersionesMayores;
        }
    }
}
