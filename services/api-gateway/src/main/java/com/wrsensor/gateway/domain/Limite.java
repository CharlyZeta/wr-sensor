package com.wrsensor.gateway.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Clase de límite (FEAT-0007 BR-004): peticiones por ventana y burst del token bucket.
 * Valores siempre configurables, nunca hardcodeados.
 */
public record Limite(String clase, int peticiones, int ventanaSegundos, int burst) {

    public Limite {
        Objects.requireNonNull(clase, "clase");
        if (peticiones <= 0) {
            throw new IllegalStateException("gateway.rate-limit.clases." + clase
                    + ".peticiones invalido: " + peticiones + " (debe ser > 0)");
        }
        if (ventanaSegundos <= 0) {
            throw new IllegalStateException("gateway.rate-limit.clases." + clase
                    + ".ventana-segundos invalido: " + ventanaSegundos + " (debe ser > 0)");
        }
        if (burst <= 0) {
            throw new IllegalStateException("gateway.rate-limit.clases." + clase
                    + ".burst invalido: " + burst + " (debe ser > 0)");
        }
    }

    /** Tokens que se recargan por segundo. */
    public double recargaPorSegundo() {
        return (double) peticiones / ventanaSegundos;
    }

    /** Tiempo estimado para consumir {@code cantidad} peticiones desde el balde lleno. */
    public java.time.Duration tiempoDeConsumo(int cantidad) {
        return java.time.Duration.ofMillis(
                (long) Math.ceil(cantidad / recargaPorSegundo() * 1000.0));
    }
}
