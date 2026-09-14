package com.wrsensor.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0007 — BR-011 / AC-012: la resiliencia del lookup de config queda documentada
 * (configuración, endpoint, motivo de DLQ y deuda de reintentos).
 */
class FIX0007DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("AC-012 / BR-011: RUNBOOK explica configuración, endpoint y qué hacer con el circuito abierto")
    void ac012_runbook() throws IOException {
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook).contains("Resiliencia del lookup de config (FIX-0007)")
                .contains("INGESTION_REGISTRY_TIMEOUT")
                .contains("INGESTION_REGISTRY_CACHE_TTL")
                .contains("INGESTION_CIRCUITO_FALLOS")
                .contains("INGESTION_CIRCUITO_SEGUNDOS_ABIERTO")
                .contains("INGESTION_CIRCUITO_EXITOS_CERRAR")
                .contains("/api/ingestion/resiliencia")
                .contains("REGISTRY_UNAVAILABLE")
                .contains("retry-max-attempts")
                .contains("no se usa");
    }

    @Test
    @DisplayName("AC-012 / BR-011: API.md lista REGISTRY_UNAVAILABLE y ARQUITECTURA describe la política")
    void ac012_apiYArquitectura() throws IOException {
        assertThat(leer("docs/API.md")).contains("REGISTRY_UNAVAILABLE");

        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("resiliencia del lookup")
                .contains("circuit breaker propio")
                .contains("last-known-good")
                .contains("REGISTRY_UNAVAILABLE")
                .contains("GET /api/ingestion/resiliencia");
    }

    @Test
    @DisplayName("AC-012 / BR-011: la decisión queda como ADR-0018 y la configuración es por entorno")
    void ac012_adrYConfiguracion() throws IOException {
        String adr = leer("docs/DECISIONES.md");
        assertThat(adr).contains("ADR-0018")
                .contains("FIX-0007")
                .contains("circuit breaker propio")
                .contains("last-known-good")
                .contains("REGISTRY_UNAVAILABLE");

        String yml = leer("services/ingestion-service/src/main/resources/application.yml");
        assertThat(yml).contains("timeout-ms: ${INGESTION_REGISTRY_TIMEOUT:2000}")
                .contains("conexion-timeout-ms: ${INGESTION_REGISTRY_CONEXION_TIMEOUT:1000}")
                .contains("ttl-segundos: ${INGESTION_REGISTRY_CACHE_TTL:300}")
                .contains("fallos-para-abrir: ${INGESTION_CIRCUITO_FALLOS:5}")
                .contains("segundos-abierto: ${INGESTION_CIRCUITO_SEGUNDOS_ABIERTO:30}")
                .contains("exitos-para-cerrar: ${INGESTION_CIRCUITO_EXITOS_CERRAR:2}");
    }
}
