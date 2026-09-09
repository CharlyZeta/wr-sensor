package com.wrsensor.alerting;

import com.wrsensor.alerting.application.port.Notificador;
import com.wrsensor.alerting.application.service.GestorAlertas;
import com.wrsensor.alerting.domain.AlertaConfirmada;
import com.wrsensor.alerting.domain.EventoAlerta;
import com.wrsensor.alerting.domain.Severidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0012 GestorAlertas (histéresis con ventana real corta).
 * Cubre BR-001..003/005 y AC-001..AC-006.
 */
class GestorAlertasTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final Duration VENTANA = Duration.ofMillis(150);

    private static final class Captor implements Notificador {
        final List<AlertaConfirmada> alertas = new CopyOnWriteArrayList<>();

        @Override
        public void notificar(AlertaConfirmada a) {
            alertas.add(a);
        }
    }

    private record H(GestorAlertas gestor, Captor captor) {}

    private static H newH() {
        Captor c = new Captor();
        return new H(new GestorAlertas(c, VENTANA), c);
    }

    private static EventoAlerta evento(Severidad anterior, Severidad nueva) {
        return new EventoAlerta(ID, Instant.now(), new BigDecimal("5.0"), anterior, nueva, false);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("AC-001: subida NORMAL→WARNING notifica inmediato")
    void ac001_subidaInmediata() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING));
        assertThat(h.captor().alertas).hasSize(1);
        assertThat(h.captor().alertas.get(0).severidadNueva()).isEqualTo(Severidad.WARNING);
    }

    @Test
    @DisplayName("AC-005: subida directa NORMAL→CRITICAL notifica inmediato")
    void ac005_subidaDirecta() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.CRITICAL));
        assertThat(h.captor().alertas).hasSize(1);
        assertThat(h.captor().alertas.get(0).severidadNueva()).isEqualTo(Severidad.CRITICAL);
    }

    @Test
    @DisplayName("AC-002: bajada WARNING→NORMAL no notifica al instante")
    void ac002_bajadaNoInmediata() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING)); // confirmado WARNING
        assertThat(h.captor().alertas).hasSize(1);

        h.gestor().procesar(evento(Severidad.WARNING, Severidad.NORMAL));
        dormir(60); // dentro de la ventana
        assertThat(h.captor().alertas).hasSize(1); // sigue sin notificarse
    }

    @Test
    @DisplayName("AC-003: bajada se confirma al expirar la ventana sin eventos contrarios")
    void ac003_bajadaConfirmadaPorVentana() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING));
        h.gestor().procesar(evento(Severidad.WARNING, Severidad.NORMAL));
        dormir(500); // > ventana 150ms
        assertThat(h.captor().alertas).hasSize(2);
        assertThat(h.captor().alertas.get(1).severidadNueva()).isEqualTo(Severidad.NORMAL);
    }

    @Test
    @DisplayName("AC-004 / AF-02: re-subida dentro de la ventana cancela la bajada (antiflapping)")
    void ac004_antiflapping() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING)); // confirmado WARNING (1)
        h.gestor().procesar(evento(Severidad.WARNING, Severidad.NORMAL)); // bajada pendiente
        dormir(60);
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING)); // re-subida (cancela)
        dormir(500);

        // Solo la notificacion WARNING inicial; la bajada nunca se confirma.
        assertThat(h.captor().alertas).hasSize(1);
        assertThat(h.captor().alertas.get(0).severidadNueva()).isEqualTo(Severidad.WARNING);
    }

    @Test
    @DisplayName("AF-01 / AC-006: evento con misma severidad confirmada se ignora")
    void ac006_duplicadoIgnorado() {
        H h = newH();
        h.gestor().procesar(evento(Severidad.NORMAL, Severidad.WARNING));
        h.gestor().procesar(evento(Severidad.WARNING, Severidad.WARNING)); // duplicado (out-of-order)
        assertThat(h.captor().alertas).hasSize(1);
    }
}
