package com.wrsensor.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0018 — Verificación mecánica de la CI real, el Maven wrapper y el Quickstart reproducible.
 *
 * <p>Cada test referencia su criterio del contrato. Si alguien agrega {@code -DskipTests} al
 * workflow, saca servicios de la matriz, omite los ITs o vuelve a hardcodear rutas absolutas en el
 * README, esta suite se pone roja.</p>
 */
class FEAT0018CiDocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path WORKFLOW = RAIZ.resolve(Path.of(".github", "workflows", "maven-build.yml"));

    private static final List<String> SERVICIOS = List.of(
            "sensor-registry",
            "data-simulator",
            "ingestion-service",
            "alerting-service",
            "query-api",
            "api-gateway");

    private static String leer(Path absoluto) throws IOException {
        assertThat(absoluto).as("existe " + absoluto).exists();
        return Files.readString(absoluto, StandardCharsets.UTF_8);
    }

    private static String leerWorkflow() throws IOException {
        return leer(WORKFLOW);
    }

    private static String leerReadme() throws IOException {
        return leer(RAIZ.resolve("README.md"));
    }

    private static String seccionQuickstart(String readme) {
        int inicio = readme.indexOf("## 5. Quickstart");
        assertThat(inicio).as("README.md contiene la sección 5. Quickstart").isGreaterThan(0);
        return readme.substring(inicio);
    }

    @Test
    @DisplayName("Main Flow / integration-test:FEAT-0018-main: El workflow existe y cubre los 6 servicios con una matriz")
    void mainFlow_workflowConMatrizPorServicio() throws IOException {
        String workflow = leerWorkflow();
        assertThat(workflow).contains("matrix:");
        assertThat(workflow).contains("fail-fast: false");
        for (String servicio : SERVICIOS) {
            assertThat(workflow).as("la matriz incluye " + servicio).contains(servicio);
        }
    }

    @Test
    @DisplayName("BR-001/unit-test:FEAT-0018-br001: Cada job corre por servicio con -pl, no un build monolítico")
    void br001_porServicio() throws IOException {
        String workflow = leerWorkflow();
        assertThat(workflow)
                .contains("working-directory: services")
                .contains("-pl ${{ matrix.service }}");
        assertThat(workflow).doesNotContain("mvn install");
    }

    @Test
    @DisplayName("BR-002+AC-006/unit-test:FEAT-0018-br002: Existe un job que ejecuta los integration tests (*IT)")
    void br002_itsEnElCi() throws IOException {
        String workflow = leerWorkflow();
        assertThat(workflow)
                .contains("integration-tests:")
                .contains("-Dtest='*IT'");
    }

    @Test
    @DisplayName("BR-003+AC-005/unit-test:FEAT-0018-br003: El workflow no baja los tests con -DskipTests")
    void br003_sinSkipTests() throws IOException {
        assertThat(leerWorkflow()).doesNotContain("skipTests");
    }

    @Test
    @DisplayName("BR-004+AC-003/unit-test:FEAT-0018-br004: El Maven wrapper está en el repo y el Quickstart no usa Maven global")
    void br004_wrapperYQuickstart() throws IOException {
        assertThat(RAIZ.resolve(Path.of("services", "mvnw"))).exists();
        assertThat(RAIZ.resolve(Path.of("services", "mvnw.cmd"))).exists();
        assertThat(RAIZ.resolve(Path.of("services", ".mvn", "wrapper", "maven-wrapper.properties"))).exists();

        String quickstart = seccionQuickstart(leerReadme());
        assertThat(quickstart)
                .contains("cd services")
                .contains("./mvnw test");
    }

    @Test
    @DisplayName("AC-004/assertion:FEAT-0018-ac004: El README no contiene rutas absolutas de Windows")
    void ac004_sinRutasAbsolutas() throws IOException {
        String readme = leerReadme();
        assertThat(readme)
                .doesNotContain("C:/Users")
                .doesNotContain("C:\\Users")
                .doesNotContain("C:/Program Files")
                .doesNotContain("C:\\Program Files");
    }

    @Test
    @DisplayName("AC-002+AC-007/assertion:FEAT-0018-ac002: Los badges de CI y de tests llevan a su fuente")
    void ac002BadgesConFuente() throws IOException {
        String readme = leerReadme();
        assertThat(readme).as("badge de CI apunta al workflow")
                .contains("actions/workflows/maven-build.yml")
                .contains("shields.io/github/actions/workflow/status/CharlyZeta/wr-sensor/maven-build.yml");
        assertThat(readme).as("badge de tests enlazado a su fuente")
                .contains("href=\"docs/REGISTRO-SDD.md\"");

        // AC-007: el número del badge tiene que coincidir con la suite documentada.
        String registro = leer(RAIZ.resolve(Path.of("docs", "REGISTRO-SDD.md")));
        Matcher documentado = Pattern.compile("Total:\\s*(\\d+)\\s*unit/assert\\s*\\+\\s*(\\d+)\\s*ITs\\s*=\\s*(\\d+)")
                .matcher(registro);
        assertThat(documentado.find()).as("REGISTRO-SDD.md declara el total de la suite").isTrue();
        int totalDocumentado = Integer.parseInt(documentado.group(1)) + Integer.parseInt(documentado.group(2));
        assertThat(totalDocumentado)
                .as("el total documentado coincide con la suma unit + IT")
                .isEqualTo(Integer.parseInt(documentado.group(3)));

        Matcher badge = Pattern.compile("shields\\.io/badge/tests-(\\d+)%20passed").matcher(readme);
        assertThat(badge.find()).as("el badge de tests publica un número").isTrue();
        assertThat(Integer.parseInt(badge.group(1)))
                .as("el número del badge coincide con la suite documentada")
                .isEqualTo(totalDocumentado);
    }
}
