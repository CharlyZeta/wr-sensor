package com.wrsensor.ingestion;

import com.wrsensor.ingestion.domain.CircuitoResiliencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-0007 — máquina de estados del circuit breaker (unit tests BR-002, BR-003, AF-04, AF-06 y
 * AC-008), con reloj inyectado.
 */
class CircuitoResilienciaTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");

    private static CircuitoResiliencia breaker(List<String> transiciones) {
        return new CircuitoResiliencia(5, Duration.ofSeconds(30), 2, transiciones::add);
    }

    @Test
    @DisplayName("BR-002: tras 5 fallos consecutivos el circuito abre y corta los llamados")
    void br002_abreTrasUmbral() {
        List<String> transiciones = new ArrayList<>();
        CircuitoResiliencia c = breaker(transiciones);

        for (int i = 0; i < 5; i++) {
            assertThat(c.permitir(T0)).isTrue();
            c.registrarFallo(T0);
        }
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.ABIERTO);
        assertThat(transiciones).contains("ABIERTO");

        // mientras esté abierto no se permite tráfico
        assertThat(c.permitir(T0.plusSeconds(10))).isFalse();
        assertThat(c.permitir(T0.plusSeconds(29))).isFalse();
    }

    @Test
    @DisplayName("AF-04: fallos por debajo del umbral no abren y un éxito resetea el contador")
    void af04_noAbreYResetea() {
        CircuitoResiliencia c = breaker(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            c.registrarFallo(T0);
        }
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.CERRADO);
        assertThat(c.fallosConsecutivos()).isEqualTo(4);

        c.registrarExito(T0);
        assertThat(c.fallosConsecutivos()).as("un éxito resetea los fallos consecutivos").isZero();
    }

    @Test
    @DisplayName("AC-008: tras la ventana pasa a SEMIABIERTO y 2 éxitos consecutivos cierran")
    void ac008_semiAbiertoYCierre() {
        List<String> transiciones = new ArrayList<>();
        CircuitoResiliencia c = breaker(transiciones);

        for (int i = 0; i < 5; i++) {
            c.permitir(T0);
            c.registrarFallo(T0);
        }
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.ABIERTO);

        // pasada la ventana, el primer permitir transiciona a SEMIABIERTO y deja pasar un sondeo
        assertThat(c.permitir(T0.plusSeconds(31))).isTrue();
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.SEMIABIERTO);
        assertThat(transiciones).contains("SEMIABIERTO");

        c.registrarExito(T0.plusSeconds(31));
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.SEMIABIERTO);
        c.registrarExito(T0.plusSeconds(32));
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.CERRADO);
        assertThat(transiciones).contains("CERRADO");
    }

    @Test
    @DisplayName("AF-06: un fallo en SEMIABIERTO reabre de inmediato")
    void af06_semiAbiertoReabre() {
        List<String> transiciones = new ArrayList<>();
        CircuitoResiliencia c = breaker(transiciones);
        for (int i = 0; i < 5; i++) {
            c.permitir(T0);
            c.registrarFallo(T0);
        }
        c.permitir(T0.plusSeconds(31));   // → SEMIABIERTO
        c.registrarFallo(T0.plusSeconds(31));
        assertThat(c.estado()).isEqualTo(CircuitoResiliencia.Estado.ABIERTO);
        assertThat(c.permitir(T0.plusSeconds(32))).isFalse();
    }

    @Test
    @DisplayName("BR-002: configuración inválida falla al construir")
    void br002_configInvalida() {
        assertThatThrownBy(() -> new CircuitoResiliencia(0, Duration.ofSeconds(30), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallos-para-abrir");
        assertThatThrownBy(() -> new CircuitoResiliencia(5, Duration.ZERO, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("segundos-abierto");
        assertThatThrownBy(() -> new CircuitoResiliencia(5, Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exitos-para-cerrar");
    }
}
