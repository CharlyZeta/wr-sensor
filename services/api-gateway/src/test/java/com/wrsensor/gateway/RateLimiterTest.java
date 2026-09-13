package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.domain.TokenBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-0007 — token bucket y rate limiter en memoria (unit tests BR-005, BR-006, BR-007 y AF-02).
 */
class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");

    // ===== BR-005: token bucket con burst y recarga =====

    @Test
    @DisplayName("BR-005: el burst define el tamaño del balde y la recarga es peticiones/ventana")
    void br005_burstYRecarga() {
        Limite limite = new Limite("login", 10, 60, 10);   // 10 peticiones por minuto
        TokenBucket cubeta = new TokenBucket(limite, T0);

        for (int i = 0; i < 10; i++) {
            assertThat(cubeta.intentar(T0).permitido()).as("petición " + (i + 1)).isTrue();
        }
        assertThat(cubeta.intentar(T0).permitido()).as("la 11ª excede el burst").isFalse();

        // 6 segundos después ya se recargó exactamente 1 token (10/60 = 0.1666/s)
        assertThat(cubeta.intentar(T0.plusSeconds(6)).permitido()).isTrue();
        assertThat(cubeta.intentar(T0.plusSeconds(6)).permitido()).isFalse();

        // una ventana completa devuelve el balde lleno sin pasar de la capacidad
        assertThat(cubeta.tokens()).isLessThanOrEqualTo(limite.burst());
        assertThat(cubeta.intentar(T0.plusSeconds(120)).permitido()).isTrue();
    }

    @Test
    @DisplayName("BR-005: el reloj no retrocede ni la recarga se aplica dos veces en el mismo instante")
    void br005_relojEstable() {
        TokenBucket cubeta = new TokenBucket(new Limite("x", 60, 60, 2), T0);
        assertThat(cubeta.intentar(T0).permitido()).isTrue();
        assertThat(cubeta.intentar(T0).permitido()).isTrue();
        assertThat(cubeta.intentar(T0).permitido()).isFalse();
        assertThat(cubeta.intentar(T0.minusSeconds(30)).permitido())
                .as("un instante anterior no recarga ni rompe el estado").isFalse();
    }

    // ===== BR-006: veredicto con Retry-After =====

    @Test
    @DisplayName("BR-006: al exceder el cupo el veredicto trae Retry-After entero ≥ 1 y restante 0")
    void br006_retryAfter() {
        Limite limite = new Limite("login", 2, 60, 2);
        TokenBucket cubeta = new TokenBucket(limite, T0);
        cubeta.intentar(T0);
        cubeta.intentar(T0);

        TokenBucket.Veredicto veredicto = cubeta.intentar(T0);
        assertThat(veredicto.permitido()).isFalse();
        assertThat(veredicto.retryAfterSegundos()).as("2/60 → 30 s para el próximo token")
                .isEqualTo(30);
        assertThat(veredicto.restante()).isZero();
        assertThat(veredicto.limite()).isEqualTo(2);

        TokenBucket.Veredicto permitido = cubeta.intentar(T0.plusSeconds(30));
        assertThat(permitido.permitido()).isTrue();
        assertThat(permitido.retryAfterSegundos()).isZero();
    }

    // ===== AF-02: cupo por clave (clase + IP) =====

    @Test
    @DisplayName("AF-02: el cupo es por clave — agotar una IP no afecta a otra")
    void af02_cupoPorClave() {
        RateLimiterEnMemoria limiter = new RateLimiterEnMemoria(Duration.ofSeconds(300));
        Limite limite = new Limite("login", 1, 60, 1);

        assertThat(limiter.evaluar("login|10.0.0.1", limite, T0).permitido()).isTrue();
        assertThat(limiter.evaluar("login|10.0.0.1", limite, T0).permitido()).isFalse();
        assertThat(limiter.evaluar("login|10.0.0.2", limite, T0).permitido())
                .as("otra IP tiene su propio cupo").isTrue();
        assertThat(limiter.evaluar("lectura|10.0.0.1", limite, T0).permitido())
                .as("otra clase tiene su propio cupo").isTrue();
        assertThat(limiter.clavesActivas()).isEqualTo(3);
    }

    // ===== BR-007 / AC-012: expiración de claves inactivas =====

    @Test
    @DisplayName("AC-012 / BR-007: las claves sin uso dentro de la ventana se liberan (sin reloj real)")
    void ac012_expiracionDeClaves() {
        RateLimiterEnMemoria limiter = new RateLimiterEnMemoria(Duration.ofSeconds(300));
        Limite limite = new Limite("default", 10, 60, 10);

        limiter.evaluar("login|10.0.0.1", limite, T0);
        limiter.evaluar("login|10.0.0.2", limite, T0);
        assertThat(limiter.clavesActivas()).isEqualTo(2);

        limiter.evaluar("login|10.0.0.3", limite, T0.plusSeconds(301));
        assertThat(limiter.clavesActivas()).as("las inactivas se purgaron, queda la nueva")
                .isEqualTo(1);

        limiter.purgar(T0.plusSeconds(700));
        assertThat(limiter.clavesActivas()).isZero();
    }

    @Test
    @DisplayName("BR-007: la clave se reutiliza mientras siga activa (el balde no se reinicia)")
    void br007_claveReutilizada() {
        RateLimiterEnMemoria limiter = new RateLimiterEnMemoria(Duration.ofSeconds(300));
        Limite limite = new Limite("login", 2, 60, 2);
        assertThat(limiter.evaluar("k", limite, T0).permitido()).isTrue();
        assertThat(limiter.evaluar("k", limite, T0.plusSeconds(1)).permitido()).isTrue();
        assertThat(limiter.evaluar("k", limite, T0.plusSeconds(2)).permitido())
                .as("el balde persiste entre requests").isFalse();
        assertThat(limiter.clavesActivas()).isEqualTo(1);
    }

    // ===== BR-004: validación de configuración =====

    @Test
    @DisplayName("BR-004: límites inválidos en configuración fallan con mensaje explícito")
    void br004_limitesInvalidos() {
        assertThatThrownBy(() -> new Limite("login", 0, 60, 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("peticiones invalido");
        assertThatThrownBy(() -> new Limite("login", 10, 0, 10))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ventana-segundos invalido");
        assertThatThrownBy(() -> new Limite("login", 10, 60, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("burst invalido");

        Limite l = new Limite("login", 10, 60, 10);
        assertThat(l.recargaPorSegundo()).isCloseTo(1.0 / 6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(l.tiempoDeConsumo(10)).isEqualTo(Duration.ofSeconds(60));
    }
}
