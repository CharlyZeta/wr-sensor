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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FEAT-0008 (Main Flow + AC-001..AC-008, AC-012) contra el gateway real con
 * downstreams stub en proceso: servidor HTTP del JDK para registry/query-api y Reactor Netty para
 * el WebSocket. Sin Docker.
 *
 * <p>Cubre los tres habilitadores del frontend: CORS (preflight propio, sin cupo y sin downstream),
 * autenticación del handshake WebSocket (firma + exp, sin propagar el token) y el ruteo del endpoint
 * de resumen a query-api por patrón más específico.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // BR-001: CORS habilitado para el SPA en dev (mismo valor que documenta el RUNBOOK).
        "gateway.cors.origenes=http://localhost:5173",
        // AC-001: la clase lectura con burst = cupo (120) para poder medir la frontera en ráfaga,
        // igual que en FEAT-0007.
        "gateway.rate-limit.clases.lectura.peticiones=120",
        "gateway.rate-limit.clases.lectura.burst=120",
        "gateway.rate-limit.clases.lectura.ventana-segundos=3600"
})
class FEAT0008MainFlowIT {

    private static final String ORIGEN_DEV = "http://localhost:5173";
    private static final String ORIGEN_AJENO = "http://malicioso.example";

    private static final List<Peticion> REGISTRY = new CopyOnWriteArrayList<>();
    private static final List<Peticion> QUERY = new CopyOnWriteArrayList<>();
    private static final List<String> RUTAS_WS = new CopyOnWriteArrayList<>();

    private static HttpServer stubRegistry;
    private static HttpServer stubQuery;
    private static DisposableServer stubWs;
    private static int puertoRegistry;
    private static int puertoQuery;

    /** Petición tal como la vio el downstream (método, path, query y headers). */
    private record Peticion(String metodo, String path, String query, Map<String, String> headers) {}

    static {
        try {
            levantarStubs();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudieron levantar los downstream stub", e);
        }
    }

    private static void levantarStubs() throws IOException {
        stubRegistry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubRegistry.createContext("/", ex -> {
            REGISTRY.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(), headers(ex)));
            responder(ex, 200, "{\"items\":[],\"nextCursor\":null,\"origen\":\"registry-stub\"}");
        });
        stubRegistry.start();
        puertoRegistry = stubRegistry.getAddress().getPort();

        stubQuery = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubQuery.createContext("/", ex -> {
            QUERY.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(), headers(ex)));
            responder(ex, 200, "[{\"id\":\"00000000-0000-4000-8000-00000000000a\","
                    + "\"codigo\":\"S-01\",\"nombre\":\"Norte\",\"tipo\":\"TEMPERATURA\","
                    + "\"latitud\":-34.6,\"longitud\":-58.4,\"estado\":\"ACTIVE\","
                    + "\"unidadMedida\":\"CELSIUS\",\"ultimaLectura\":null}]");
        });
        stubQuery.start();
        puertoQuery = stubQuery.getAddress().getPort();

        stubWs = reactor.netty.http.server.HttpServer.create()
                .host("127.0.0.1").port(0)
                .route(FEAT0008MainFlowIT::wsStub)
                .bindNow();
    }

    private static void wsStub(HttpServerRoutes rutas) {
        rutas.ws(req -> {
            RUTAS_WS.add(req.uri());
            return req.uri().startsWith("/ws/");
        }, (entrante, saliente) -> {
            Flux<String> salida = Flux.concat(Flux.just("hola-desde-stub"),
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
        if (stubWs != null) {
            stubWs.disposeNow();
        }
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
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
        String ws = "http://127.0.0.1:" + stubWs.port();
        // La tabla de rutas se declara completa (una fuente de mayor precedencia que define
        // índices de la lista no se mergea con application.yml).
        ruta(r, 0, "registry-login", "/api/auth/login", "POST", registry, "login", 10000);
        ruta(r, 1, "registry-auth", "/api/auth/**", "POST", registry, "default", 10000);
        ruta(r, 2, "query-resumen", "/api/sensores/resumen", "GET", query, "lectura", 10000);
        ruta(r, 3, "registry-sensores-lectura", "/api/sensores/**", "GET", registry, "lectura", 10000);
        ruta(r, 4, "registry-sensores-escritura", "/api/sensores/**", "POST,PUT,DELETE", registry,
                "default", 10000);
        ruta(r, 5, "query-lecturas", "/api/sensores/*/lecturas", "GET", query, "lectura", 10000);
        ruta(r, 6, "query-actual", "/api/sensores/*/actual", "GET", query, "lectura", 10000);
        ruta(r, 7, "ws-alertas", "/ws/alertas", "", ws, "ws", 10000);
        ruta(r, 8, "ws-sensores", "/ws/sensores/**", "", ws, "ws", 10000);
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

    /** GET con upgrade a WebSocket (a nivel HTTP: sin completar el handshake). */
    private WebTestClient.ResponseSpec upgrade(String path) {
        return cliente.get().uri(path)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .exchange();
    }

    /** Conecta de verdad, espera el saludo del stub, manda "ping" y devuelve lo recibido. */
    private List<String> ws(String path) {
        return ws(path, null);
    }

    /** Igual que {@link #ws(String)} pero con headers propios (para probar Bearer). */
    private List<String> ws(String path, HttpHeaders headers) {
        List<String> recibidos = new CopyOnWriteArrayList<>();
        ReactorNettyWebSocketClient ws = new ReactorNettyWebSocketClient();
        reactor.core.publisher.Mono<Void> conexion = headers == null
                ? ws.execute(URI.create("ws://127.0.0.1:" + puertoGateway + path),
                        sesion -> recibir(sesion, recibidos))
                : ws.execute(URI.create("ws://127.0.0.1:" + puertoGateway + path), headers,
                        sesion -> recibir(sesion, recibidos));
        Disposable sub = conexion.subscribe();
        esperarHasta(() -> recibidos.size() >= 2, 8000);
        sub.dispose();
        return recibidos;
    }

    private static Mono<Void> recibir(org.springframework.web.reactive.socket.WebSocketSession sesion,
                                      List<String> recibidos) {
        return sesion.receive()
                .map(m -> m.getPayloadAsText())
                .doOnNext(recibidos::add)
                // el ping se manda cuando llegó el saludo, para no perderlo antes de que el
                // downstream suscriba su entrada
                .concatMap(texto -> texto.startsWith("hola")
                        ? sesion.send(Mono.just(sesion.textMessage("ping")))
                        : Mono.empty())
                .take(2)
                .then();
    }

    // ================= Main Flow =================

    @Test
    @DisplayName("Main Flow: preflight propio + resumen por query-api + WS autenticado de punta a punta")
    void mainFlow() {
        // 2) preflight resuelto por el gateway: 204, CORS y sin tocar downstreams
        cliente.options().uri("/api/sensores/resumen")
                .header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEN_DEV);
        assertThat(REGISTRY).isEmpty();
        assertThat(QUERY).as("el preflight no llega al downstream").isEmpty();

        // 3) resumen: lo sirve query-api (patrón más específico) con la clase lectura
        cliente.get().uri("/api/sensores/resumen")
                .header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEN_DEV)
                .expectHeader().valueEquals("X-RateLimit-Limit", "120")
                .expectBody().jsonPath("$[0].codigo").isEqualTo("S-01");
        assertThat(QUERY).extracting(Peticion::path).containsExactly("/api/sensores/resumen");
        assertThat(REGISTRY).as("el resumen no va al registry").isEmpty();

        // 4) WS autenticado con ?token=
        List<String> recibidos = ws("/ws/alertas?token=" + GatewayTestTokens.vigente("VIEWER"));
        assertThat(recibidos).containsExactly("hola-desde-stub", "eco:ping");
        assertThat(RUTAS_WS).containsExactly("/ws/alertas");
    }

    // ================= AC-001 / AC-002 =================

    @Test
    @DisplayName("AC-001: el preflight no consume cupo (las 120 lecturas siguen disponibles)")
    void ac001_preflightSinCupo() {
        for (int i = 0; i < 5; i++) {
            cliente.options().uri("/api/sensores/resumen")
                    .header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange().expectStatus().isNoContent();
        }
        for (int i = 0; i < 120; i++) {
            cliente.get().uri("/api/sensores/resumen").header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                    .exchange().expectStatus().isOk();
        }
        cliente.get().uri("/api/sensores/resumen").header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                .exchange().expectStatus().isEqualTo(429);
        assertThat(QUERY).as("sólo las 120 lecturas llegaron").hasSize(120);
    }

    @Test
    @DisplayName("AC-002/AF-01: preflight de origen no permitido → 403 sin headers CORS")
    void ac002_origenNoPermitido() {
        var headers = cliente.options().uri("/api/sensores/resumen")
                .header(HttpHeaders.ORIGIN, ORIGEN_AJENO)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ORIGIN_NOT_ALLOWED")
                .returnResult().getResponseHeaders();
        assertThat(headers.headerNames())
                .noneMatch(h -> h.toLowerCase(java.util.Locale.ROOT).startsWith("access-control-"));
        assertThat(QUERY).isEmpty();
        assertThat(REGISTRY).isEmpty();
    }

    // ================= AC-003 =================

    @Test
    @DisplayName("AC-003: el SPA puede leer correlación y cupo (headers expuestos) en el resumen")
    void ac003_headersExpuestos() {
        var headers = cliente.get().uri("/api/sensores/resumen")
                .header(HttpHeaders.ORIGIN, ORIGEN_DEV)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGEN_DEV)
                .returnResult(String.class).getResponseHeaders();
        String expuestos = headers.getFirst(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        assertThat(expuestos).contains("X-Correlation-Id").contains("X-RateLimit-Limit");
        assertThat(headers.getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(headers.getFirst("X-RateLimit-Limit")).isEqualTo("120");
    }

    // ================= AC-004 / AC-005 / AC-007 =================

    @Test
    @DisplayName("AC-004/AF-02: upgrade sin token → 401 y el downstream no recibe conexión")
    void ac004_sinToken() {
        upgrade("/ws/alertas")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        upgrade("/ws/sensores/abc")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        assertThat(RUTAS_WS).isEmpty();
    }

    @Test
    @DisplayName("AC-005/AF-03: token expirado o con firma inválida → 401 y sin conexión")
    void ac005_tokenInvalido() {
        upgrade("/ws/alertas?token=" + GatewayTestTokens.expirado("ADMIN"))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        upgrade("/ws/alertas?token=" + GatewayTestTokens.firmaInvalida("ADMIN"))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        upgrade("/ws/alertas?token=no-es-un-jwt")
                .expectStatus().isUnauthorized();
        assertThat(RUTAS_WS).isEmpty();
    }

    @Test
    @DisplayName("AC-007: Authorization: Bearer abre el WS igual que ?token=")
    void ac007_bearer() {
        String token = GatewayTestTokens.vigente("VIEWER");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        assertThat(ws("/ws/alertas", headers))
                .as("con Bearer válido el túnel funciona idéntico a ?token=")
                .containsExactly("hola-desde-stub", "eco:ping");
        assertThat(RUTAS_WS).containsExactly("/ws/alertas");

        // sin el header (y sin query) el mismo upgrade se rechaza
        upgrade("/ws/alertas").expectStatus().isUnauthorized();
        assertThat(RUTAS_WS).as("el rechazo no abre conexión").hasSize(1);
    }

    @Test
    @DisplayName("BR-003: rol fuera de la lista permitida → 403 INSUFFICIENT_ROLE sin conexión")
    void br003_rolNoAutorizado() {
        upgrade("/ws/alertas?token=" + GatewayTestTokens.vigente("AUDITOR"))
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_ROLE");
        assertThat(RUTAS_WS).isEmpty();
    }

    // ================= AC-006 / AC-008 =================

    @Test
    @DisplayName("AC-006: VIEWER abre ambos WS y los mensajes del downstream llegan al cliente")
    void ac006_viewerAbreWs() {
        assertThat(ws("/ws/alertas?token=" + GatewayTestTokens.vigente("VIEWER")))
                .containsExactly("hola-desde-stub", "eco:ping");
        assertThat(ws("/ws/sensores/abc?token=" + GatewayTestTokens.vigente("VIEWER")))
                .contains("eco:ping");
        assertThat(RUTAS_WS).containsExactlyInAnyOrder("/ws/alertas", "/ws/sensores/abc");
    }

    @Test
    @DisplayName("AC-008/BR-004: el token no se propaga al downstream ni queda en el log de acceso")
    void ac008_tokenNoSePropagaNiSeLoguea() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FiltroCorrelacion.class);
        logger.addAppender(logs);
        String token = GatewayTestTokens.vigente("VIEWER");
        try {
            // rechazo: la línea de acceso registra el path, nunca el query con el token
            upgrade("/ws/alertas?token=" + GatewayTestTokens.expirado("ADMIN"))
                    .expectStatus().isUnauthorized();
            assertThat(ws("/ws/alertas?token=" + token)).contains("eco:ping");
            esperarHasta(() -> logs.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("/ws/alertas")), 5000);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).as("hay línea de acceso del WS")
                .anyMatch(e -> e.getFormattedMessage().contains("[gateway] GET /ws/alertas"));
        assertThat(logs.list).as("el token (y el query) nunca se loguean")
                .noneMatch(e -> e.getFormattedMessage().contains(token)
                        || e.getFormattedMessage().contains("token="));
        assertThat(RUTAS_WS).as("el downstream recibe el path sin token").containsExactly("/ws/alertas");
    }

    // ================= AC-012 =================

    @Test
    @DisplayName("AC-012: resumen → query-api; /api/sensores y /{id} siguen yendo al registry")
    void ac012_ruteoDelResumen() {
        cliente.get().uri("/api/sensores/resumen").exchange().expectStatus().isOk();
        cliente.get().uri("/api/sensores").exchange().expectStatus().isOk();
        cliente.get().uri("/api/sensores/abc").exchange().expectStatus().isOk();
        cliente.get().uri("/api/sensores/abc/actual").exchange().expectStatus().isOk();

        assertThat(QUERY).extracting(Peticion::path)
                .containsExactly("/api/sensores/resumen", "/api/sensores/abc/actual");
        assertThat(REGISTRY).extracting(Peticion::path)
                .containsExactly("/api/sensores", "/api/sensores/abc");

        var ultimo = QUERY.get(0);
        assertThat(ultimo.query()).as("el resumen viaja sin parámetros extra").isNull();
    }

    @Test
    @DisplayName("AC-012: el resumen usa la clase lectura (cupo 120/min, no la default de 300)")
    void ac012_claseLectura() {
        cliente.get().uri("/api/sensores/resumen").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "120");
    }

    // ================= AC-013 (decisiones registradas) =================

    @Test
    @DisplayName("AC-013/BR-010: el simulador sigue sin ruta en el gateway (404 ROUTE_NOT_FOUND)")
    void ac013_simuladorFuera() {
        cliente.get().uri("/api/simulador/estado").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        cliente.post().uri("/api/simulador/iniciar").exchange()
                .expectStatus().isNotFound();
        assertThat(REGISTRY).isEmpty();
        assertThat(QUERY).isEmpty();
    }
}
