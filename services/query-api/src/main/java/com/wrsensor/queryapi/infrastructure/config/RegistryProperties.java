package com.wrsensor.queryapi.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del cliente REST de query-api hacia {@code sensor-registry} (FEAT-0008 BR-007).
 * Todo con default documentado y override por entorno ({@code QUERY_REGISTRY_*}): nada hardcodeado.
 *
 * @param baseUrl          URL base del registry
 * @param auth             credenciales de servicio con rol de lectura (VIEWER)
 * @param timeoutMs        timeout de respuesta **explícito** (nunca el default de la librería)
 * @param conexionTimeoutMs timeout de conexión TCP
 * @param limitPagina      tamaño de página del listado keyset
 * @param maxSensores      tope de seguridad de sensores acumulados (evita un mapa sin fin)
 */
@ConfigurationProperties(prefix = "query.registry")
public record RegistryProperties(String baseUrl, Auth auth, Long timeoutMs, Long conexionTimeoutMs,
                                 Integer limitPagina, Integer maxSensores) {

    /** Credenciales del usuario de servicio (rol VIEWER) usadas para leer el listado. */
    public record Auth(String email, String password) {}

    public String baseUrlOrDefault() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl.trim();
    }

    public Auth authOrDefault() {
        return auth == null ? new Auth("viewer@wrsensor.local", "Viewer123!") : auth;
    }

    public long timeoutMsOrDefault() {
        return timeoutMs == null ? 5_000L : timeoutMs;
    }

    public long conexionTimeoutMsOrDefault() {
        return conexionTimeoutMs == null ? 2_000L : conexionTimeoutMs;
    }

    public int limitPaginaOrDefault() {
        return (limitPagina == null || limitPagina < 1 || limitPagina > 1000) ? 200 : limitPagina;
    }

    public int maxSensoresOrDefault() {
        return (maxSensores == null || maxSensores < 1) ? 5_000 : maxSensores;
    }
}
