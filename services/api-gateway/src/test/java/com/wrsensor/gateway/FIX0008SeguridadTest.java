package com.wrsensor.gateway;

import com.wrsensor.gateway.infrastructure.adapter.in.web.FiltroSeguridad;
import com.wrsensor.gateway.infrastructure.adapter.in.web.ServidorSpa;
import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FIX-0008 BR-003/BR-004/BR-005/BR-006: headers de seguridad (incluida HSTS según el
 * protocolo real), CSP por default sin {@code unsafe-inline}/{@code unsafe-eval} en scripts, y las
 * reglas de resolución del SPA (rutas de cliente, assets y rechazo de traversal).
 */
class FIX0008SeguridadTest {

    private static GatewayProperties props(GatewayProperties.SeguridadCfg seguridad) {
        return new GatewayProperties(List.of(), null, null, null, seguridad);
    }

    private static GatewayProperties.SeguridadCfg cfg(String csp, List<String> rutasCliente) {
        return new GatewayProperties.SeguridadCfg(null, csp, rutasCliente, null, null, null, null,
                null, null);
    }

    private static MockServerWebExchange get(String path, String... headers) {
        var builder = MockServerHttpRequest.get(path);
        for (int i = 0; i < headers.length; i += 2) {
            builder = builder.header(headers[i], headers[i + 1]);
        }
        return MockServerWebExchange.from(builder);
    }

    private static MockServerWebExchange aplicar(FiltroSeguridad filtro, MockServerWebExchange ex) {
        WebFilterChain cadena = e -> Mono.empty();
        filtro.filter(ex, cadena).block();
        return ex;
    }

    // ===== BR-003 / AC-003: headers presentes y únicos =====

    @Test
    @DisplayName("BR-003/AC-003: toda respuesta sale con los headers de seguridad")
    void br003_headersPresentes() {
        MockServerWebExchange ex = aplicar(new FiltroSeguridad(props(cfg(null, null))),
                get("/api/sensores"));

        var h = ex.getResponse().getHeaders();
        assertThat(h.getFirst("Content-Security-Policy")).isNotBlank();
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Permissions-Policy")).contains("geolocation=()");
        assertThat(h.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }

    @Test
    @DisplayName("BR-003: el downstream no puede duplicar ni pisar los headers de seguridad")
    void br003_downstreamNoDuplica() {
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.RespuestasGateway
                .propagable("Content-Security-Policy")).isFalse();
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.RespuestasGateway
                .propagable("X-Frame-Options")).isFalse();
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.RespuestasGateway
                .propagable("Referrer-Policy")).isFalse();
        assertThat(com.wrsensor.gateway.infrastructure.adapter.in.web.RespuestasGateway
                .propagable("x-correlation-id")).as("lo demás sí se propaga").isTrue();
    }

    // ===== BR-004 / AC-004: CSP y HSTS =====

    @Test
    @DisplayName("AC-004/BR-004: la CSP por default no permite unsafe-inline ni unsafe-eval en scripts")
    void ac004_cspEstricta() {
        String csp = new GatewayProperties.SeguridadCfg(null, null, null, null, null, null, null, null,
                null).contentSecurityPolicyOrDefault();

        String scriptSrc = csp.split(";")[1].trim();
        assertThat(scriptSrc).as("script-src es la directiva que protege el token")
                .isEqualTo("script-src 'self'")
                .doesNotContain("unsafe-inline").doesNotContain("unsafe-eval").doesNotContain("*");
        assertThat(csp).contains("object-src 'none'").contains("base-uri 'none'")
                .contains("frame-ancestors 'none'").contains("form-action 'self'")
                .contains("connect-src 'self'");
        assertThat(csp).as("img-src acotado a lo propio y a los tiles configurados")
                .contains("img-src 'self' data:")
                .doesNotContain("img-src *").doesNotContain("img-src https:;");
    }

    @Test
    @DisplayName("AC-004: HSTS sólo cuando la request llegó por HTTPS (directo o por X-Forwarded-Proto)")
    void ac004_hstsSegunProtocolo() {
        FiltroSeguridad filtro = new FiltroSeguridad(props(cfg(null, null)));

        MockServerWebExchange http = aplicar(filtro, get("/api/sensores"));
        assertThat(http.getResponse().getHeaders().getFirst("Strict-Transport-Security"))
                .as("sin TLS no se anuncia HSTS").isNull();

        MockServerWebExchange https = aplicar(filtro,
                get("/api/sensores", "X-Forwarded-Proto", "https"));
        assertThat(https.getResponse().getHeaders().getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");

        MockServerWebExchange mayusculas = aplicar(filtro,
                get("/api/sensores", "X-Forwarded-Proto", "HTTPS, http"));
        assertThat(mayusculas.getResponse().getHeaders().getFirst("Strict-Transport-Security"))
                .as("toma el primer valor del header y compara sin distinguir mayúsculas")
                .isNotNull();
    }

    @Test
    @DisplayName("BR-004/AF-06: la CSP es configurable (orígenes de tiles u otros ajustes)")
    void br004_cspConfigurable() {
        String propia = "default-src 'self'; script-src 'self'; img-src 'self' https://tiles.mi-red";
        MockServerWebExchange ex = aplicar(new FiltroSeguridad(props(cfg(propia, null))),
                get("/mapa"));
        assertThat(ex.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(propia);
    }

    @Test
    @DisplayName("BR-007: el índice se sirve sin caché (configurable) y sin valores hardcodeados")
    void br007_cacheConfigurable() {
        GatewayProperties.SeguridadCfg c = new GatewayProperties.SeguridadCfg(null, null, null, null,
                60L, 10L, null, null, null);
        assertThat(c.cacheAssetsSegundosOrDefault()).isEqualTo(60L);
        assertThat(c.cacheEstaticosSegundosOrDefault()).isEqualTo(10L);
        assertThat(c.cacheAssetsSegundosOrDefault()).as("default: inmutable por hash")
                .isEqualTo(60L);
        assertThat(new GatewayProperties.SeguridadCfg(null, null, null, null, null, null, null, null,
                null).cacheAssetsSegundosOrDefault()).isEqualTo(31_536_000L);
    }

    // ===== BR-005 / AF-03: resolución contenida =====

    @Test
    @DisplayName("BR-005/AF-03: paths con traversal, absolutos o raros se rechazan")
    void br005_pathsPeligrosos() {
        ServidorSpa spa = new ServidorSpa(cfg(null, null));

        assertThat(spa.pathSeguro("/../application.yml")).isFalse();
        assertThat(spa.pathSeguro("/..%2f..%2fapplication.yml")).as("Spring decodifica: llega '..'")
                .isFalse();
        assertThat(spa.pathSeguro("/assets/../application.yml")).isFalse();
        assertThat(spa.pathSeguro("\\..\\application.yml")).isFalse();
        assertThat(spa.pathSeguro("/assets//x.js")).isFalse();
        assertThat(spa.pathSeguro("/C:/Windows/win.ini")).isFalse();
        assertThat(spa.pathSeguro("/~/.ssh/id_rsa")).isFalse();
        assertThat(spa.pathSeguro("/" + "a".repeat(600))).as("path absurdamente largo").isFalse();
        assertThat(spa.pathSeguro("/assets/index-abc12345.js")).isTrue();
        assertThat(spa.pathSeguro("/sensores/00000000-0000-4000-8000-00000000000a")).isTrue();
    }

    // ===== BR-006 / AC-006: qué es ruta de cliente y qué no =====

    @Test
    @DisplayName("AC-006/BR-006: sólo las rutas de cliente caen al índice; los assets nunca")
    void ac006_rutasDeCliente() {
        ServidorSpa spa = new ServidorSpa(cfg(null, null));

        assertThat(spa.esRutaDeCliente("/")).isTrue();
        assertThat(spa.esRutaDeCliente("/login")).isTrue();
        assertThat(spa.esRutaDeCliente("/mapa")).isTrue();
        assertThat(spa.esRutaDeCliente("/sensores")).isTrue();
        assertThat(spa.esRutaDeCliente("/sensores/abc-123")).isTrue();

        assertThat(spa.esRutaDeCliente("/assets/index-abc.js")).isFalse();
        assertThat(spa.esRutaDeCliente("/favicon.ico")).isFalse();
        assertThat(spa.esRutaDeCliente("/otra-cosa")).isFalse();
    }

    @Test
    @DisplayName("BR-006: las rutas de cliente son configurables (nunca hardcodeadas)")
    void br006_rutasConfigurables() {
        ServidorSpa spa = new ServidorSpa(cfg(null, List.of("/panel", "/panel/sensores")));
        assertThat(spa.esRutaDeCliente("/panel")).isTrue();
        assertThat(spa.esRutaDeCliente("/panel/sensores/7")).isTrue();
        assertThat(spa.esRutaDeCliente("/sensores")).as("ya no está configurada").isFalse();
        assertThat(spa.esRutaDeCliente("/")).as("la raíz siempre es ruta de cliente").isTrue();
        assertThat(spa.rutasDeCliente()).containsExactly("/panel", "/panel/sensores");
    }

    @Test
    @DisplayName("BR-003/BR-004: el filtro aplica la CSP a respuestas de error de otros filtros")
    void br003_headersEnErrores() {
        // simula lo que hace el handler de errores: escribe el status y corta la cadena
        MockServerWebExchange ex = get("/api/sensores");
        WebFilterChain corta = e -> {
            e.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return Mono.empty();
        };
        new FiltroSeguridad(props(cfg(null, null))).filter(ex, corta).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getResponse().getHeaders().getFirst("Content-Security-Policy")).isNotBlank();
        assertThat(ex.getResponse().getHeaders().getFirst("X-Frame-Options"))
                .isEqualTo("DENY");
    }
}