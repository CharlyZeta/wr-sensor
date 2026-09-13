package com.wrsensor.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0006 — BR-012 / AC-013: el payload v1, la política de versiones y la convivencia con el
 * payload legado quedan documentadas en el repositorio, no sólo en el Contract.
 */
class FIX0006DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-013 / BR-012: ARQUITECTURA.md describe el payload v1 y su política de evolución")
    void ac013_arquitectura() throws IOException {
        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("Payload v1 de `sensor.lecturas` (FIX-0006)")
                .contains("\"schemaVersion\": \"1.0\"")
                .contains("\"eventId\"")
                .contains("\"sequence\"")
                .contains("\"codigosAnomalias\"")
                .contains("Política de evolución y versiones (FIX-0006)")
                .contains("tolerancia hacia adelante")
                .contains("SCHEMA_UNSUPPORTED")
                .contains("legado `0.0`")
                .contains("query-api` (FEAT-0013)")
                .contains("DTO +\nJackson");
    }

    @Test
    @DisplayName("AC-013 / BR-012: API.md lista el motivo SCHEMA_UNSUPPORTED y RUNBOOK explica la convivencia")
    void ac013_apiYRunbook() throws IOException {
        assertThat(leer("docs/API.md")).contains("SCHEMA_UNSUPPORTED");

        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook).contains("Schema del evento `sensor.lecturas` (FIX-0006)")
                .contains("SIMULADOR_SCHEMA_VERSION")
                .contains("INGESTION_SCHEMA_VERSION")
                .contains("INGESTION_SCHEMA_TOLERAR_MAYORES")
                .contains("evento legado")
                .contains("hueco de secuencia");
    }

    @Test
    @DisplayName("BR-011 / BR-012: la versión y la tolerancia son configurables y están en los yml")
    void br011_configuracion() throws IOException {
        String simulador = leer("services/data-simulator/src/main/resources/application.yml");
        assertThat(simulador).contains("schema-version: ${SIMULADOR_SCHEMA_VERSION:1.0}");

        String ingestion = leer("services/ingestion-service/src/main/resources/application.yml");
        assertThat(ingestion).contains("schema:")
                .contains("version-soportada: ${INGESTION_SCHEMA_VERSION:1.0}")
                .contains("tolerar-versiones-mayores: ${INGESTION_SCHEMA_TOLERAR_MAYORES:true}");
    }

    @Test
    @DisplayName("AC-013: la decisión queda registrada como ADR")
    void ac013_adr() throws IOException {
        String adr = leer("docs/DECISIONES.md");
        assertThat(adr).contains("ADR-0017")
                .contains("FIX-0006")
                .contains("tolerancia hacia adelante")
                .contains("Jackson");
    }
}
