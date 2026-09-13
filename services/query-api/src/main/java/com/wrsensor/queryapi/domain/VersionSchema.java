package com.wrsensor.queryapi.domain;

import java.util.OptionalInt;

/**
 * Resolución mínima del `schemaVersion` del evento (FIX-0006 BR-006/BR-007) para el consumer
 * tiempo real de `query-api`.
 *
 * <p>Se duplica a propósito la lógica de extracción del número mayor en lugar de compartir un
 * artefacto: los servicios de este proyecto son módulos Maven independientes y sin librería
 * común. La semántica es la misma que en `ingestion-service`: legado (sin versión) y mayor
 * soportada se procesan; una mayor desconocida se procesa con evidencia (tolerancia hacia
 * adelante); una versión no interpretable es un payload inválido.</p>
 */
public final class VersionSchema {

    private VersionSchema() {
    }

    /** Número mayor de {@code "MAYOR[.MENOR[.PARCHE]]"}; vacío si no es interpretable. */
    public static OptionalInt mayorDe(String version) {
        if (version == null || version.isBlank()) {
            return OptionalInt.empty();
        }
        String limpio = version.trim();
        int punto = limpio.indexOf('.');
        String mayor = punto < 0 ? limpio : limpio.substring(0, punto);
        if (mayor.isEmpty() || !mayor.chars().allMatch(Character::isDigit)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(mayor));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** Mayor soportada del consumer (configurable). */
    public static int mayorSoportada(String versionSoportada) {
        return mayorDe(versionSoportada).orElse(1);
    }
}
