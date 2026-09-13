package com.wrsensor.gateway.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter en memoria (FEAT-0007 BR-005/BR-007): un {@link TokenBucket} por clave
 * {@code clase + IP de origen}. Las claves inactivas se liberan por expiración para que la
 * memoria no crezca sin límite (AC-012). v1 es por instancia
 * (el rate limiting distribuido con Redis queda fuera de alcance).
 */
public final class RateLimiterEnMemoria {

    private final Duration expiracion;
    private final Map<String, Entrada> cubetas = new ConcurrentHashMap<>();
    private final AtomicLong evaluaciones = new AtomicLong();

    public RateLimiterEnMemoria(Duration expiracion) {
        this.expiracion = expiracion;
    }

    private static final class Entrada {
        final TokenBucket cubeta;
        volatile Instant ultimoUso;

        Entrada(TokenBucket cubeta, Instant ultimoUso) {
            this.cubeta = cubeta;
            this.ultimoUso = ultimoUso;
        }
    }

    /** Evalúa el consumo de un token para {@code clave} con {@code limite}. */
    public TokenBucket.Veredicto evaluar(String clave, Limite limite, Instant ahora) {
        purgar(ahora);
        Entrada entrada = cubetas.compute(clave, (k, actual) -> {
            if (actual == null) {
                return new Entrada(new TokenBucket(limite, ahora), ahora);
            }
            actual.ultimoUso = ahora;
            return actual;
        });
        TokenBucket.Veredicto veredicto = entrada.cubeta.intentar(ahora);
        entrada.ultimoUso = ahora;
        evaluaciones.incrementAndGet();
        return veredicto;
    }

    /** Claves activas (AC-012 / observabilidad). */
    public int clavesActivas() {
        return cubetas.size();
    }

    public long evaluaciones() {
        return evaluaciones.get();
    }

    /** Vacía todos los contadores (sólo para tests y reinicio operativo controlado). */
    public void limpiar() {
        cubetas.clear();
    }

    /** Elimina las claves sin uso dentro de la ventana de expiración (BR-007). */
    public void purgar(Instant ahora) {
        cubetas.entrySet().removeIf(e ->
                Duration.between(e.getValue().ultimoUso, ahora).compareTo(expiracion) > 0);
    }
}
