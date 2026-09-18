package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0008 — AC-001 (el secreto del WS llega al gateway), BR-008/BR-009 y AC-008: configuración y
 * documentación. Los criterios de "configurado y documentado" se verifican mecánicamente sobre los
 * archivos, no por inspección manual.
 */
class FIX0008DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ===== AC-001: S1 — el secreto viaja al gateway =====

    @Test
    @DisplayName("AC-001/BR-001: docker-compose pasa AUTH_JWT_SECRET al api-gateway (hallazgo S1)")
    void ac001_secretoEnCompose() throws IOException {
        String compose = leer("docker-compose.yml");
        int inicio = compose.indexOf("  api-gateway:");
        assertThat(inicio).as("existe el servicio api-gateway").isGreaterThan(0);
        String bloque = compose.substring(inicio, compose.indexOf("\n\n", inicio) < 0
                ? compose.length() : compose.indexOf("\n\n", inicio));

        assertThat(bloque).as("el gateway valida el JWT del WS: necesita el secreto compartido")
                .contains("AUTH_JWT_SECRET")
                .contains("${AUTH_JWT_SECRET:-wrsensor-dev-secret-2026-no-usar-en-prod}");
        // y el registry usa exactamente el mismo default por entorno (mismo secreto en todo el stack)
        assertThat(compose).contains("AUTH_JWT_SECRET: ${AUTH_JWT_SECRET:-wrsensor-dev-secret-2026-no-usar-en-prod}");
    }

    // ===== BR-008: configuración sin valores hardcodeados =====

    @Test
    @DisplayName("BR-008/AC-004: los headers y la CSP son configurables en application.yml")
    void br008_configuracion() throws IOException {
        String yml = leer("services/api-gateway/src/main/resources/application.yml");
        assertThat(yml).contains("seguridad:")
                .contains("content-security-policy")
                .contains("GATEWAY_CSP")
                .contains("permisos-politica")
                .contains("rutas-cliente")
                .contains("static-location")
                .contains("cache-assets-segundos")
                .contains("GATEWAY_CACHE_ASSETS")
                .contains("hsts")
                .contains("perfiles-desarrollo");
        // la CSP declara explícitamente la excepción de estilos y NO la de scripts
        String csp = yml.substring(yml.indexOf("content-security-policy"));
        csp = csp.substring(0, csp.indexOf('\n'));
        assertThat(csp).contains("script-src 'self';").contains("style-src 'self' 'unsafe-inline'");
        assertThat(csp).as("script-src sin unsafe-inline ni unsafe-eval")
                .doesNotContain("script-src 'self' 'unsafe-inline'").doesNotContain("unsafe-eval");
    }

    // ===== AC-008: documentación =====

    @Test
    @DisplayName("AC-008: el RUNBOOK documenta los headers, la CSP y cómo verificar el secreto del WS")
    void ac008_runbook() throws IOException {
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook).contains("Content-Security-Policy")
                .contains("AUTH_JWT_SECRET")
                .contains("printenv AUTH_JWT_SECRET")
                .contains("X-Content-Type-Options")
                .contains("Strict-Transport-Security")
                .contains("GATEWAY_CSP");
    }

    @Test
    @DisplayName("AC-008: ARQUITECTURA documenta la cadena de filtros con la seguridad y el hosting del SPA")
    void ac008_arquitectura() throws IOException {
        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("FiltroSeguridad")
                .contains("classpath:/static/")
                .contains("ROUTE_NOT_FOUND")
                .contains("static");
    }

    @Test
    @DisplayName("AC-008: hay ADR de la decisión (headers en el gateway y resolución segura de estáticos)")
    void ac008_adr() throws IOException {
        String adr = leer("docs/DECISIONES.md");
        assertThat(adr).contains("## ADR-0020")
                .contains("FIX-0008")
                .contains("Content-Security-Policy")
                .contains("AUTH_JWT_SECRET");
    }

    @Test
    @DisplayName("BR-009: el contrato cierra con el mapa completo y queda el audit del Loop")
    void br009_cierre() throws IOException {
        assertThat(leer("contracts/FIX-0008.md"))
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FIX-0008]");
        assertThat(Files.exists(RAIZ.resolve(".sdd/runs"))).isTrue();
    }
}