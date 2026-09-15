package com.wrsensor.gateway;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.infrastructure.adapter.in.web.FiltroCorrelacion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServerRoutes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FEAT-0007 (Main Flow + AC-001..AC-011) con downstreams stub en proceso
 * (servidor HTTP del JDK para REST y Reactor Netty para WebSocket): sin Docker, y con conteo
 * exacto de lo que recibe cada downstream.
 *
 * <p>Nota de configuración: la clase `lectura` se sube a 120/60 s con burst 120 para que el AC-005
 * ("120 peticiones dentro del límite") sea alcanzable en ráfaga; con el burst default (60) el
 * cupo instantáneo es 60 — comportamiento correcto del token bucket, verificado aparte.</p>
 *
 * <p>FEAT-0008 BR-003: el handshake WebSocket ahora exige token válido, así que las conexiones de
 * este test mandan {@code ?token=} acuñado con el mismo secreto/format que el registry.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.clases.lectura.peticiones=120",
        "gateway.rate-limit.clases.lectura.burst=120",
        // ventana larga para que la recarga continua del token bucket no devuelva cupo
        // mientras se mide la frontera del límite (120 dentro, la 121 afuera)
        "gateway.rate-limit.clases.lectura.ventana-segundos=3600"
})
class FEAT0007MainFlowIT {

    private static final List<Peticion> REGISTRY = new CopyOnWriteArrayList<>();
    private static final List<Peticion> QUERY = new CopyOnWriteArrayList<>();
    private static final List<String> RUTAS_WS = new CopyOnWriteArrayList<>();
    private static final List<String> MENSAJES_WS = new CopyOnWriteArrayList<>();

    private static HttpServer stubRegistry;
    private static HttpServer stubQuery;
    private static HttpServer stubLento;
    private static DisposableServer stubWs;
    private static int puertoRegistry;
    private static int puertoQuery;
    private static int puertoLento;
    private static int puertoWs;
    private static int puertoCaido;

    /**
     * Los stubs arrancan en un inicializador estático: {@code @DynamicPropertySource} se evalúa
     * antes de {@code @BeforeAll}, así que los puertos tienen que existir al inicializar la clase.
     */
    static {
        try {
            levantarStubs();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudieron levantar los downstream stub", e);
        }
    }

    /** Petición tal como la vio el downstream (método, path, query y headers). */
    private record Peticion(String metodo, String path, String query, Map<String, String> headers) {}

    private static void levantarStubs() throws IOException {
        stubRegistry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubRegistry.createContext("/", ex -> {
            REGISTRY.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(), headers(ex)));
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/auth/login")) {
                responder(ex, 200, "{\"token\":\"t\",\"rol\":\"ADMIN\",\"expiraEnSegundos\":3600}");
            } else {
                responder(ex, 200, "{\"items\":[],\"nextCursor\":null,\"origen\":\"registry-stub\"}");
            }
        });
        stubRegistry.start();
        puertoRegistry = stubRegistry.getAddress().getPort();

        stubQuery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubQuery.createContext("/", ex -> {
            QUERY.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(), headers(ex)));
            responder(ex, 200, "{\"origen\":\"query-stub\",\"items\":[]}");
        });
        stubQuery.start();
        puertoQuery = stubQuery.getAddress().getPort();

        stubLento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubLento.createContext("/", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responder(ex, 200, "{\"origen\":\"lento-stub\"}");
        });
        stubLento.start();
        puertoLento = stubLento.getAddress().getPort();

        // puerto sin listener: conexión rechazada de inmediato (AF-03). Se usa un puerto fijo
        // y poco probable en lugar del recién liberado, que en Windows puede quedar filtrando
        // el SYN (se vería como timeout, no como destino caído).
        puertoCaido = 1;

        stubWs = reactor.netty.http.server.HttpServer.create()
                .host("127.0.0.1").port(0)
                .route(rutas -> wsStub(rutas))
                .bindNow();
        puertoWs = stubWs.port();
    }

    private static void wsStub(HttpServerRoutes rutas) {
        rutas.ws(req -> {
            RUTAS_WS.add(req.uri());
            return req.uri().startsWith("/ws/");
        }, (entrante, saliente) -> {
            // Un único escritor y la entrada suscrita desde el arranque: si el eco se suscribe
            // recién después de mandar el saludo, un frame que llegue en el medio se pierde.
            reactor.core.publisher.Flux<String> salida = reactor.core.publisher.Flux.concat(
                    reactor.core.publisher.Flux.just("hola-desde-stub"),
                    entrante.receiveFrames()
                            .filter(f -> f instanceof TextWebSocketFrame)
                            .map(f -> "eco:" + ((TextWebSocketFrame) f).text()));
            return saliente.sendString(salida).then();
        }, reactor.netty.http.server.WebsocketServerSpec.builder().build());
    }

    @AfterAll
    static void bajarStubs() {
        if (stubRegistry != null) {
            stubRegistry.stop(0);
        }
        if (stubQuery != null) {
            stubQuery.stop(0);
        }
        if (stubLento != null) {
            stubLento.stop(0);
        }
        if (stubWs != null) {
            stubWs.disposeNow();
        }
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("X-Downstream", "stub");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static Map<String, String> headers(HttpExchange ex) {
        Map<String, String> h = new java.util.HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT),
                String.join(",", v)));
        return h;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String registry = "http://127.0.0.1:" + puertoRegistry;
        String query = "http://127.0.0.1:" + puertoQuery;
        String ws = "http://127.0.0.1:" + puertoWs;
        // La tabla de rutas se declara completa acá: una fuente de mayor precedencia que define
        // índices de la lista no se mergea con application.yml, así que el test la reemplaza.
        ruta(r, 0, "registry-login", "/api/auth/login", "POST", registry, "login", 10000);
        ruta(r, 1, "registry-auth", "/api/auth/**", "POST", registry, "default", 10000);
        ruta(r, 2, "registry-sensores-lectura", "/api/sensores/**", "GET", registry, "lectura", 10000);
        ruta(r, 3, "registry-sensores-escritura", "/api/sensores/**", "POST,PUT,DELETE", registry,
                "default", 10000);
        ruta(r, 4, "query-lecturas", "/api/sensores/*/lecturas", "GET", query, "lectura", 10000);
        ruta(r, 5, "query-actual", "/api/sensores/*/actual", "GET", query, "lectura", 10000);
        ruta(r, 6, "ws-alertas", "/ws/alertas", "", ws, "ws", 10000);
        ruta(r, 7, "ws-sensores", "/ws/sensores/**", "", ws, "ws", 10000);
        // AF-03 / AC-009: destino caído y destino lento
        ruta(r, 8, "caido", "/api/caido/**", "GET", "http://127.0.0.1:" + puertoCaido,
                "default", 2000);
        ruta(r, 9, "lento", "/api/lento/**", "GET", "http://127.0.0.1:" + puertoLento,
                "default", 300);
    }

    private static void ruta(DynamicPropertyRegistry r, int i, String id, String patron,
                             String metodos, String destino, String clase, long timeoutMs) {
        r.add("gateway.rutas[" + i + "].id", () -> id);
        r.add("gateway.rutas[" + i + "].patron", () -> patron);
        if (!metodos.isEmpty()) {
            String[] ms = metodos.split(",");
            for (int j = 0; j < ms.length; j++) {
                int idx = j;
                r.add("gateway.rutas[" + i + "].metodos[" + j + "]", () -> ms[idx]);
            }
        }
        r.add("gateway.rutas[" + i + "].destino", () -> destino);
        r.add("gateway.rutas[" + i + "].clase-limite", () -> clase);
        r.add("gateway.rutas[" + i + "].timeout-ms", () -> String.valueOf(timeoutMs));
    }

    @Autowired
    RateLimiterEnMemoria limiter;

    @LocalServerPort
    int puertoGateway;

    /** Cliente HTTP contra el gateway real (sin auto-configuración: se arma explícito). */
    private WebTestClient cliente;

    @BeforeEach
    void limpiar() {
        cliente = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + puertoGateway)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        limiter.limpiar();
        REGISTRY.clear();
        QUERY.clear();
        RUTAS_WS.clear();
        MENSAJES_WS.clear();
    }

    private static void esperarHasta(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long limite = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < limite && !cond.getAsBoolean()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ================= Main Flow =================

    @Test
    @DisplayName("Main Flow: ruteo REST + correlación + prefijo compartido + login + WebSocket por el gateway")
    void mainFlow() {
        // 2) GET /api/sensores → sensor-registry, con correlación y headers de cupo
        String id = cliente.get().uri("/api/sensores")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-RateLimit-Limit")
                .expectBody().jsonPath("$.origen").isEqualTo("registry-stub")
                .returnResult().getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(id).isNotBlank();
        assertThat(REGISTRY).as("una sola invocación al downstream").hasSize(1);
        assertThat(REGISTRY.get(0).headers()).containsEntry("x-correlation-id", id);

        // 4) prefijo compartido: lecturas/actual van a query-api, el detalle al registry
        cliente.get().uri("/api/sensores/abc/lecturas").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.origen").isEqualTo("query-stub");
        cliente.get().uri("/api/sensores/abc/actual").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.origen").isEqualTo("query-stub");
        cliente.get().uri("/api/sensores/abc").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.origen").isEqualTo("registry-stub");
        assertThat(QUERY).extracting(Peticion::path)
                .containsExactly("/api/sensores/abc/lecturas", "/api/sensores/abc/actual");

        // 5) login por su clase de límite
        cliente.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        assertThat(REGISTRY).extracting(Peticion::path).contains("/api/auth/login");

        // 6) WebSocket tunelado
        assertThat(ws("/ws/alertas")).contains("hola-desde-stub", "eco:ping");
        assertThat(RUTAS_WS).contains("/ws/alertas");
    }

    /** Conecta al gateway, espera el saludo del stub, manda "ping" y devuelve lo recibido. */
    private List<String> ws(String path) {
        List<String> recibidos = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient ws = new ReactorNettyWebSocketClient();
        // FEAT-0008 BR-003: el upgrade exige token; el túnel de FEAT-0007 se prueba autenticado.
        String url = "ws://127.0.0.1:" + puertoGateway + path + "?token="
                + GatewayTestTokens.vigente("VIEWER");
        Disposable sub = ws.execute(URI.create(url), sesion ->
                sesion.receive()
                        .map(m -> m.getPayloadAsText())
                        .doOnNext(recibidos::add)
                        // el ping se manda recién cuando llegó el saludo del stub, para no
                        // perderlo antes de que el downstream suscriba su entrada
                        .concatMap(texto -> texto.startsWith("hola")
                                ? sesion.send(Mono.just(sesion.textMessage("ping")))
                                : Mono.empty())
                        .take(2)
                        .then()).subscribe();
        esperarHasta(() -> recibidos.size() >= 2, 8000);
        sub.dispose();
        return recibidos;
    }

    // ================= AC-002 =================

    @Test
    @DisplayName("AC-002: la ruta más específica gana y el path/query llegan intactos")
    void ac002_ruteoPorEspecificidad() {
        cliente.get().uri("/api/sensores/abc/lecturas?desde=2026-01-01&limit=5")
                .exchange().expectStatus().isOk();

        assertThat(QUERY).hasSize(1);
        assertThat(QUERY.get(0).path()).isEqualTo("/api/sensores/abc/lecturas");
        assertThat(QUERY.get(0).query()).isEqualTo("desde=2026-01-01&limit=5");
        assertThat(REGISTRY).as("no fue al registry").isEmpty();
    }

    // ================= AC-003 =================

    @Test
    @DisplayName("AC-003: upgrade WS a /ws/alertas tunelado en ambos sentidos")
    void ac003_wsTunelado() {
        List<String> recibidos = ws("/ws/alertas");
        assertThat(recibidos).containsExactly("hola-desde-stub", "eco:ping");
        assertThat(RUTAS_WS).contains("/ws/alertas");
    }

    @Test
    @DisplayName("AC-003 / BR-002: el path del WS se preserva hacia query-api")
    void ac003_wsSensoresPath() {
        List<String> recibidos = ws("/ws/sensores/abc");
        assertThat(recibidos).contains("eco:ping");
        assertThat(RUTAS_WS).contains("/ws/sensores/abc");
    }

    @Test
    @DisplayName("AC-003 / AF-05: un GET sin upgrade a /ws/** responde 426 y no abre sesión")
    void ac003_sinUpgrade() {
        cliente.get().uri("/ws/alertas").exchange()
                .expectStatus().isEqualTo(426)
                .expectBody().jsonPath("$.code").isEqualTo("WS_UPGRADE_REQUIRED");
        assertThat(RUTAS_WS).isEmpty();
    }

    // ================= AC-004 =================

    @Test
    @DisplayName("AC-004: login 10/60s — las 10 llegan al downstream y la 11ª es 429 sin reenvío")
    void ac004_loginRateLimit() {
        for (int i = 0; i < 10; i++) {
            cliente.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        }
        assertThat(REGISTRY).as("las 10 primeras llegaron").hasSize(10);

        cliente.post().uri("/api/auth/login").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueMatches("Retry-After", "\\d+")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMIT_EXCEEDED");

        assertThat(REGISTRY).as("la rechazada no se reenvió al downstream").hasSize(10);
        String retry = cliente.post().uri("/api/auth/login").exchange().expectStatus()
                .isEqualTo(429).returnResult().getResponseHeaders().getFirst("Retry-After");
        assertThat(Long.parseLong(retry)).isGreaterThanOrEqualTo(1L);
    }

    // ================= AC-005 =================

    @Test
    @DisplayName("AC-005: 120 lecturas dentro del cupo y la 121ª rechazada (cupo por IP, burst = cupo)")
    void ac005_lecturaRateLimit() {
        for (int i = 0; i < 120; i++) {
            cliente.get().uri("/api/sensores").exchange().expectStatus().isOk();
        }
        cliente.get().uri("/api/sensores").exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");
        assertThat(REGISTRY).as("120 al downstream, la 121 no").hasSize(120);
    }

    @Test
    @DisplayName("BR-004/BR-005: el cupo de lectura no se agota por el login (clases independientes)")
    void br004_clasesIndependientes() {
        for (int i = 0; i < 10; i++) {
            cliente.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        }
        cliente.post().uri("/api/auth/login").exchange().expectStatus().isEqualTo(429);
        // la clase lectura sigue intacta pese al login agotado
        cliente.get().uri("/api/sensores").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "120");
    }

    // ================= AC-006 / AC-007 =================

    @Test
    @DisplayName("AC-006: sin header el gateway genera el id, lo propaga y lo refleja + lo loguea")
    void ac006_correlacion() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FiltroCorrelacion.class);
        logger.addAppender(logs);
        try {
            String generado = cliente.get().uri("/api/sensores").exchange().expectStatus().isOk()
                    .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
            assertThat(generado).isNotBlank();
            assertThat(REGISTRY.get(0).headers()).containsEntry("x-correlation-id", generado);
            assertThat(logs.list).anyMatch(e -> e.getFormattedMessage().contains("correlacion=" + generado)
                    && e.getFormattedMessage().contains("200"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("AC-006: con header del cliente el valor se propaga idéntico")
    void ac006_correlacionPropagada() {
        String propio = "abc-123_correlacion";
        String devuelto = cliente.get().uri("/api/sensores")
                .header("X-Correlation-Id", propio)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(devuelto).isEqualTo(propio);
        assertThat(REGISTRY.get(0).headers()).containsEntry("x-correlation-id", propio);
    }

    @Test
    @DisplayName("AC-007 / AF-04: un X-Correlation-Id inválido se descarta y se genera uno nuevo")
    void ac007_correlacionInvalida() {
        String basura = "x".repeat(200);
        String devuelto = cliente.get().uri("/api/sensores")
                .header("X-Correlation-Id", basura)
                .exchange().expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(devuelto).isNotEqualTo(basura).hasSizeLessThanOrEqualTo(64).isNotBlank();
        assertThat(REGISTRY.get(0).headers()).containsEntry("x-correlation-id", devuelto);
        assertThat(REGISTRY.get(0).headers()).doesNotContainValue(basura);
    }

    // ================= AC-008 =================

    @Test
    @DisplayName("AC-008 / AF-01: ruta no declarada → 404 ROUTE_NOT_FOUND sin tocar downstreams")
    void ac008_rutaInexistente() {
        cliente.get().uri("/api/desconocido").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        cliente.get().uri("/api/simulador/estado").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        assertThat(REGISTRY).isEmpty();
        assertThat(QUERY).isEmpty();
    }

    // ================= AC-009 =================

    @Test
    @DisplayName("AC-009 / AF-03: destino caído → 502 y destino lento → 504, nunca 500")
    void ac009_erroresDeUpstream() {
        cliente.get().uri("/api/caido/x").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.message").exists();

        cliente.get().uri("/api/lento/x").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
    }

    // ================= AC-010 =================

    @Test
    @DisplayName("AC-010: headers hop-by-hop fuera, X-Forwarded-* agregados y Authorization intacto")
    void ac010_higieneDeHeaders() {
        cliente.get().uri("/api/sensores")
                .header("Authorization", "Bearer token-de-prueba")
                .header("X-Forwarded-For", "9.9.9.9")
                .header("Te", "trailers")
                .header("Proxy-Authorization", "Basic zzz")
                .exchange().expectStatus().isOk();

        Map<String, String> h = REGISTRY.get(0).headers();
        assertThat(h).as("hop-by-hop y credenciales de proxy no se propagan")
                .doesNotContainKeys("te", "proxy-authorization", "transfer-encoding", "connection");
        assertThat(h).containsEntry("authorization", "Bearer token-de-prueba");
        assertThat(h).containsEntry("x-forwarded-proto", "http");
        assertThat(h.get("x-forwarded-for"))
                .as("se usa el peer real, no el header del cliente").isEqualTo("127.0.0.1");
        assertThat(h.get("host")).as("Host es el del destino, no el del cliente")
                .isEqualTo("127.0.0.1:" + puertoRegistry);
        assertThat(h).containsEntry("x-correlation-id", h.get("x-correlation-id"));
    }

    // ================= AC-011 (config del contexto) =================

    @Test
    @DisplayName("AC-011: la clase lectura responde a la configuración del contexto (120/60s)")
    void ac011_configuracionEfectiva() {
        cliente.get().uri("/api/sensores").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "120");
    }

    // ================= AC-013 (integración del spoof) =================

    @Test
    @DisplayName("AC-013: mandar X-Forwarded-For no evade el límite con la config default")
    void ac013_spoofNoEvade() {
        for (int i = 0; i < 10; i++) {
            cliente.post().uri("/api/auth/login").header("X-Forwarded-For", "9.9.9." + i)
                    .exchange().expectStatus().isOk();
        }
        cliente.post().uri("/api/auth/login").header("X-Forwarded-For", "9.9.9.99")
                .exchange().expectStatus().isEqualTo(429);
    }
}
