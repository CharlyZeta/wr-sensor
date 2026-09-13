package com.wrsensor.ingestion.domain;

import java.util.Locale;

/**
 * Política de versiones del schema del evento (FIX-0006 BR-001/BR-006/BR-007/BR-011).
 *
 * <p>Clase de dominio pura: resuelve qué hacer con el {@code schemaVersion} que llegó, sin
 * conocer HTTP, logs ni DLQ. Política adoptada (HO-Gate 2026-09-13): **tolerancia hacia
 * adelante** — una versión mayor desconocida se procesa igual (un publisher más nuevo no debe
 * tumbar la ingesta), dejando evidencia; el rechazo queda para versiones malformadas o cuando
 * se desactiva explícitamente la tolerancia.</p>
 */
public final class EsquemaLectura {

    public static final String VERSION_LEGADO = "0.0";
    public static final String VERSION_SOPORTADA_DEFAULT = "1.0";

    /** Qué representa la versión recibida. */
    public enum Estado {
        /** Sin {@code schemaVersion}: payload plano previo a FIX-0006 (ventana de compatibilidad). */
        LEGADO,
        /** Versión conocida y soportada. */
        SOPORTADA,
        /** Versión mayor a la soportada: se procesa con evidencia (tolerancia hacia adelante). */
        MAYOR_DESCONOCIDA,
        /** {@code schemaVersion} presente pero no interpretable → payload inválido. */
        INVALIDA
    }

    public record Resolucion(Estado estado, String version, int mayor) {

        public boolean procesable(boolean tolerarMayores) {
            return switch (estado) {
                case LEGADO, SOPORTADA -> true;
                case MAYOR_DESCONOCIDA -> tolerarMayores;
                case INVALIDA -> false;
            };
        }
    }

    private EsquemaLectura() {
    }

    /**
     * Resuelve el estado de la versión recibida contra la soportada.
     *
     * @param schemaVersion     valor del campo (puede ser {@code null} = legado)
     * @param versionSoportada  versión que este consumer entiende (configurable)
     */
    public static Resolucion resolver(String schemaVersion, String versionSoportada) {
        int mayorSoportada = mayorDe(versionSoportada).orElse(1);
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return new Resolucion(Estado.LEGADO, VERSION_LEGADO, 0);
        }
        var mayor = mayorDe(schemaVersion);
        if (mayor.isEmpty()) {
            return new Resolucion(Estado.INVALIDA, schemaVersion.trim(), -1);
        }
        int m = mayor.get();
        Estado estado = m > mayorSoportada ? Estado.MAYOR_DESCONOCIDA : Estado.SOPORTADA;
        return new Resolucion(estado, schemaVersion.trim(), m);
    }

    /** Extrae el número mayor de {@code "MAYOR[.MENOR[.PARCHE]]"}; vacío si no es interpretable. */
    public static java.util.Optional<Integer> mayorDe(String version) {
        if (version == null || version.isBlank()) {
            return java.util.Optional.empty();
        }
        String limpio = version.trim();
        int punto = limpio.indexOf('.');
        String mayor = punto < 0 ? limpio : limpio.substring(0, punto);
        if (mayor.isEmpty() || !mayor.chars().allMatch(Character::isDigit)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Integer.parseInt(mayor));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    /** Normaliza para logs/comparaciones ({@code "UNO"} no es una versión válida). */
    public static String normalizar(String version) {
        return version == null ? "" : version.trim().toUpperCase(Locale.ROOT);
    }
}
