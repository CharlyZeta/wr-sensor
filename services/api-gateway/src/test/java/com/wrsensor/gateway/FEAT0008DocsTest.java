package com.wrsensor.gateway;

import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0008 — BR-001/BR-003 (todo configurable, sin valores hardcodeados), BR-008 (el resumen entra
 * como ruta declarada con clase `lectura`), BR-009/BR-010 (decisiones registradas: SPA servido por el
 * gateway, simulador fuera) y BR-011/AC-013 (la documentación refleja CORS, auth de WS y el resumen).
 *
 * <p>Sigue el patrón de `FIX0007DocsTest`: los criterios de "documentado y configurable" se verifican
 * mecánicamente sobre los archivos, no por inspección manual.</p>
 */
class FEAT0008DocsTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String ymlGateway() throws IOException {
        return leer("services/api-gateway/src/main/resources/application.yml");
    }

    // ===== BR-001 / BR-003: configuración con overrides por entorno =====

    @Test
    @DisplayName("BR-001/BR-003: el gateway declara CORS y WS configurables por entorno (nada hardcodeado)")
    void br001_br003_configuracionEnYml() throws IOException {
        String yml = ymlGateway();
        assertThat(yml)
                .as("CORS: lista de orígenes por entorno, vacía por default (same-origin only)")
                .contains("GATEWAY_CORS_ORIGENES").contains("max-age-segundos").contains("GATEWAY_CORS_MAX_AGE")
                .contains("permitir-credenciales: false")
                .contains("Authorization, Content-Type, X-Correlation-Id")
                .contains("X-Correlation-Id, X-RateLimit-Limit, X-RateLimit-Remaining, Retry-After");
        assertThat(yml)
                .as("WS: roles, nombre del parámetro del token y secreto compartido")
                .contains("roles-permitidos: [ADMIN, VIEWER]")
                .contains("parametro-token: token")
                .contains("AUTH_JWT_SECRET");
    }

    @Test
    @DisplayName("BR-001: sin configurar, CORS queda deshabilitado; configurado, habilita el origen")
    void br001_bindingDeOrigenes() {
        assertThat(bind(Map.of()).cors().origenesOrDefault())
                .as("default de producción: same-origin only").isEmpty();
        assertThat(bind(Map.of("gateway.cors.origenes", "")).cors().origenesOrDefault())
                .as("una variable de entorno vacía tampoco habilita orígenes").isEmpty();
        assertThat(bind(Map.of("gateway.cors.origenes", "http://localhost:5173,https://dash.local"))
                .cors().origenesOrDefault())
                .containsExactly("http://localhost:5173", "https://dash.local");
    }

    @Test
    @DisplayName("BR-003: el nombre del parámetro del token y los roles son configurables")
    void br003_bindingDeWs() {
        GatewayProperties base = bind(Map.of());
        assertThat(base.ws().rolesPermitidosOrDefault()).containsExactly("ADMIN", "VIEWER");
        assertThat(base.ws().parametroTokenOrDefault()).isEqualTo("token");
        GatewayProperties cambiado = bind(Map.of(
                "gateway.ws.roles-permitidos", "VIEWER",
                "gateway.ws.parametro-token", "access_token"));
        assertThat(cambiado.ws().rolesPermitidosOrDefault()).containsExactly("VIEWER");
        assertThat(cambiado.ws().parametroTokenOrDefault()).isEqualTo("access_token");
    }

    private static GatewayProperties bind(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>(overrides);
        // un registro anidado con TODOS sus campos sin definir no se instancia (queda null), así que
        // se siembra una propiedad inocua bajo cada prefijo para poder afirmar los defaults reales.
        props.put("gateway.cors.max-age-segundos", "3600");
        props.put("gateway.ws.jwt-secreto", "secreto-de-prueba");
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("gateway", GatewayProperties.class)
                .orElseThrow(() -> new IllegalStateException("no bindeo gateway"));
    }

    // ===== BR-008: el resumen es una ruta declarada con clase lectura =====

    @Test
    @DisplayName("BR-008: /api/sensores/resumen está declarada como ruta a query-api con clase lectura")
    void br008_rutaDeclarada() throws IOException {
        String yml = ymlGateway();
        assertThat(yml).contains("id: query-resumen")
                .contains("patron: /api/sensores/resumen")
                .contains("QUERY_API_URL");
        // la ruta del resumen declara la clase lectura (no la default) y no cambia las existentes
        String bloque = yml.substring(yml.indexOf("id: query-resumen"));
        assertThat(bloque.substring(0, bloque.indexOf("- id: registry-sensores-lectura")))
                .contains("clase-limite: lectura");
        assertThat(yml).contains("id: query-lecturas").contains("id: query-actual");
    }

    // ===== BR-009 / BR-010 / AC-013 =====

    @Test
    @DisplayName("BR-010/AC-013: el simulador sigue sin ruta en el gateway (decisión registrada)")
    void br010_simuladorFuera() throws IOException {
        assertThat(ymlGateway()).as("ninguna ruta del gateway apunta al simulador")
                .doesNotContain("/api/simulador").doesNotContain("data-simulator")
                .doesNotContain("8081");
    }

    @Test
    @DisplayName("BR-009/AC-013: la decisión de servir el SPA desde el gateway queda registrada para FEAT-0009")
    void br009_decisionDelSpa() throws IOException {
        assertThat(leer("contracts/FEAT-0008.md"))
                .contains("BR-009").contains("FEAT-0009").contains("El SPA se sirve desde el gateway");
        assertThat(leer("docs/ARQUITECTURA.md")).contains("FEAT-0009");
        assertThat(leer("docs/CONTINUIDAD.md")).contains("mapa Leaflet");
    }

    // ===== BR-011 / AC-013: documentación =====

    @Test
    @DisplayName("BR-011/AC-013: el RUNBOOK explica CORS por entorno, WS a mano y el resumen")
    void br011_runbook() throws IOException {
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook)
                .contains("GATEWAY_CORS_ORIGENES")
                .contains("websocat")
                .contains("?token=")
                .contains("/api/sensores/resumen")
                .contains("QUERY_REGISTRY_TIMEOUT_MS")
                .contains("same-origin");
    }

    @Test
    @DisplayName("BR-011/AC-013: API.md documenta el endpoint nuevo, el 401 del upgrade y los códigos nuevos")
    void br011_api() throws IOException {
        String api = leer("docs/API.md");
        assertThat(api).contains("GET` | `/api/sensores/resumen")
                .contains("UNAUTHENTICATED")
                .contains("INSUFFICIENT_ROLE")
                .contains("ORIGIN_NOT_ALLOWED")
                .contains("REGISTRY_UNAVAILABLE")
                .contains("CORS (FEAT-0008)")
                .contains("WebSocket autenticado (FEAT-0008)");
    }

    @Test
    @DisplayName("BR-011/AC-013: ARQUITECTURA describe el flujo de auth del WS y DECISIONES tiene el ADR-0019")
    void br011_arquitecturaYAdr() throws IOException {
        String arq = leer("docs/ARQUITECTURA.md");
        assertThat(arq).contains("Autenticación del handshake WebSocket (FEAT-0008)")
                .contains("AutenticadorWs")
                .contains("VerificadorJwt")
                .contains("Cadena de filtros del gateway")
                .contains("query-resumen");
        String adr = leer("docs/DECISIONES.md");
        assertThat(adr).contains("## ADR-0019").contains("same-origin no es CORS")
                .contains("FEAT-0008");
    }

    @Test
    @DisplayName("BR-011: el estado del work item quedó registrado en el tablero y el registro SDD")
    void br011_estadoYRegistro() throws IOException {
        assertThat(leer("contracts/FEAT-0008.md"))
                .as("el contract cierra con el mapa completo")
                .contains("Status: RESOLVED").doesNotContain("|❌");
        assertThat(leer("docs/ESTADO-SDD.md")).contains("FEAT-0008").contains("408/408");
        assertThat(leer("docs/REGISTRO-SDD.md")).contains("FEAT-0008").contains("403 verdes");
        assertThat(leer("docs/CHANGELOG.md")).contains("[FEAT-0008]");
    }
}
