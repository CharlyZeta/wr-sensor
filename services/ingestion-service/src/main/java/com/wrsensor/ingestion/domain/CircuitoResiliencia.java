package com.wrsensor.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Circuit breaker propio del lookup de config de sensores (FIX-0007 BR-002/BR-003/BR-007).
 *
 * <p>Máquina de estados {@code CERRADO → ABIERTO → SEMIABIERTO → (CERRADO | ABIERTO)} con
 * **reloj inyectado** (testeable sin esperar ventanas reales) y un {@code observador} de
 * transiciones para que el adapter loguee WARN/INFO sin que el dominio conozca el logging.</p>
 *
 * <ul>
 *   <li>{@code CERRADO}: los llamados pasan; {@code fallosParaAbrir} fallos consecutivos abren.</li>
 *   <li>{@code ABIERTO}: los llamados se cortan (no hay red); tras {@code segundosAbierto} el
 *       siguiente {@link #permitir(Instant)} pasa a {@code SEMIABIERTO} y deja pasar un sondeo.</li>
 *   <li>{@code SEMIABIERTO}: pasan los sondeos; {@code exitosParaCerrar} éxitos consecutivos
 *       cierran; un fallo reabre de inmediato.</li>
 * </ul>
 *
 * <p>Es una clase de dominio pura: no conoce HTTP, Redis ni Spring.</p>
 */
public final class CircuitoResiliencia {

    public enum Estado { CERRADO, ABIERTO, SEMIABIERTO }

    private static final Consumer<String> SIN_OBSERVADOR = transicion -> { };

    private final int fallosParaAbrir;
    private final Duration segundosAbierto;
    private final int exitosParaCerrar;
    private final Consumer<String> observador;

    private Estado estado = Estado.CERRADO;
    private int fallosConsecutivos = 0;
    private int exitosConsecutivos = 0;
    private Instant abiertoHasta = null;
    private long llamadas = 0;

    public CircuitoResiliencia(int fallosParaAbrir, Duration segundosAbierto, int exitosParaCerrar) {
        this(fallosParaAbrir, segundosAbierto, exitosParaCerrar, SIN_OBSERVADOR);
    }

    public CircuitoResiliencia(int fallosParaAbrir, Duration segundosAbierto, int exitosParaCerrar,
                               Consumer<String> observador) {
        if (fallosParaAbrir <= 0) {
            throw new IllegalStateException("fallos-para-abrir invalido: " + fallosParaAbrir);
        }
        if (segundosAbierto == null || segundosAbierto.isNegative() || segundosAbierto.isZero()) {
            throw new IllegalStateException("segundos-abierto invalido: " + segundosAbierto);
        }
        if (exitosParaCerrar <= 0) {
            throw new IllegalStateException("exitos-para-cerrar invalido: " + exitosParaCerrar);
        }
        this.fallosParaAbrir = fallosParaAbrir;
        this.segundosAbierto = segundosAbierto;
        this.exitosParaCerrar = exitosParaCerrar;
        this.observador = observador == null ? SIN_OBSERVADOR : observador;
    }

    /** ¿Se permite la llamada en {@code ahora}? Transiciona ABIERTO→SEMIABIERTO al vencer la ventana. */
    public synchronized boolean permitir(Instant ahora) {
        llamadas++;
        if (estado == Estado.ABIERTO && ahora != null && abiertoHasta != null
                && ahora.isAfter(abiertoHasta)) {
            estado = Estado.SEMIABIERTO;
            exitosConsecutivos = 0;
            observador.accept(estado.name());
        }
        return estado != Estado.ABIERTO;
    }

    /** Registra un éxito: resetea fallos en CERRADO y cierra el circuito en SEMIABIERTO. */
    public synchronized void registrarExito(Instant ahora) {
        switch (estado) {
            case CERRADO -> fallosConsecutivos = 0;
            case SEMIABIERTO -> {
                if (++exitosConsecutivos >= exitosParaCerrar) {
                    estado = Estado.CERRADO;
                    fallosConsecutivos = 0;
                    exitosConsecutivos = 0;
                    observador.accept(estado.name());
                }
            }
            case ABIERTO -> { /* no debería haber éxito sin permitir */ }
        }
    }

    /** Registra un fallo: abre en CERRADO tras el umbral, reabre en SEMIABIERTO, ignora en ABIERTO. */
    public synchronized void registrarFallo(Instant ahora) {
        switch (estado) {
            case CERRADO -> {
                if (++fallosConsecutivos >= fallosParaAbrir) {
                    abrir(ahora);
                }
            }
            case SEMIABIERTO -> abrir(ahora);
            case ABIERTO -> { /* ya abierto */ }
        }
    }

    private void abrir(Instant ahora) {
        estado = Estado.ABIERTO;
        fallosConsecutivos = fallosParaAbrir;
        exitosConsecutivos = 0;
        abiertoHasta = ahora.plus(segundosAbierto);
        observador.accept(estado.name());
    }

    public synchronized Estado estado() {
        return estado;
    }

    public synchronized int fallosConsecutivos() {
        return fallosConsecutivos;
    }

    public synchronized long llamadas() {
        return llamadas;
    }

    /** Vuelve a CERRADO y limpia los contadores (tests / reinicio operativo controlado). */
    public synchronized void reset() {
        estado = Estado.CERRADO;
        fallosConsecutivos = 0;
        exitosConsecutivos = 0;
        abiertoHasta = null;
    }
}
