package com.wrsensor.queryapi.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fila del resumen del mapa (FEAT-0008 BR-005): metadata del sensor + su última lectura.
 *
 * <p>{@code ultimaLectura} es {@code null} cuando el sensor no tiene ninguna lectura (AF-05): el
 * sensor **no** se omite, porque el mapa tiene que poder dibujarlo (en gris) aunque nunca haya
 * reportado.</p>
 */
public record ResumenSensor(UUID id, String codigo, String nombre, String tipo, BigDecimal latitud,
                            BigDecimal longitud, String estado, String unidadMedida,
                            Lectura ultimaLectura) {

    /** Última lectura: sólo los cuatro campos que consume el mapa/tabla del SPA. */
    public record Lectura(BigDecimal valor, Instant timestamp, String severidad, String calidad) {}

    public static ResumenSensor de(SensorMetadata meta, LecturaConsulta ultima) {
        return new ResumenSensor(meta.id(), meta.codigo(), meta.nombre(), meta.tipo(), meta.latitud(),
                meta.longitud(), meta.estado(), meta.unidadMedida(), ultima == null ? null
                        : new Lectura(ultima.valor(), ultima.timestamp(), ultima.severidad(),
                                ultima.calidad()));
    }
}
