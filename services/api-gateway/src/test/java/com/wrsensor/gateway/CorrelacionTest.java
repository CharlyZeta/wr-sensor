package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.Correlacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0007 — correlación y códigos de error (unit tests BR-008, BR-009 y AF-04).
 */
class CorrelacionTest {

    // ===== AF-04: validez del header del cliente =====

    @Test
    @DisplayName("AF-04: valor vacío, demasiado largo o no imprimible se descarta")
    void af04_valoresInvalidos() {
        assertThat(Correlacion.valido(null)).isEmpty();
        assertThat(Correlacion.valido("")).isEmpty();
        assertThat(Correlacion.valido("   ")).isEmpty();
        assertThat(Correlacion.valido("a".repeat(Correlacion.MAX_LARGO + 1))).isEmpty();
        assertThat(Correlacion.valido("abc\n123")).isEmpty();
        assertThat(Correlacion.valido("abc\u0000def")).isEmpty();
        assertThat(Correlacion.valido("abc\u00e9def")).isEmpty();
    }

    @Test
    @DisplayName("AF-04: valor válido se propaga; el inválido se reemplaza por uno generado")
    void af04_resolucion() {
        assertThat(Correlacion.resolver("abc-123_X.Y")).isEqualTo("abc-123_X.Y");
        assertThat(Correlacion.valido("x".repeat(Correlacion.MAX_LARGO))).isPresent();

        String generado = Correlacion.resolver("con espacio");
        assertThat(generado).as("no se propaga el valor inválido").isNotEqualTo("con espacio");
        assertThat(Correlacion.valido(generado)).as("se generó un id válido").isPresent();

        String otro = Correlacion.resolver(null);
        assertThat(otro).isNotBlank().isNotEqualTo(generado);
    }

    // ===== BR-008: nombre de header estable =====

    @Test
    @DisplayName("BR-008: el header de correlación es X-Correlation-Id")
    void br008_header() {
        assertThat(Correlacion.HEADER).isEqualTo("X-Correlation-Id");
        assertThat(Correlacion.nuevo()).hasSize(36);
    }

    // ===== BR-009: códigos de error de dominio =====

    @Test
    @DisplayName("BR-009: los códigos del gateway son los del contrato y el JSON escapa texto")
    void br009_codigosYJson() {
        assertThat(CodigosError.ROUTE_NOT_FOUND).isEqualTo("ROUTE_NOT_FOUND");
        assertThat(CodigosError.RATE_LIMIT_EXCEEDED).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(CodigosError.UPSTREAM_UNAVAILABLE).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(CodigosError.UPSTREAM_TIMEOUT).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(CodigosError.WS_UPGRADE_REQUIRED).isEqualTo("WS_UPGRADE_REQUIRED");

        assertThat(CodigosError.json(CodigosError.RATE_LIMIT_EXCEEDED, "cupo \"x\"\nfin"))
                .isEqualTo("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"cupo \\\"x\\\"\\nfin\"}");
        assertThat(CodigosError.json("A", null)).isEqualTo("{\"code\":\"A\",\"message\":\"\"}");
    }
}
