package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0015 — AC-011 y BR-009: el feed de alertas queda documentado y configurable, el contract cierra
 * con el mapa completo y el código no expone el token ni usa sinks de XSS. Verificación mecánica sobre
 * los archivos (patrón de los tests de docs de los work items anteriores).
 */
class FEAT0015DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-011/BR-009: el RUNBOOK documenta el feed, sus variables y cómo probarlo")
    void ac011_runbook() throws IOException {
        assertThat(leer("docs/RUNBOOK.md"))
                .contains("VITE_ALERTAS_MAX")
                .contains("VITE_ALERTAS_DEBOUNCE_MS")
                .contains("/ws/alertas")
                .contains("websocat")
                .contains("ráfaga");
    }

    @Test
    @DisplayName("AC-011: ARQUITECTURA documenta el flujo de alertas (WS → feed → refresco del resumen)")
    void ac011_arquitectura() throws IOException {
        assertThat(leer("docs/ARQUITECTURA.md"))
                .contains("ProveedorAlertas")
                .contains("WS /ws/alertas")
                .contains("ráfagas")
                .contains("deduplicado");
    }

    @Test
    @DisplayName("AC-011: las variables del feed están en .env.example (defaults documentados)")
    void ac011_envExample() throws IOException {
        assertThat(leer("web/.env.example"))
                .contains("VITE_ALERTAS_MAX=")
                .contains("VITE_ALERTAS_DEBOUNCE_MS=");
    }

    @Test
    @DisplayName("BR-009: el contract cierra 29/29 y el tablero/changelog lo reflejan")
    void br009_cierre() throws IOException {
        assertThat(leer("contracts/FEAT-0015.md"))
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/ESTADO-SDD.md")).contains("FEAT-0015");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FEAT-0015]");
    }

    @Test
    @DisplayName("BR-001/A5/A2: el código del feed no expone el token ni usa sinks de XSS")
    void br001_sinFugas() throws IOException {
        for (String archivo : new String[]{
                "web/src/alertas/ProveedorAlertas.tsx",
                "web/src/features/alertas/FeedAlertas.tsx",
                "web/src/features/alertas/AlertasPage.tsx"}) {
            assertThat(sinComentarios(leer(archivo)))
                    .as("sin sinks de XSS ni logs en " + archivo)
                    .doesNotContain("dangerouslySetInnerHTML")
                    .doesNotContain(".innerHTML")
                    .doesNotContain("console.log");
        }
        // el token se agrega al query con el helper de config (que lo documenta como secreto)
        assertThat(leer("web/src/alertas/ProveedorAlertas.tsx")).contains("urlWebSocket(");
    }

    private static String sinComentarios(String fuente) {
        return fuente.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}