package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — FEAT-0009 (AC-001/AC-002/AC-009/AC-010/AC-011): el SPA se sirve desde el
 * gateway **sin romper el contrato de la API**.
 *
 * <p>Se apoya en el fixture `src/test/resources/static/index.html` (misma forma que el build de
 * Vite) y en un asset de prueba creado en `target/test-classes/static/`; en el empaquetado real el
 * contenido viene de `web/dist` (profile `con-spa` o el stage de Node del Dockerfile).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FEAT0009HostingIT {

    @LocalServerPort
    int puerto;

    @Autowired
    RateLimiterEnMemoria limiter;

    private WebTestClient cliente;

    @BeforeEach
    void limpiar() {
        cliente = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + puerto)
                .responseTimeout(Duration.ofSeconds(15)).build();
        limiter.limpiar();
    }

    // ===== AC-001 / AC-002: el SPA se sirve y las rutas del cliente caen al índice =====

    @Test
    @DisplayName("AC-001: GET / devuelve el índice del SPA (text/html, sin caché)")
    void ac001_indice() {
        cliente.get().uri("/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CONTENT_TYPE, "text/html.*")
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(String.class).value(cuerpo -> {
                    assertThat(cuerpo).contains("id=\"root\"");
                    assertThat(cuerpo).as("no es el dev server de Vite").doesNotContain("@vite/client");
                });
    }

    @Test
    @DisplayName("AC-002: las rutas del cliente devuelven el índice y la API sigue yendo a los servicios")
    void ac002_rutasDeCliente() {
        for (String ruta : new String[]{"/login", "/mapa", "/sensores",
                "/sensores/00000000-0000-4000-8000-00000000000a"}) {
            cliente.get().uri(ruta).exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).value(cuerpo -> assertThat(cuerpo)
                            .as("ruta de cliente " + ruta).contains("id=\"root\""));
        }
    }

    @Test
    @DisplayName("AC-002/AC-011: /api/** no declarado sigue siendo 404 ROUTE_NOT_FOUND en JSON")
    void ac002_apiIntacta() {
        cliente.get().uri("/api/loquesea").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueMatches(HttpHeaders.CONTENT_TYPE, ".*json.*")
                .expectBody()
                .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND")
                .jsonPath("$.message").exists();
        // el prefijo reservado del WS tampoco recibe el índice: con la ruta declarada (application.yml)
        // un request sin upgrade responde 426 WS_UPGRADE_REQUIRED, nunca 200 con el HTML del SPA
        cliente.get().uri("/ws/alertas").exchange()
                .expectStatus().isEqualTo(426)
                .expectBody().jsonPath("$.code").isEqualTo("WS_UPGRADE_REQUIRED");
        cliente.get().uri("/ws/alertas").exchange()
                .expectBody(String.class).value(cuerpo ->
                        assertThat(cuerpo).as("nunca el índice en un path de WS").doesNotContain("id=\"root\""));
    }

    // ===== AC-009 / AC-010: assets y caché =====

    @Test
    @DisplayName("AC-010/AC-009: el asset con hash se sirve con caché inmutable y extensión ausente es 404")
    void ac010_assets() throws Exception {
        var dir = java.nio.file.Path.of("target", "test-classes", "static", "assets");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("index-abc12345.js"), "console.log('spa');");

        cliente.get().uri("/assets/index-abc12345.js").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CACHE_CONTROL, ".*immutable.*")
                .expectBody(String.class).value(cuerpo -> assertThat(cuerpo).contains("spa"));

        cliente.get().uri("/assets/noexiste-abc12345.js").exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class).value(cuerpo ->
                        assertThat(cuerpo).as("nunca el índice para un asset").doesNotContain("id=\"root\""));
    }

    // ===== AC-011: sin valores hardcodeados / configurables =====

    @Test
    @DisplayName("AC-011: el origen de los estáticos es configurable (classpath por default, file: en Docker)")
    void ac011_origenConfigurable() throws Exception {
        String yml = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "services", "api-gateway", "src", "main", "resources",
                        "application.yml"));
        assertThat(yml).contains("static-location: ${GATEWAY_STATIC_LOCATION:classpath:static/}");
        String compose = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "docker-compose.yml"));
        assertThat(compose).contains("GATEWAY_STATIC_LOCATION: file:/app/static/");
        String dockerfile = java.nio.file.Files.readString(
                java.nio.file.Path.of("..", "..", "services", "api-gateway", "Dockerfile"));
        assertThat(dockerfile).as("el stage de Node construye el SPA")
                .contains("FROM node:").contains("npm ci").contains("npm run build")
                .contains("COPY --from=spa /web/dist/ /app/static/");
    }
}