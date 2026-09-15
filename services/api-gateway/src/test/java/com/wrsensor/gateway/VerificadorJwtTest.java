package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.VerificadorJwt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit — FEAT-0008 BR-003: verificador HS256 del handshake WebSocket (firma + {@code exp}).
 * Cubre la mitad "token" de AF-02/AF-03 y la aceptación de AF-04/AC-007.
 */
class VerificadorJwtTest {

    private final VerificadorJwt verificador = new VerificadorJwt(GatewayTestTokens.SECRET);

    private static long ahora() {
        return Instant.now().getEpochSecond();
    }

    @Test
    @DisplayName("BR-003: un token vigente del emisor real devuelve sujeto, rol y expiración")
    void br003_tokenVigente() {
        VerificadorJwt.Claims claims = verificador
                .verificar(GatewayTestTokens.vigente("VIEWER"), ahora()).orElseThrow();
        assertThat(claims.rol()).isEqualTo("VIEWER");
        assertThat(claims.sujeto()).hasSize(36);
        assertThat(claims.expiraEnSegundos()).isGreaterThan(ahora());
        assertThat(claims.expirado(ahora())).isFalse();
    }

    @Test
    @DisplayName("AF-03: token expirado → vacío (no autoriza)")
    void af03_expirado() {
        assertThat(verificador.verificar(GatewayTestTokens.expirado("ADMIN"), ahora())).isEmpty();
    }

    @Test
    @DisplayName("AF-03: firma con otro secreto → vacío")
    void af03_firmaInvalida() {
        assertThat(verificador.verificar(GatewayTestTokens.firmaInvalida("ADMIN"), ahora()))
                .isEmpty();
    }

    @Test
    @DisplayName("AF-03: payload alterado después de firmar → vacío")
    void af03_payloadAlterado() {
        String alterado = GatewayTestTokens.alterado(GatewayTestTokens.vigente("VIEWER"));
        assertThat(verificador.verificar(alterado, ahora())).isEmpty();
    }

    @Test
    @DisplayName("AF-02: token ausente, vacío o malformado → vacío, sin excepción")
    void af02_ausenteOMalformado() {
        assertThat(verificador.verificar(null, ahora())).isEmpty();
        assertThat(verificador.verificar("   ", ahora())).isEmpty();
        assertThat(verificador.verificar("no-es-un-jwt", ahora())).isEmpty();
        assertThat(verificador.verificar("a.b", ahora())).isEmpty();
        assertThat(verificador.verificar("a.b.c.d", ahora())).isEmpty();
        assertThat(verificador.verificar("a.!!!no-base64!!!.c", ahora())).isEmpty();
    }

    @Test
    @DisplayName("BR-003: firma válida pero sin claim sub → vacío (formato ajeno al emisor)")
    void br003_sinSujeto() {
        assertThat(verificador.verificar(GatewayTestTokens.sinSujeto("ADMIN"), ahora())).isEmpty();
    }

    @Test
    @DisplayName("BR-003: sin secreto configurado el gateway falla al arrancar, no autoriza a ciegas")
    void br003_sinSecreto() {
        assertThatThrownBy(() -> new VerificadorJwt("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-secreto");
        assertThatThrownBy(() -> new VerificadorJwt(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
