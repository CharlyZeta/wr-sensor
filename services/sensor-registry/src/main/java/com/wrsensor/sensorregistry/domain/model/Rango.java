package com.wrsensor.sensorregistry.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Value object Rango {min, max}. El invariante BR-002 vincula tres instancias
 * (rangoNormal ⊆ rangoWarning ⊆ rangoCritical) mediante anidamiento inclusivo;
 * la validacion de esa regla vive en la capa de aplicacion / dominio, no aqui.
 */
public record Rango(BigDecimal min, BigDecimal max) {

    public Rango {
        Objects.requireNonNull(min, "rango.min");
        Objects.requireNonNull(max, "rango.max");
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("rango.min > rango.max");
        }
    }
}
