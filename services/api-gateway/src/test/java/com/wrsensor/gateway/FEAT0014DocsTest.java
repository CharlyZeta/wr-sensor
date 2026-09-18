package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0014 — AC-011 y BR-009: el detalle en vivo queda documentado y configurable, y el contract
 * cierra con el mapa completo. Verificación mecánica sobre los archivos (patrón de los tests de docs
 * de FIX-0008/FEAT-0009).
 */
class FEAT0014DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-011/BR-009: el RUNBOOK documenta las variables del detalle y cómo probar el WS")
    void ac011_runbook() throws IOException {
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook)
                .contains("VITE_SERIE_HORAS")
                .contains("VITE_SERIE_MAX_PUNTOS")
                .contains("VITE_WS_BACKOFF_BASE_MS")
                .contains("VITE_DATO_VENCIDO_MS")
                .contains("VITE_TABLA_FILAS")
                .contains("/ws/sensores/");
    }

    @Test
    @DisplayName("AC-011: ARQUITECTURA documenta el flujo en vivo (WS → estado de la vista → gráfico/tabla)")
    void ac011_arquitectura() throws IOException {
        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("useLecturasEnVivo")
                .contains("SerieTemporal")
                .contains("backoff")
                .contains("WS /ws/sensores/{id}");
    }

    @Test
    @DisplayName("AC-011: las variables del detalle están en .env.example (defaults documentados)")
    void ac011_envExample() throws IOException {
        String env = leer("web/.env.example");
        assertThat(env).contains("VITE_SERIE_HORAS=24")
                .contains("VITE_SERIE_MAX_PUNTOS=")
                .contains("VITE_WS_BACKOFF_BASE_MS=")
                .contains("VITE_WS_BACKOFF_TOPE_MS=")
                .contains("VITE_WS_MAX_INTENTOS=")
                .contains("VITE_DATO_VENCIDO_MS=")
                .contains("VITE_TABLA_FILAS=");
    }

    @Test
    @DisplayName("BR-009: el contract cierra 29/29 y el tablero/changelog lo reflejan")
    void br009_cierre() throws IOException {
        assertThat(leer("contracts/FEAT-0014.md"))
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/ESTADO-SDD.md")).contains("FEAT-0014");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FEAT-0014]");
    }

    @Test
    @DisplayName("BR-001/A5: el código del detalle no expone el token ni usa sinks de XSS")
    void br001_sinFugas() throws IOException {
        String hook = leer("web/src/hooks/useLecturasEnVivo.ts");
        String pagina = leer("web/src/features/detalle/DetallePage.tsx");
        for (String fuente : new String[]{hook, pagina}) {
            assertThat(sinComentarios(fuente))
                    .as("sin sinks de XSS ni logs de la URL con token")
                    .doesNotContain("dangerouslySetInnerHTML")
                    .doesNotContain(".innerHTML")
                    .doesNotContain("console.log");
        }
        // el token se agrega al query con el helper de config, que lo marca como secreto
        assertThat(hook).contains("urlWebSocket(");
    }

    private static String sinComentarios(String fuente) {
        return fuente.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}