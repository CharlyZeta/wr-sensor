package com.wrsensor.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FIX-0008 (Reproduction Steps + AC-003..AC-007) contra el gateway real, con el
 * índice del SPA como fixture en `src/test/resources/static/` y un stub del registry.
 *
 * <p>Cubre los tres hallazgos de la revisión de seguridad que tocan al gateway: headers de seguridad
 * (S3/A1), resolución segura de estáticos con el 404 de la API intacto (S2/A6) y el secreto del WS
 * (S1, verificado sobre `docker-compose.yml` + fail-fast por perfil).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FIX0008MainFlowIT {

    private static final AtomicInteger REGISTRY_LLAMADAS = new AtomicInteger();
    private static HttpServer stubRegistry;
    private static int puertoRegistry;

    static {
        try {
            stubRegistry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stubRegistry.createContext("/", ex -> {
                REGISTRY_LLAMADAS.incrementAndGet();
                responder(ex, 200, "{\"items\":[],\"nextCursor\":null}");
            });
            stubRegistry.start();
            puertoRegistry = stubRegistry.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo levantar el stub del registry", e);
        }
    }

    @AfterAll
    static void bajarStub() {
        if (stubRegistry != null) {
            stubRegistry.stop(0);
        }
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        // el downstream intenta pisar un header de seguridad: el gateway debe ignorarlo (AF-05)
        ex.getResponseHeaders().add("X-Frame-Options", "ALLOWALL");
        ex.getResponseHeaders().add("Content-Security-Policy", "default-src *");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String registry = "http://127.0.0.1:" + puertoRegistry;
        ruta(r, 0, "registry-sensores-lectura", "/api/sensores/**", "GET", registry, "lectura", 5000);
        ruta(r, 1, "query-resumen", "/api/sensores/resumen", "GET", registry, "lectura", 5000);
    }

    private static void ruta(DynamicPropertyRegistry r, int i, String id, String patron,
                             String metodos, String destino, String clase, long timeoutMs) {
        r.add("gateway.rutas[" + i + "].id", () -> id);
        r.add("gateway.rutas[" + i + "].patron", () -> patron);
        String[] ms = metodos.split(",");
        for (int j = 0; j < ms.length; j++) {
            int idx = j;
            r.add("gateway.rutas[" + i + "].metodos[" + j + "]", () -> ms[idx]);
        }
        r.add("gateway.rutas[" + i + "].destino", () -> destino);
        r.add("gateway.rutas[" + i + "].clase-limite", () -> clase);
        r.add("gateway.rutas[" + i + "].timeout-ms", () -> String.valueOf(timeoutMs));
    }

    @Autowired
    RateLimiterEnMemoria limiter;

    @LocalServerPort
    int puerto;

    private WebTestClient cliente;

    @BeforeEach
    void limpiar() {
        cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto)
                .responseTimeout(Duration.ofSeconds(15)).build();
        limiter.limpiar();
        REGISTRY_LLAMADAS.set(0);
    }

    // ===== AC-003 / AF-05: headers de seguridad en toda respuesta =====

    @Test
    @DisplayName("AC-003/AF-05: la API sale con los headers del gateway, una sola vez (el downstream no los pisa)")
    void ac003_headersEnLaApi() {
        var headers = cliente.get().uri("/api/sensores").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders();

        assertThat(headers.get("Content-Security-Policy")).as("un único valor")
                .hasSize(1);
        assertThat(headers.getFirst("Content-Security-Policy")).doesNotContain("default-src *");
        assertThat(headers.get("X-Frame-Options")).hasSize(1);
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("Permissions-Policy")).contains("geolocation=()");
        assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(REGISTRY_LLAMADAS.get()).as("la request llegó al downstream").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-003: los errores del gateway también llevan los headers")
    void ac003_headersEnErrores() {
        cliente.get().uri("/api/loquesea").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-004: HSTS sólo con X-Forwarded-Proto https")
    void ac004_hsts() {
        cliente.get().uri("/api/sensores").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("Strict-Transport-Security");
        cliente.get().uri("/api/sensores").header("X-Forwarded-Proto", "https").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Strict-Transport-Security",
                        "max-age=31536000; includeSubDomains");
    }

    // ===== AC-006 / S2: hosting del SPA sin romper el contrato de la API =====

    @Test
    @DisplayName("AC-006: GET / y las rutas de cliente devuelven el índice del SPA")
    void ac006_spaServido() {
        cliente.get().uri("/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CONTENT_TYPE, "text/html")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(String.class).value(cuerpo -> assertThat(cuerpo).contains("id=\"root\""));

        cliente.get().uri("/sensores/00000000-0000-4000-8000-00000000000a").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(cuerpo -> assertThat(cuerpo).contains("id=\"root\""));
        cliente.get().uri("/login").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("AC-006: /api/** no declarado sigue siendo 404 JSON y los assets inexistentes 404 sin HTML")
    void ac006_contratoDeLaApiIntacto() {
        cliente.get().uri("/api/loquesea").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches(HttpHeaders.CONTENT_TYPE, ".*json.*")
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");

        cliente.get().uri("/assets/noexiste-abc12345.js").exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).value(cuerpo ->
                        assertThat(cuerpo).as("nunca el índice para un asset").doesNotContain("id=\"root\""));
        cliente.get().uri("/favicon.ico").exchange().expectStatus().isNotFound();
        assertThat(REGISTRY_LLAMADAS.get()).as("nada de esto tocó el downstream").isZero();
    }

    @Test
    @DisplayName("AC-005/AF-03: traversal y paths fuera de static/ se rechazan sin filtrar el repo")
    void ac005_traversal() {
        for (String path : new String[]{"/../application.yml", "/..%2f..%2fapplication.yml",
                "/WEB-INF/web.xml", "/assets/../../application.yml"}) {
            var cuerpo = cliente.get().uri(path).exchange()
                    .expectStatus().isNotFound()
                    .expectBody(String.class).returnResult().getResponseBody();
            assertThat(cuerpo == null ? "" : cuerpo)
                    .as("no expone archivos del repo en " + path)
                    .doesNotContain("jwt-secreto").doesNotContain("datasource").doesNotContain("id=\"root\"");
        }
    }

    @Test
    @DisplayName("AC-006/BR-006: /ws/** nunca recibe el índice del SPA (404 si la ruta no está declarada)")
    void ac006_wsIntacto() {
        // Este contexto declara sólo rutas REST: un /ws/** no declarado debe seguir siendo 404
        // ROUTE_NOT_FOUND (nunca 200 con el HTML del SPA). El 426 con la ruta declarada y sin
        // upgrade se verifica en FEAT0008MainFlowIT, que sí declara /ws/**.
        cliente.get().uri("/ws/alertas").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        cliente.get().uri("/ws/sensores/abc").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND");
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.ServidorSpa
                .esPrefijoReservado("/ws/alertas")).isTrue();
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.ServidorSpa
                .esPrefijoReservado("/api/sensores")).isTrue();
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.ServidorSpa
                .esPrefijoReservado("/sensores/abc")).isFalse();
    }

    // ===== AC-007: caché =====

    @Test
    @DisplayName("AC-007: el índice no se cachea y el asset con hash sí (inmutable)")
    void ac007_cache() throws IOException {
        // un asset de prueba en el mismo classpath de test que el índice
        java.nio.file.Path dir = java.nio.file.Path.of("target", "test-classes", "static", "assets");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("index-abc12345.js"), "console.log(1);");

        cliente.get().uri("/").exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store");
        cliente.get().uri("/assets/index-abc12345.js").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*immutable.*");
    }
}