package com.wrsensor.alerting.application.service;

import com.wrsensor.alerting.application.port.Notificador;
import com.wrsensor.alerting.domain.AlertaConfirmada;
import com.wrsensor.alerting.domain.EventoAlerta;
import com.wrsensor.alerting.domain.Severidad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motor de histéresis (Main Flow FEAT-0012, BR-001..BR-003/BR-005):
 * subida → confirma inmediato; bajada → candidata con ventana (debounce temporal,
 * decision HO-Gate); una subida durante la ventana cancela la bajada (antiflapping).
 * Estado confirmado y pendientes en memoria por sensor (BR-008, AF-05). Scheduling
 * Reactor (BR-007); sin Spring.
 */
public class GestorAlertas {

    private static final Logger log = LoggerFactory.getLogger(GestorAlertas.class);

    private final Notificador notificador;
    private final Duration ventana;
    private final Scheduler scheduler;

    private static final class EstadoSensor {
        Severidad confirmada = Severidad.NORMAL;
        Disposable pendiente; // bajada en espera (token de cancelacion)
    }

    private final Map<UUID, EstadoSensor> estados = new ConcurrentHashMap<>();

    public GestorAlertas(Notificador notificador, Duration ventana, Scheduler scheduler) {
        this.notificador = notificador;
        this.ventana = ventana;
        this.scheduler = scheduler;
    }

    public GestorAlertas(Notificador notificador, Duration ventana) {
        this(notificador, ventana, Schedulers.parallel());
    }

    /** Procesa un evento (FIFO por sensor garantizado por el caller si es necesario). */
    public void procesar(EventoAlerta evento) {
        EstadoSensor estado = estados.computeIfAbsent(evento.sensorId(), k -> new EstadoSensor());
        synchronized (estado) {
            if (evento.severidadNueva() == estado.confirmada) {
                // Duplicado/out-of-order (AF-01). Con bajada pendiente, un evento con la
                // severidad confirmada es re-subida del sensor: cancela la bajada (AC-004).
                if (estado.pendiente != null) {
                    cancelarPendiente(estado);
                }
                return; // AF-01 / AC-006
            }
            boolean subida = evento.severidadNueva().esMasSeveraQue(estado.confirmada);
            if (subida) {
                cancelarPendiente(estado); // AF-02: subida cancela bajada candidata
                estado.confirmada = evento.severidadNueva();
                notificador.notificar(new AlertaConfirmada(evento.sensorId(), evento.timestamp(), estado.confirmada));
            } else {
                // bajada → candidata con ventana; si llega subida se cancela.
                cancelarPendiente(estado);
                estado.pendiente = Mono.delay(ventana, scheduler)
                        .then(Mono.fromRunnable(() -> confirmarBajada(evento.sensorId(), estado, evento.severidadNueva())))
                        .doOnError(err -> log.error("[alerting] ventana fallo: {}", err.getMessage()))
                        .subscribe();
            }
        }
    }

    private void confirmarBajada(UUID sensorId, EstadoSensor estado, Severidad bajada) {
        synchronized (estado) {
            if (estado.pendiente == null) return; // cancelada por subida posterior
            estado.pendiente = null;
            estado.confirmada = bajada;
            notificador.notificar(new AlertaConfirmada(sensorId, java.time.Instant.now(), bajada));
        }
    }

    private static void cancelarPendiente(EstadoSensor estado) {
        if (estado.pendiente != null) {
            estado.pendiente.dispose();
            estado.pendiente = null;
        }
    }
}

