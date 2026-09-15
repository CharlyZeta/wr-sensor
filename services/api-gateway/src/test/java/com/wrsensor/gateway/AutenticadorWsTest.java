package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.VerificadorJwt;
import com.wrsensor.gateway.infrastructure.adapter.in.web.AutenticadorWs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0008 BR-003/BR-004 en el autorizador del handshake: de dónde sale el token, qué se
 * rechaza con qué código y cómo se limpia el query antes de reenviarlo al downstream.
 */
class AutenticadorWsTest {

    private static final List<String> ROLES = List.of("ADMIN", "VIEWER");

    private static AutenticadorWs autenticador() {
        return new AutenticadorWs(new VerificadorJwt(GatewayTestTokens.SECRET), ROLES, "token",
                java.time.Clock.systemUTC());
    }

    private static ServerWebExchange upgrade(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade"));
    }

    private static ServerWebExchange upgradeConQuery(String path, String query) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path + "?" + query)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade"));
    }

    // ===== AF-02: sin token =====

    @Test
    @DisplayName("AF-02/AC-004: sin token → 401 UNAUTHENTICATED (no hay handshake)")
    void af02_sinToken() {
        AutenticadorWs.Rechazo r = autenticador().autorizar(upgrade("/ws/alertas")).orElseThrow();
        assertThat(r.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.codigo()).isEqualTo(CodigosError.UNAUTHENTICATED);
        assertThat(r.mensaje()).doesNotContain("null");
    }

    // ===== AF-03: token inválido =====

    @Test
    @DisplayName("AF-03/AC-005: token expirado o con firma inválida → 401")
    void af03_tokenInvalido() {
        AutenticadorWs auth = autenticador();
        assertThat(auth.autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.expirado("ADMIN"))).orElseThrow().codigo())
                .isEqualTo(CodigosError.UNAUTHENTICATED);
        assertThat(auth.autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.firmaInvalida("ADMIN"))).orElseThrow().status())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auth.autorizar(upgradeConQuery("/ws/alertas", "token=basura")).orElseThrow()
                .codigo()).isEqualTo(CodigosError.UNAUTHENTICATED);
    }

    // ===== AF-04 / AC-006 / AC-007: token válido =====

    @Test
    @DisplayName("AF-04/AC-006: VIEWER por query string autoriza los WS (no exigen ADMIN)")
    void af04_viewerPorQuery() {
        assertThat(autenticador().autorizar(upgradeConQuery("/ws/sensores/abc",
                "token=" + GatewayTestTokens.vigente("VIEWER")))).isEmpty();
        assertThat(autenticador().autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.vigente("ADMIN")))).isEmpty();
    }

    @Test
    @DisplayName("AC-007: Authorization: Bearer se comporta igual que ?token=")
    void ac007_bearer() {
        AutenticadorWs auth = autenticador();
        String token = GatewayTestTokens.vigente("VIEWER");
        ServerWebExchange conHeader = MockServerWebExchange.from(MockServerHttpRequest
                .get("/ws/alertas")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade"));
        assertThat(auth.autorizar(conHeader)).isEmpty();
        assertThat(auth.autorizar(upgradeConQuery("/ws/alertas", "token=" + token))).isEmpty();
        assertThat(auth.tokenDe(conHeader)).contains(token);
    }

    @Test
    @DisplayName("BR-003: rol fuera de la lista permitida → 403 INSUFFICIENT_ROLE")
    void br003_rolNoAutorizado() {
        AutenticadorWs.Rechazo r = autenticador().autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.vigente("AUDITOR"))).orElseThrow();
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.codigo()).isEqualTo(CodigosError.INSUFFICIENT_ROLE);
    }

    @Test
    @DisplayName("BR-003: roles permitidos configurables (lista propia del despliegue)")
    void br003_rolesConfigurables() {
        AutenticadorWs soloViewer = new AutenticadorWs(new VerificadorJwt(GatewayTestTokens.SECRET),
                List.of("VIEWER"), "token", java.time.Clock.systemUTC());
        assertThat(soloViewer.autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.vigente("ADMIN"))).orElseThrow().status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(soloViewer.autorizar(upgradeConQuery("/ws/alertas",
                "token=" + GatewayTestTokens.vigente("VIEWER")))).isEmpty();
    }

    @Test
    @DisplayName("BR-003: el nombre del query param del token es configurable")
    void br003_parametroConfigurable() {
        AutenticadorWs otro = new AutenticadorWs(new VerificadorJwt(GatewayTestTokens.SECRET), ROLES,
                "access_token", java.time.Clock.systemUTC());
        String token = GatewayTestTokens.vigente("VIEWER");
        assertThat(otro.autorizar(upgradeConQuery("/ws/alertas", "access_token=" + token))).isEmpty();
        assertThat(otro.autorizar(upgradeConQuery("/ws/alertas", "token=" + token)))
                .as("el parametro default ya no aplica").isPresent();
    }

    // ===== BR-004: el token no se propaga =====

    @Test
    @DisplayName("BR-004/AC-008: el token se quita del query y el resto de parámetros se preserva")
    void br004_querySinToken() {
        AutenticadorWs auth = autenticador();
        assertThat(auth.querySinToken("token=abc.def.ghi")).isNull();
        assertThat(auth.querySinToken("token=abc&sensor=7")).isEqualTo("sensor=7");
        assertThat(auth.querySinToken("sensor=7&token=abc")).isEqualTo("sensor=7");
        assertThat(auth.querySinToken("sensor=7&token=abc&otro=1")).isEqualTo("sensor=7&otro=1");
        assertThat(auth.querySinToken(null)).isNull();
        assertThat(auth.querySinToken("")).isNull();
        assertThat(auth.querySinToken("sensor=7")).as("sin token, el query queda intacto")
                .isEqualTo("sensor=7");
        assertThat(auth.querySinToken("tokenX=abc")).as("no confunde prefijos")
                .isEqualTo("tokenX=abc");
    }
}
