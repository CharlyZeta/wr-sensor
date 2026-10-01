package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0009 — Verificación mecánica de portabilidad del Quickstart en README.md
 * y comandos del ciclo de vida de la SPA web.
 */
class FIX0009DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String obtenerSeccion5Quickstart() throws IOException {
        String contenido = leer("README.md");
        int inicio = contenido.indexOf("## 5. Quickstart");
        assertThat(inicio).as("README.md contiene sección 5. Quickstart").isGreaterThan(0);
        int fin = contenido.indexOf("## 6. Nota sobre el frame SDD-GL", inicio);
        if (fin < 0) {
            fin = contenido.length();
        }
        return contenido.substring(inicio, fin);
    }

    // ===== Reproduction Steps / Main =====

    @Test
    @DisplayName("Reproduction Steps / integration-test:FIX-0009-main: El Quickstart es completo y portable")
    void reproductionSteps_main() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion)
                .contains("mvn -o test")
                .contains("cd web")
                .contains("npm test")
                .contains("npm run dev");
    }

    // ===== Business Rules =====

    @Test
    @DisplayName("BR-001/unit-test:FIX-0009-br001: Ningún comando o texto en README.md incluye rutas absolutas a carpetas de usuario (C:\\Users\\)")
    void br001_sinRutasDeUsuario() throws IOException {
        String readme = leer("README.md");
        assertThat(readme).as("README.md no debe contener rutas absolutas de usuario local")
                .doesNotContain("C:\\Users\\")
                .doesNotContain("C:/Users/");
    }

    @Test
    @DisplayName("BR-002/unit-test:FIX-0009-br002: Documenta comandos de ciclo de vida tanto para backend (Maven) como para frontend (npm)")
    void br002_comandosCicloDeVidaBackendYFrontend() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion)
                .contains("mvn")
                .contains("npm");
    }

    // ===== Alternative Flows =====

    @Test
    @DisplayName("AF-01/unit-test:FIX-0009-af01: Guía el uso de test en Frontend (npm test)")
    void af01_testsFrontend() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion).contains("npm test");
    }

    @Test
    @DisplayName("AF-02/unit-test:FIX-0009-af02: Guía el uso de dev server en Frontend (npm run dev / 5173)")
    void af02_devServerFrontend() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion)
                .contains("npm run dev")
                .contains("5173");
    }

    // ===== Acceptance Criteria =====

    @Test
    @DisplayName("AC-001/assertion:FIX-0009-ac001: La sección 5 no contiene referencias a carpetas de usuario personales (Gerardo o similares)")
    void ac001_sinReferenciasPersonales() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion).as("Sección Quickstart sin referencias personales de usuario")
                .doesNotContain("Gerardo");
    }

    @Test
    @DisplayName("AC-002/assertion:FIX-0009-ac002: La sección 5 documenta los comandos de la SPA (cd web, npm ci, npm test, npm run dev)")
    void ac002_comandosSpa() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion)
                .contains("cd web")
                .contains("npm ci")
                .contains("npm test")
                .contains("npm run dev");
    }

    @Test
    @DisplayName("AC-003/assertion:FIX-0009-ac003: Los comandos son portables entre Windows, Linux y macOS")
    void ac003_comandosPortables() throws IOException {
        String seccion = obtenerSeccion5Quickstart();
        assertThat(seccion)
                .doesNotContain("C:/Program Files")
                .doesNotContain("C:\\Program Files");
        assertThat(seccion)
                .contains("mvn -o test");
    }
}
