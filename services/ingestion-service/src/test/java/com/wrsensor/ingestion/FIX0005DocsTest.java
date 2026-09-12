package com.wrsensor.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0005 — BR-010 / AC-008: la topología particionada y su operación quedan documentadas
 * en el repositorio (arquitectura + runbook), no sólo en el Contract.
 */
class FIX0005DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String doc(String nombre) throws IOException {
        Path p = RAIZ.resolve("docs").resolve(nombre);
        assertThat(p).as("existe docs/" + nombre).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-008 / BR-010: ARQUITECTURA.md documenta el particionamiento por sensorId")
    void ac008_arquitectura() throws IOException {
        String arq = doc("ARQUITECTURA.md");
        assertThat(arq).contains("FIX-0005")
                .contains("sensor.lecturas.part")
                .contains("x-consistent-hash")
                .contains("queue.sensor.lecturas.p0")
                .contains("INGESTION_PARTICIONES_TOTAL")
                .contains("INGESTION_PARTICIONES_ASIGNADAS")
                .contains("afinidad sensor")
                .contains("El publisher (`data-simulator`) **no** cambia")
                .contains("un consumer por partición");
    }

    @Test
    @DisplayName("AC-008 / BR-010: RUNBOOK.md documenta escalado, asignación por instancia, "
            + "cambio de total y drenaje de la cola anterior")
    void ac008_runbook() throws IOException {
        String runbook = doc("RUNBOOK.md");
        assertThat(runbook).contains("Escalado horizontal de `ingestion-service`")
                .contains("INGESTION_PARTICIONES_ASIGNADAS")
                .contains("reinicio coordinado")
                .contains("drenar")
                .contains("queue.sensor.lecturas")
                .contains("rabbitmq_consistent_hash_exchange");
    }

    @Test
    @DisplayName("AC-008 / BR-010: la configuración de particiones vive en application.yml "
            + "con defaults documentados (nunca hardcodeada)")
    void ac008_configuracion() throws IOException {
        Path yml = RAIZ.resolve(Path.of("services", "ingestion-service", "src", "main",
                "resources", "application.yml"));
        assertThat(yml).exists();
        String cfg = Files.readString(yml, StandardCharsets.UTF_8);
        assertThat(cfg).contains("particiones:")
                .contains("INGESTION_PARTICIONES_TOTAL:4")
                .contains("sensor.lecturas.part")
                .contains("queue.sensor.lecturas.p{i}");

        Path enabled = RAIZ.resolve(Path.of("infra", "rabbitmq", "enabled_plugins"));
        assertThat(enabled).as("plugin del exchange type habilitado en el broker").exists();
        assertThat(Files.readString(enabled, StandardCharsets.UTF_8))
                .contains("rabbitmq_consistent_hash_exchange");
    }

    @Test
    @DisplayName("AC-008 / BR-010: docker-compose monta los plugins y define las particiones")
    void ac008_compose() throws IOException {
        List<String> compose = Files.readAllLines(RAIZ.resolve("docker-compose.yml"),
                StandardCharsets.UTF_8);
        String texto = String.join("\n", compose);
        assertThat(texto).contains("infra/rabbitmq/enabled_plugins")
                .contains("INGESTION_PARTICIONES_TOTAL");
    }
}
