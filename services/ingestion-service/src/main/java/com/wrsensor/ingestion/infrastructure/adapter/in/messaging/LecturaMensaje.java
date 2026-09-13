package com.wrsensor.ingestion.infrastructure.adapter.in.messaging;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO del payload de `sensor.lecturas` (FIX-0006 BR-006). Se deserializa con Jackson
 * **ignorando propiedades desconocidas**, de modo que un campo nuevo de una versión futura no
 * rompe el consumo (eso es lo que hace real la política de tolerancia hacia adelante).
 *
 * <p>Los campos informativos de {@code calidad} se declaran como {@code Object}: la marca es
 * opcional y tolerante, así que un tipo raro se ignora con WARN en lugar de descartar la
 * lectura (AF-05).</p>
 */
public record LecturaMensaje(
        String schemaVersion,
        String eventId,
        String sensorId,
        String timestamp,
        BigDecimal valor,
        String unidadMedida,
        Long sequence,
        CalidadMensaje calidad
) {

    public record CalidadMensaje(String estado, Object confianza, Object codigosAnomalias) {

        /** Devuelve las advertencias de normalización (campos informativos descartados). */
        public List<String> advertencias() {
            java.util.List<String> avisos = new java.util.ArrayList<>();
            if (confianza != null && confianzaDouble().isEmpty()) {
                avisos.add("calidad.confianza no numerica ni en [0,1]: " + confianza);
            }
            if (codigosAnomalias != null && !(codigosAnomalias instanceof List)) {
                avisos.add("calidad.codigosAnomalias no es un array: " + codigosAnomalias);
            }
            return avisos;
        }

        /** Confianza válida en [0,1]; vacío si el campo es basura o está fuera de rango. */
        public java.util.OptionalDouble confianzaDouble() {
            if (confianza instanceof Number n) {
                double d = n.doubleValue();
                return d >= 0.0 && d <= 1.0 ? java.util.OptionalDouble.of(d) : java.util.OptionalDouble.empty();
            }
            if (confianza instanceof String s) {
                try {
                    double d = Double.parseDouble(s.trim());
                    return d >= 0.0 && d <= 1.0 ? java.util.OptionalDouble.of(d) : java.util.OptionalDouble.empty();
                } catch (NumberFormatException e) {
                    return java.util.OptionalDouble.empty();
                }
            }
            return java.util.OptionalDouble.empty();
        }

        /** Códigos de anomalía válidos (strings); vacío si el campo no es un array. */
        public List<String> codigosValidos() {
            if (!(codigosAnomalias instanceof List<?> lista)) {
                return List.of();
            }
            return lista.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
    }
}
