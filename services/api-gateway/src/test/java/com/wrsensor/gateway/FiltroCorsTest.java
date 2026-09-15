package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.domain.TablaRutas;
import com.wrsensor.gateway.infrastructure.adapter.in.web.FiltroCors;
import com.wrsensor.gateway.infrastructure.adapter.in.web.FiltroRateLimit;
import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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
 * Unit — FEAT-0008 BR-001/BR-002: CORS del gateway a nivel filtro. El preflight lo responde el
 * gateway (204) sin llegar al downstream y sin consumir cupo de rate limit; un origen no permitido
 * se rechaza con 403 **sin** headers CORS.
 */
class FiltroCorsTest {

    private static final Instant T0 = Instant.parse("2026-09-11T10:00:00Z");
    private static final String ORIGEN_DEV = "http://localhost:5173";

    private record Escenario(FiltroCors cors, FiltroRateLimit rateLimit, AtomicInteger pasaron) {}

    private static GatewayProperties.CorsCfg corsCfg(List<String> origenes) {
        return new GatewayProperties.CorsCfg(origenes,
                List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"),
                List.of("Authorization", "Content-Type", "X-Correlation-Id"),
                List.of("X-Correlation-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                        "Retry-After"),
                3600L, false);
    }

    private static Escenario escenario(List<String> origenes) {
        TablaRutas tabla = TablaRutas.de(List.of(new TablaRutas.Ruta("sensores",
                "/api/sensores/**", Set.of("GET"), "http://query:8082", "lectura", 10_000)));
        Map<String, Limite> clases = Map.of(
                "lectura", new Limite("lectura", 2, 60, 2),
                "default", new Limite("default", 300, 60, 100));
        FiltroRateLimit rateLimit = new FiltroRateLimit(tabla, clases,
                new RateLimiterEnMemoria(Duration.ofSeconds(300)), false,
                Clock.fixed(T0, ZoneOffset.UTC));
        return new Escenario(new FiltroCors(props(origenes)), rateLimit, new AtomicInteger());
    }

    private static GatewayProperties props(List<String> origenes) {
        return new GatewayProperties(List.of(), null, corsCfg(origenes), null);
    }

    /** Corre CORS y después el rate limit, como en el arranque real (CORS primero). */
    private Mono<Void> ejecutar(Escenario esc, MockServerWebExchange exchange) {
        WebFilterChain final1 = ex -> {
            esc.pasaron().incrementAndGet();
            return Mono.empty();
        };
        return esc.cors().filter(exchange, ex -> esc.rateLimit().filter(ex, final1));
    }

    private static MockServerWebExchange preflight(String origen) {
        return MockServerWebExchange.from(MockServerHttpRequest.options("/api/sensores")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.ORIGIN, origen)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"));
    }

    private static MockServerWebExchange get(String origen) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/sensores")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.ORIGIN, origen));
    }

    // ===== AC-001: preflight permitido =====

    @Test
    @DisplayName("AC-001: preflight desde origen permitido → 204 con headers CORS y sin downstream")
    void ac001_preflightPermitido() {
        Escenario esc = escenario(List.of(ORIGEN_DEV));
        MockServerWebExchange ex = preflight(ORIGEN_DEV);

        ejecutar(esc, ex).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var h = ex.getResponse().getHeaders();
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGEN_DEV);
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).contains("Authorization");
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("GET");
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("3600");
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .as("BR-001: sin cookies, sin credenciales").isNull();
        assertThat(esc.pasaron().get()).as("el preflight no encadena al proxy").isZero();
    }

    @Test
    @DisplayName("AC-001/BR-002: el preflight no consume cupo de rate limit")
    void ac001_noConsumeCupo() {
        Escenario esc = escenario(List.of(ORIGEN_DEV));
        for (int i = 0; i < 5; i++) {
            MockServerWebExchange pf = preflight(ORIGEN_DEV);
            ejecutar(esc, pf).block();
            assertThat(pf.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                    .as("el preflight no pasa por el rate limit").isNull();
        }
        // las 2 permitidas de la clase lectura siguen disponibles después de 5 preflights
        MockServerWebExchange g1 = get(ORIGEN_DEV);
        MockServerWebExchange g2 = get(ORIGEN_DEV);
        MockServerWebExchange g3 = get(ORIGEN_DEV);
        ejecutar(esc, g1).block();
        ejecutar(esc, g2).block();
        ejecutar(esc, g3).block();
        assertThat(g1.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(g2.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(g3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ===== AC-002 / AF-01: origen no permitido =====

    @Test
    @DisplayName("AC-002/AF-01: preflight de origen no permitido → 403 sin headers Access-Control-*")
    void ac002_preflightRechazado() {
        Escenario esc = escenario(List.of(ORIGEN_DEV));
        MockServerWebExchange ex = preflight("http://malicioso.example");
        ejecutar(esc, ex).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getResponse().getHeaders().headerNames())
                .as("nada de CORS en la respuesta rechazada")
                .noneMatch(h -> h.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-"));
        assertThat(ex.getResponse().getBodyAsString().block())
                .contains("\"code\":\"ORIGIN_NOT_ALLOWED\"");
        assertThat(esc.pasaron().get()).isZero();
    }

    @Test
    @DisplayName("AC-002/AF-01: un GET real desde origen no permitido también es 403")
    void ac002_getRechazado() {
        Escenario esc = escenario(List.of(ORIGEN_DEV));
        MockServerWebExchange ex = get("http://malicioso.example");
        ejecutar(esc, ex).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(esc.pasaron().get()).isZero();
    }

    // ===== BR-001: same-origin no es CORS =====

    @Test
    @DisplayName("BR-001: con Origin del propio gateway no se agregan headers CORS ni se bloquea")
    void br001_mismoOrigenNoEsCors() {
        Escenario esc = escenario(List.of());   // CORS deshabilitado (same-origin only)
        // El navegador manda Origin también en el POST del propio origen y en el handshake WS.
        MockServerWebExchange post = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.HOST, "gateway.local")
                .header(HttpHeaders.ORIGIN, "http://gateway.local"));
        ejecutar(esc, post).block();

        assertThat(post.getResponse().getStatusCode())
                .as("same-origin nunca se rechaza, ni con la lista de orígenes vacía").isNull();
        assertThat(post.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("no es un request CORS: sin headers CORS").isNull();
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-001: el handshake WS del propio origen (con Origin) pasa y no se bloquea")
    void br001_wsMismoOrigen() {
        Escenario esc = escenario(List.of());
        MockServerWebExchange ws = MockServerWebExchange.from(MockServerHttpRequest
                .get("/ws/alertas")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.HOST, "gateway.local:8084")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.ORIGIN, "http://gateway.local:8084"));
        ejecutar(esc, ws).block();
        assertThat(ws.getResponse().getStatusCode()).isNull();
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-001: detrás de un terminador TLS el origen propio se resuelve por X-Forwarded-*")
    void br001_mismoOrigenTrasProxy() {
        Escenario esc = escenario(List.of());
        MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/login")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.HOST, "127.0.0.1:8084")
                .header("X-Forwarded-Host", "dashboard.wrsensor.local")
                .header("X-Forwarded-Proto", "https")
                .header(HttpHeaders.ORIGIN, "https://dashboard.wrsensor.local"));
        ejecutar(esc, ex).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
        assertThat(esc.pasaron().get()).isEqualTo(1);

        // y el mismo origen externo sigue siendo orígen cruzado si no está en la lista
        MockServerWebExchange otro = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/sensores")
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567))
                .header(HttpHeaders.HOST, "127.0.0.1:8084")
                .header("X-Forwarded-Host", "otro.wrsensor.local")
                .header("X-Forwarded-Proto", "https")
                .header(HttpHeaders.ORIGIN, "https://dashboard.wrsensor.local"));
        ejecutar(esc, otro).block();
        assertThat(otro.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ===== AC-003 / BR-001: headers expuestos y configuración =====

    @Test
    @DisplayName("AC-003: la respuesta real expone correlación y cupo al JS del SPA")
    void ac003_headersExpuestos() {
        Escenario esc = escenario(List.of(ORIGEN_DEV));
        MockServerWebExchange ex = get(ORIGEN_DEV);
        ejecutar(esc, ex).block();

        var h = ex.getResponse().getHeaders();
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ORIGEN_DEV);
        assertThat(h.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .contains("X-Correlation-Id").contains("X-RateLimit-Limit");
        assertThat(h.getFirst(HttpHeaders.VARY)).isEqualTo(HttpHeaders.ORIGIN);
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-001: lista vacía = same-origin only (CORS deshabilitado, default de producción)")
    void br001_listaVacia() {
        Escenario esc = escenario(List.of());
        MockServerWebExchange pre = preflight(ORIGEN_DEV);
        ejecutar(esc, pre).block();
        assertThat(pre.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        MockServerWebExchange sinOrigen = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/sensores")
                        .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 34567)));
        ejecutar(esc, sinOrigen).block();
        assertThat(sinOrigen.getResponse().getStatusCode())
                .as("un request sin Origin nunca se bloquea").isNull();
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-001: comodín (*) habilita cualquier origen, configurado explícitamente")
    void br001_comodin() {
        Escenario esc = escenario(List.of("*"));
        MockServerWebExchange ex = get("http://cualquiera.example");
        ejecutar(esc, ex).block();
        assertThat(ex.getResponse().getHeaders()
                .getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://cualquiera.example");
        assertThat(esc.pasaron().get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-001: con allow-credentials activado se refleja en la respuesta")
    void br001_credencialesConfigurables() {
        GatewayProperties props = new GatewayProperties(List.of(), null,
                new GatewayProperties.CorsCfg(List.of(ORIGEN_DEV), null, null, null, null, true),
                null);
        FiltroCors cors = new FiltroCors(props);
        MockServerWebExchange ex = get(ORIGEN_DEV);
        cors.filter(ex, e -> Mono.empty()).block();
        assertThat(ex.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        // los defaults de métodos/headers/max-age también aplican sin configuración explícita
        assertThat(props.cors().metodosOrDefault()).contains("OPTIONS");
        assertThat(props.cors().headersOrDefault()).contains("Authorization");
        assertThat(props.cors().headersExpuestosOrDefault()).contains("Retry-After");
        assertThat(props.cors().maxAgeSegundosOrDefault()).isEqualTo(3600L);
    }
}
