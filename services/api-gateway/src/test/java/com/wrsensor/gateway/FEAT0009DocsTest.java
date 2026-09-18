package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0009 — AC-011 (documentación y configuración) y BR-003/BR-009 (nada hardcodeado, estado del
 * frontend registrado). Verifica mecánicamente los archivos en lugar de confiar en la inspección
 * manual, siguiendo el patrón de `FIX0008DocsTest`.
 */
class FEAT0009DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-011/BR-011: el RUNBOOK explica cómo correr y servir el SPA y sus variables")
    void ac011_runbook() throws IOException {
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook)
                .contains("web/")
                .contains("npm ci")
                .contains("npm run build")
                .contains("npm test")
                .contains("VITE_")
                .contains("5173")
                .contains("con-spa")
                .contains("GATEWAY_STATIC_LOCATION")
                .contains("proxy");
    }

    @Test
    @DisplayName("AC-011/BR-011: ARQUITECTURA documenta el frontend y el empaquetado del SPA")
    void ac011_arquitectura() throws IOException {
        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("Frontend").contains("Vite").contains("Leaflet")
                .contains("classpath:/static/").contains("web/dist");
    }

    @Test
    @DisplayName("AC-011: hay ADR de las decisiones de stack y empaquetado del SPA")
    void ac011_adr() throws IOException {
        String adr = leer("docs/DECISIONES.md");
        assertThat(adr).contains("## ADR-0021").contains("FEAT-0009")
                .contains("sessionStorage").contains("Vite");
    }

    @Test
    @DisplayName("BR-003/BR-009: el contract cierra, el tablero lo refleja y el changelog lo registra")
    void br009_cierre() throws IOException {
        assertThat(leer("contracts/FEAT-0009.md"))
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/ESTADO-SDD.md")).contains("FEAT-0009");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FEAT-0009]");
        assertThat(leer("docs/CONTINUIDAD.md")).contains("FEAT-0009");
    }

    @Test
    @DisplayName("BR-003/A10: el SPA no tiene URLs de backend ni puertos hardcodeados")
    void br003_sinHardcodeos() throws IOException {
        Path src = RAIZ.resolve("web/src");
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.walk(src)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".ts")
                    || p.toString().endsWith(".tsx")).toList()) {
                if (p.getFileName().toString().contains(".test.")) {
                    continue;
                }
                sb.append(sinComentarios(Files.readString(p, StandardCharsets.UTF_8))).append('\n');
            }
        }
        String codigo = sb.toString();
        assertThat(codigo).as("sin localhost/puertos en el código del SPA")
                .doesNotContain("localhost:").doesNotContain("127.0.0.1").doesNotContain(":8084");
        assertThat(codigo).as("sin sink de XSS (A2/A3)")
                .doesNotContain("dangerouslySetInnerHTML").doesNotContain(".innerHTML");
        assertThat(codigo).as("el token no se persiste fuera de sessionStorage ni se loguea")
                .doesNotContain("localStorage").doesNotContain("document.cookie")
                .doesNotContain("console.log");
        assertThat(codigo).as("siempre mismo origen: la base viene de config")
                .contains("urlApi(").contains("config.apiBase");
    }

    /**
     * Quita comentarios de bloque y de línea: la regla verifica **código**, y los comentarios del
     * proyecto explican justamente lo que está prohibido (por ejemplo por qué no se usa
     * `localStorage`), así que escanearlos daría un falso positivo.
     */
    private static String sinComentarios(String fuente) {
        return fuente
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}