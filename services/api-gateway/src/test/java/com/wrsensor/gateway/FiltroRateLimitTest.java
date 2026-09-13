package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.domain.TablaRutas;
import com.wrsensor.gateway.infrastructure.adapter.in.web.FiltroRateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0007 — filtro de rate limiting end-to-end a nivel filtro (AF-02, AF-06, AC-013) con
 * reloj y exchange simulados (sin broker ni puertos).
 */
class FiltroRateLimitTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");

    private record Escenario(FiltroRateLimit filtro, AtomicInteger pasaron) {}

    private static Escenario escenario(boolean confiarXff, Limite login) {
        TablaRutas tabla = TablaRutas.de(List.of(
                new TablaRutas.Ruta("login", "/api/auth/login", Set.of("POST"),
                        "http://registry:8080", "login", 10_000),
                new TablaRutas.Ruta("sensores", "/api/sensores/**", Set.of("GET"),
                        "http://registry:8080", "lectura", 10_000)));
        Map<String, Limite> clases = Map.of(
                "login", login,
                "lectura", new Limite("lectura", 120, 60, 60),
                "default", new Limite("default", 300, 60, 100));
        FiltroRateLimit filtro = new FiltroRateLimit(tabla, clases,
                new RateLimiterEnMemoria(Duration.ofSeconds(300)), confiarXff,
                Clock.fixed(T0, ZoneOffset.UTC));
        return new Escenario(filtro, new AtomicInteger());
    }

    private static MockServerWebExchange login(String ip, String xff) {
        var builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress(ip, 34567));
        if (xff != null) {
            builder = builder.header("X-Forwarded-For", xff);
        }
        return MockServerWebExchange.from(builder);
    }

    private Mono<Void> ejecutar(Escenario esc, MockServerWebExchange exchange) {
        WebFilterChain chain = ex -> {
            esc.pasaron().incrementAndGet();
            return Mono.empty();
        };
        return esc.filtro().filter(exchange, chain);
    }

    // ===== AF-02: cupo agotado no llega al downstream =====

    @Test
    @DisplayName("AF-02: superado el cupo responde 429 + Retry-After + body de dominio y no encadena")
    void af02_cupoAgotado() {
        Escenario esc = escenario(false, new Limite("login", 2, 60, 2));
        MockServerWebExchange ex1 = login("10.0.0.1", null);
        MockServerWebExchange ex2 = login("10.0.0.1", null);
        MockServerWebExchange ex3 = login("10.0.0.1", null);

        ejecutar(esc, ex1).block();
        ejecutar(esc, ex2).block();
        ejecutar(esc, ex3).block();

        assertThat(esc.pasaron().get()).as("sólo las 2 permitidas siguieron la cadena")
                .isEqualTo(2);
        assertThat(ex3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex3.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(ex3.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(ex3.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(ex3.getResponse().getBodyAsString().block())
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"");
    }

    @Test
    @DisplayName("AF-02: otra IP de origen conserva su propio cupo (el límite no es global)")
    void af02_otraIpPermitida() {
        Escenario esc = escenario(false, new Limite("login", 1, 60, 1));
        MockServerWebExchange a1 = login("10.0.0.1", null);
        MockServerWebExchange a2 = login("10.0.0.1", null);
        MockServerWebExchange b1 = login("10.0.0.2", null);

        ejecutar(esc, a1).block();
        ejecutar(esc, a2).block();
        ejecutar(esc, b1).block();

        assertThat(a2.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(b1.getResponse().getStatusCode()).as("la otra IP pasa").isNull();
        assertThat(esc.pasaron().get()).isEqualTo(2);
    }

    // ===== AF-06 / AC-013: spoof de X-Forwarded-For =====

    @Test
    @DisplayName("AC-013 / AF-06: con confiar-forwarded-for=false el header del cliente se ignora")
    void ac013_spoofIgnorado() {
        Escenario esc = escenario(false, new Limite("login", 1, 60, 1));
        MockServerWebExchange primero = login("10.0.0.1", "9.9.9.9");
        MockServerWebExchange spoof = login("10.0.0.1", "9.9.9.9");

        ejecutar(esc, primero).block();
        ejecutar(esc, spoof).block();

        assertThat(spoof.getResponse().getStatusCode())
                .as("cambiar X-Forwarded-For no evade el límite").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("AC-013: con confiar-forwarded-for=true la clave usa el header (config explícita)")
    void ac013_confiarXff() {
        Escenario esc = escenario(true, new Limite("login", 1, 60, 1));
        MockServerWebExchange primero = login("10.0.0.1", "9.9.9.9");
        MockServerWebExchange otroXff = login("10.0.0.1", "8.8.8.8");

        ejecutar(esc, primero).block();
        ejecutar(esc, otroXff).block();

        assertThat(otroXff.getResponse().getStatusCode())
                .as("con la config explícita el header sí define la clave").isNull();
        assertThat(esc.pasaron().get()).isEqualTo(2);
    }

    // ===== BR-004 / BR-006: clase por ruta y headers informativos =====

    @Test
    @DisplayName("BR-004: rutas distintas usan clases distintas (login estricta, lectura amplia)")
    void br004_clasesPorRuta() {
        Escenario esc = escenario(false, new Limite("login", 1, 60, 1));
        MockServerWebExchange login = login("10.0.0.1", null);
        ejecutar(esc, login).block();
        assertThat(login.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("1");

        var lectura = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sensores")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567)));
        ejecutar(esc, lectura).block();
        assertThat(lectura.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                .as("la clase lectura no se agota por el login").isEqualTo("120");
        assertThat(lectura.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("AF-01: sin ruta declarada el filtro no aplica límite (lo resuelve el router)")
    void af01_sinRuta() {
        Escenario esc = escenario(false, new Limite("login", 1, 60, 1));
        var desconocido = MockServerWebExchange.from(MockServerHttpRequest.get("/api/desconocido")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567)));
        ejecutar(esc, desconocido).block();
        assertThat(desconocido.getResponse().getStatusCode()).isNull();
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }
}
