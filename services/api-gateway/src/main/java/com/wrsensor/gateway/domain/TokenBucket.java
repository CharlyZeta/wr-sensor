package com.wrsensor.gateway.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Token bucket (FEAT-0007 BR-005/BR-006): capacidad = {@code burst}, recarga continua a
 * {@code peticiones / ventana} por segundo. Sin dependencias de framework: recibe el instante
 * (reloj inyectado) para ser testeable sin esperar ventanas reales.
 */
public final class TokenBucket {

    private final long capacidad;
    private final double recargaPorSegundo;
    private double tokens;
    private Instant ultimaRecarga;

    public TokenBucket(Limite limite, Instant ahora) {
        this.capacidad = limite.burst();
        this.recargaPorSegundo = limite.recargaPorSegundo();
        this.tokens = limite.burst();
        this.ultimaRecarga = ahora;
    }

    /** Resultado de un intento de consumo. */
    public record Veredicto(boolean permitido, long limite, long restante, long retryAfterSegundos) {}

    /** Intenta consumir un token; devuelve el veredicto con lo necesario para los headers. */
    public synchronized Veredicto intentar(Instant ahora) {
        recargar(ahora);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return new Veredicto(true, capacidad, (long) Math.floor(tokens), 0L);
        }
        double faltante = 1.0 - tokens;
        long retryAfter = Math.max(1L, (long) Math.ceil(faltante / recargaPorSegundo));
        return new Veredicto(false, capacidad, 0L, retryAfter);
    }

    private void recargar(Instant ahora) {
        Duration transcurrido = Duration.between(ultimaRecarga, ahora);
        if (transcurrido.isNegative() || transcurrido.isZero()) {
            return;
        }
        double segundos = transcurrido.toNanos() / 1_000_000_000.0;
        tokens = Math.min(capacidad, tokens + segundos * recargaPorSegundo);
        ultimaRecarga = ahora;
    }

    /** Tokens disponibles (observabilidad/tests). */
    public synchronized double tokens() {
        return tokens;
    }
}
