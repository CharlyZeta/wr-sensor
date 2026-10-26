package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0017 — Verificación mecánica del banner visual de cabecera y badges en README.md.
 */
class FEAT0017DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Main Flow / integration-test:FEAT-0017-main: El README despliega portada con banner y badges")
    void mainFlow_portada() throws IOException {
        String readme = leer("README.md");
        assertThat(readme)
                .contains("docs/assets/wrsensor-banner.jpg")
                .contains("img.shields.io/badge/");
    }

    @Test
    @DisplayName("BR-001/unit-test:FEAT-0017-br001: Todos los assets de imagen del README están en docs/assets/ con rutas relativas")
    void br001_assetsEnDocsAssets() throws IOException {
        String readme = leer("README.md");
        assertThat(RAIZ.resolve("docs/assets/wrsensor-banner.jpg")).exists();
        assertThat(readme).contains("docs/assets/wrsensor-banner.jpg");
        // No deben quedar referencias huérfanas a imágenes locales fuera de docs/assets/
        assertThat(readme).doesNotContain("docs/Wrs.jpg");
    }

    @Test
    @DisplayName("BR-002/unit-test:FEAT-0017-br002: La documentación técnica se preserva en las dos versiones del README")
    void br002_preservaDocumentacionTecnica() throws IOException {
        // FEAT-0019: el README pasó a ser bilingüe (README.md en inglés, README.es.md en español).
        // Se verifica la misma estructura de secciones en los dos, no solo en uno.
        String ingles = leer("README.md");
        String espanol = leer("README.es.md");
        assertThat(ingles)
                .contains("## 1. What it is")
                .contains("## 2. Architecture (overview)")
                .contains("## 3. Documentation (index)")
                .contains("## 4. Work item status")
                .contains("## 5. Quickstart")
                .contains("## 6. About the SDD-GL frame");
        assertThat(espanol)
                .contains("## 1. Qué es")
                .contains("## 2. Arquitectura (resumen)")
                .contains("## 3. Documentación (índice)")
                .contains("## 4. Estado de los Work Items")
                .contains("## 5. Quickstart")
                .contains("## 6. Nota sobre el frame SDD-GL");
        assertThat(ingles)
                .contains("data-simulator")
                .contains("sensor-registry")
                .contains("ingestion-service")
                .contains("alerting-service")
                .contains("query-api");
    }

    @Test
    @DisplayName("AF-01/unit-test:FEAT-0017-af01: El banner incluye atributo alt accesible")
    void af01_altAccessible() throws IOException {
        String readme = leer("README.md");
        assertThat(readme).contains("alt=\"WR-Sensor Architecture Banner\"");
    }

    @Test
    @DisplayName("AC-001/assertion:FEAT-0017-ac001: Existe el archivo docs/assets/wrsensor-banner.jpg")
    void ac001_existeBanner() {
        Path banner = RAIZ.resolve("docs/assets/wrsensor-banner.jpg");
        assertThat(banner).as("Existe docs/assets/wrsensor-banner.jpg").exists();
        assertThat(Files.isRegularFile(banner)).isTrue();
    }

    @Test
    @DisplayName("AC-002/assertion:FEAT-0017-ac002: El README contiene referencia al banner y badges de estado")
    void ac002_readmeContieneReferenciaYBadges() throws IOException {
        String readme = leer("README.md");
        assertThat(readme)
                .contains("docs/assets/wrsensor-banner.jpg")
                .contains("shields.io/badge/tests")
                .contains("shields.io/badge/Java");
    }
}
