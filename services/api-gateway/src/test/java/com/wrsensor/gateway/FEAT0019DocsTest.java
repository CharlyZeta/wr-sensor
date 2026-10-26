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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0019 — Verificación mecánica de credenciales dev, README bilingüe y registro de decisiones.
 *
 * <p>Cada test referencia su criterio del contrato. Si alguien vuelve a servir una credencial dev
 * real en la configuración, borra una de las dos versiones del README, deja las secciones
 * desalineadas o elimina la decisión revertida, esta suite se pone roja.</p>
 */
class FEAT0019DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();
    private static final String PLACEHOLDER = "localdev";

    private static String leer(Path absoluto) throws IOException {
        assertThat(absoluto).as("existe " + absoluto).exists();
        return Files.readString(absoluto, StandardCharsets.UTF_8);
    }

    private static String leer(String relativo) throws IOException {
        return leer(RAIZ.resolve(relativo));
    }

    private static List<Path> configuracionesDeServicios() throws IOException {
        try (Stream<Path> caminos = Files.walk(RAIZ.resolve("services"))) {
            return caminos
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("application.yml"))
                    .filter(p -> p.toString().contains("src" + java.io.File.separator + "main"))
                    .collect(Collectors.toList());
        }
    }

    private static List<String> seccionesNumeradas(String readme) {
        Matcher m = Pattern.compile("(?m)^## (\\d)\\. ").matcher(readme);
        List<String> numeros = new java.util.ArrayList<>();
        while (m.find()) {
            numeros.add(m.group(1));
        }
        return numeros;
    }

    private static int filasDeContratos(String readme) {
        Matcher m = Pattern.compile("(?m)^\\| `(?:FEAT|FIX)-\\d{4}`").matcher(readme);
        int filas = 0;
        while (m.find()) {
            filas++;
        }
        return filas;
    }

    /** Devuelve el cuerpo de una sección de nivel 2 (desde su encabezado hasta el siguiente `## `). */
    private static String seccion(String readme, String encabezado) {
        int inicio = readme.indexOf(encabezado);
        assertThat(inicio).as("existe la sección " + encabezado).isGreaterThan(-1);
        int siguiente = readme.indexOf("\n## ", inicio + encabezado.length());
        return siguiente < 0 ? readme.substring(inicio) : readme.substring(inicio, siguiente);
    }

    @Test
    @DisplayName("AC-001/assertion:FEAT-0019-ac001: ninguna configuración sirve credenciales dev reales")
    void ac001_sinCredencialesEnConfiguracion() throws IOException {
        String compose = leer("docker-compose.yml");
        assertThat(compose).as("docker-compose sin credenciales dev literales")
                .doesNotContain("Admin123!")
                .doesNotContain("Viewer123!");
        List<Path> configs = configuracionesDeServicios();
        assertThat(configs).as("hay application.yml de servicios para revisar").isNotEmpty();
        for (Path config : configs) {
            assertThat(leer(config)).as(config + " sin credenciales dev literales")
                    .doesNotContain("Admin123!")
                    .doesNotContain("Viewer123!");
        }
    }

    @Test
    @DisplayName("AC-002/assertion:FEAT-0019-ac002: .env.example documenta las variables de auth dev")
    void ac002_envExampleDocumentado() throws IOException {
        assertThat(leer(".env.example"))
                .contains("AUTH_JWT_SECRET")
                .contains("REGISTRY_AUTH_PASSWORD")
                .contains("QUERY_REGISTRY_PASS")
                .contains(PLACEHOLDER);
    }

    @Test
    @DisplayName("AC-003/assertion:FEAT-0019-ac003: hay exactamente dos README con link cruzado")
    void ac003_dosReadmesConLinkCruzado() throws IOException {
        try (Stream<Path> raiz = Files.list(RAIZ)) {
            List<String> readmes = raiz
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(nombre -> nombre.startsWith("README"))
                    .collect(Collectors.toList());
            assertThat(readmes).as("README.md y README.es.md en la raíz")
                    .containsExactlyInAnyOrder("README.md", "README.es.md");
        }
        String ingles = leer("README.md");
        String espanol = leer("README.es.md");
        assertThat(ingles).as("el README en inglés enlaza al español")
                .contains("[Leer en español](README.es.md)");
        assertThat(espanol).as("el README en español enlaza al inglés")
                .contains("[Read in English](README.md)");
        // El link cruzado va arriba: dentro de las primeras 10 líneas de cada archivo.
        assertThat(String.join("\n", ingles.lines().limit(10).collect(Collectors.toList())))
                .contains("README.es.md");
        assertThat(String.join("\n", espanol.lines().limit(10).collect(Collectors.toList())))
                .contains("README.md");
    }

    @Test
    @DisplayName("AC-004/assertion:FEAT-0019-ac004: las dos versiones del README son estructuralmente equivalentes")
    void ac004_estructuraEquivalente() throws IOException {
        String ingles = leer("README.md");
        String espanol = leer("README.es.md");

        assertThat(seccionesNumeradas(ingles))
                .as("secciones numeradas del README en inglés")
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(seccionesNumeradas(espanol))
                .as("las secciones numeradas del README en español coinciden")
                .isEqualTo(seccionesNumeradas(ingles));

        assertThat(filasDeContratos(ingles))
                .as("la tabla de work items del README en inglés lista los mismos contratos")
                .isEqualTo(filasDeContratos(espanol))
                .isEqualTo(28);

        assertThat(ingles.split("img.shields.io", -1).length)
                .as("la misma cantidad de badges en los dos idiomas")
                .isEqualTo(espanol.split("img.shields.io", -1).length);

        // Los totales no se fijan como snapshot: se exige que los dos README declaren el mismo
        // número que la fuente de verdad (REGISTRO-SDD) y que ambos coincidan entre sí.
        String documentado = primerGrupo(leer("docs/REGISTRO-SDD.md"),
                "Total: \\d+ unit/assert \\+ \\d+ ITs = (\\d+) verdes", "REGISTRO-SDD sin total");
        String badgeEn = primerGrupo(ingles, "badge/tests-(\\d+)%20passed", "badge de tests en inglés");
        String badgeEs = primerGrupo(espanol, "badge/tests-(\\d+)%20passed", "badge de tests en español");
        assertThat(badgeEn).as("el badge coincide en los dos idiomas y con el registro")
                .isEqualTo(badgeEs)
                .isEqualTo(documentado);
        String verdesEn = primerGrupo(ingles, "—\\s*\\*\\*(\\d+) passing\\*\\*", "total en inglés");
        String verdesEs = primerGrupo(espanol, "—\\s*\\*\\*(\\d+) verdes\\*\\*", "total en español");
        assertThat(verdesEn).as("el total con SPA coincide en los dos idiomas").isEqualTo(verdesEs);
        assertThat(Integer.parseInt(verdesEn))
                .as("el total con SPA incluye al menos los tests del backend")
                .isGreaterThanOrEqualTo(Integer.parseInt(documentado));
        for (String readme : List.of(ingles, espanol)) {
            assertThat(readme).as("diagramas sin traducir").contains("sensor.lecturas", "sensor.alertas");
        }
    }

    private static String primerGrupo(String texto, String patron, String mensaje) {
        Matcher m = Pattern.compile(patron).matcher(texto);
        assertThat(m.find()).as(mensaje).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("AC-005/assertion:FEAT-0019-ac005: el README en español documenta decisiones con costo y una revertida")
    void ac005_decisionesEnEspanol() throws IOException {
        String espanol = leer("README.es.md");
        String decisiones = seccion(espanol, "## Decisiones y trade-offs");
        assertThat(decisiones.lines().filter(l -> l.startsWith("### ")).count())
                .as("3 decisiones documentadas").isEqualTo(3);
        assertThat(decisiones)
                .as("la decisión revertida declara sus cuatro partes")
                .contains("**Primero hice:**")
                .contains("**Se rompió por:**")
                .contains("**Migramos a:**")
                .contains("**Costo del cambio:**");
        assertThat(decisiones).as("cada decisión declara su costo").contains("**Costo:**");
    }

    @Test
    @DisplayName("AC-006/assertion:FEAT-0019-ac006: el README en inglés documenta las mismas decisiones")
    void ac006_decisionesEnIngles() throws IOException {
        String ingles = leer("README.md");
        String decisiones = seccion(ingles, "## Decisions & trade-offs");
        assertThat(decisiones.lines().filter(l -> l.startsWith("### ")).count())
                .as("las mismas 3 decisiones").isEqualTo(3);
        assertThat(decisiones)
                .as("la decisión revertida en inglés declara sus cuatro partes")
                .contains("**What I did first:**")
                .contains("**Why it broke:**")
                .contains("**What we migrated to:**")
                .contains("**Cost of the change:**");
        assertThat(decisiones).as("cada decisión declara su costo").contains("**Cost:**");
    }

    @Test
    @DisplayName("AC-007/assertion:FEAT-0019-ac007: seed, compose y defaults comparten el placeholder dev")
    void ac007_placeholderCoherente() throws IOException {
        assertThat(leer("services/sensor-registry/src/main/java/com/wrsensor/sensorregistry/infrastructure/seed/DevUserSeeder.java"))
                .as("el seed usa el placeholder")
                .contains("DEV_PASSWORD_ADMIN = \"" + PLACEHOLDER + "\"")
                .contains("DEV_PASSWORD_VIEWER = \"" + PLACEHOLDER + "\"");
        assertThat(leer("docker-compose.yml"))
                .as("docker-compose pasa el placeholder")
                .contains("REGISTRY_AUTH_PASSWORD: ${REGISTRY_AUTH_PASSWORD:-" + PLACEHOLDER + "}");
        assertThat(leer("services/ingestion-service/src/main/resources/application.yml"))
                .as("ingestion default")
                .contains("${REGISTRY_AUTH_PASSWORD:" + PLACEHOLDER + "}");
        assertThat(leer("services/query-api/src/main/resources/application.yml"))
                .as("query-api default")
                .contains("${QUERY_REGISTRY_PASS:" + PLACEHOLDER + "}");
    }
}
