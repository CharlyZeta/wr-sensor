package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0016 — AC-012 y BR-010: la administración y el panel de demo quedan documentados y
 * configurables, el contract cierra con el mapa completo y el código no expone el token ni usa sinks
 * de XSS. Verificación mecánica sobre los archivos.
 */
class FEAT0016DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-012/BR-010: el RUNBOOK explica la administración y cómo habilitar el panel de demo")
    void ac012_runbook() throws IOException {
        assertThat(leer("docs/RUNBOOK.md"))
                .contains("VITE_SIMULADOR_URL")
                .contains("docker-compose.dev.yml")
                .contains("SENSOR_CODE_DUPLICATED")
                .contains("baja **lógica**")
                .contains("no tiene ruta en el gateway");
    }

    @Test
    @DisplayName("AC-012: ARQUITECTURA documenta el CRUD, la validación y el panel de demo")
    void ac012_arquitectura() throws IOException {
        assertThat(leer("docs/ARQUITECTURA.md"))
                .contains("panel de demo")
                .contains("keyset")
                .contains("se reintenta automáticamente")
                .contains("RolGuard");
    }

    @Test
    @DisplayName("AC-012: la variable del panel está en .env.example (default documentado)")
    void ac012_envExample() throws IOException {
        assertThat(leer("web/.env.example"))
                .contains("VITE_SIMULADOR_URL=")
                .contains("docker-compose.dev.yml");
    }

    @Test
    @DisplayName("BR-009: el contract cierra 31/31 y el tablero/changelog lo reflejan")
    void br009_cierre() throws IOException {
        assertThat(leer("contracts/FEAT-0016.md"))
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/ESTADO-SDD.md")).contains("FEAT-0016");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FEAT-0016]");
    }

    @Test
    @DisplayName("BR-001/BR-002: el código del CRUD no usa sinks de XSS y muestra errores por code")
    void br001_sinSinks() throws IOException {
        for (String archivo : new String[]{
                "web/src/features/admin/AdminSensoresPage.tsx",
                "web/src/features/admin/AdminFormPage.tsx",
                "web/src/features/admin/FormularioSensor.tsx",
                "web/src/features/demo/PanelDemo.tsx"}) {
            assertThat(sinComentarios(leer(archivo)))
                    .as("sin sinks de XSS ni logs en " + archivo)
                    .doesNotContain("dangerouslySetInnerHTML")
                    .doesNotContain(".innerHTML")
                    .doesNotContain("console.log");
        }
        String form = leer("web/src/features/admin/AdminFormPage.tsx");
        assertThat(form).as("los errores del backend se muestran por code").contains("e.code");
        assertThat(leer("web/src/features/demo/PanelDemo.tsx"))
                .as("el panel se apaga por configuración, no por código condicional hardcodeado")
                .contains("config.simuladorUrl");
    }

    private static String sinComentarios(String fuente) {
        return fuente.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}