package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.infrastructure.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-0007 — configuración y topología (unit tests AC-001 topología, AC-011 configurabilidad,
 * BR-004 defaults documentados y BR-012 exposición).
 */
class ConfiguracionGatewayTest {

    private static final Path RAIZ = Path.of("..", "..").toAbsolutePath().normalize();

    private static String leer(String relativo) throws IOException {
        Path p = RAIZ.resolve(relativo);
        assertThat(p).as("existe " + relativo).exists();
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ===== BR-012 / AC-001: sólo el gateway publica puertos =====

    /** Extrae el bloque YAML de un servicio (hasta el próximo servicio de primer nivel). */
    private static String bloqueServicio(String compose, String servicio) {
        String[] lineas = compose.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean dentro = false;
        for (String linea : lineas) {
            if (linea.startsWith("  " + servicio + ":")) {
                dentro = true;
                continue;
            }
            if (dentro && linea.matches("^  [A-Za-z0-9_.-]+:.*$") && !linea.startsWith("    ")) {
                break;
            }
            if (dentro) {
                sb.append(linea).append('\n');
            }
        }
        assertThat(sb.length()).as("bloque encontrado para " + servicio).isGreaterThan(0);
        return sb.toString();
    }

    @Test
    @DisplayName("AC-001 / BR-012: docker-compose sólo publica el puerto del gateway entre los servicios")
    void ac001_topologiaDePuertos() throws IOException {
        String compose = leer("docker-compose.yml");
        for (String servicio : List.of("sensor-registry", "data-simulator", "ingestion-service",
                "alerting-service", "query-api")) {
            assertThat(bloqueServicio(compose, servicio).contains("ports:"))
                    .as(servicio + " no debe publicar puertos al host (FEAT-0007 BR-012)").isFalse();
        }
        assertThat(bloqueServicio(compose, "api-gateway"))
                .as("el gateway es el único punto de entrada publicado")
                .contains("ports: [\"8084:8084\"]");
        assertThat(compose).as("los puertos de infraestructura siguen publicados (dev)")
                .contains("5432:5432").contains("5672:5672");
    }

    @Test
    @DisplayName("AC-001 / BR-012: docker-compose.dev.yml republica los puertos para debug")
    void ac001_overrideDeDesarrollo() throws IOException {
        String dev = leer("docker-compose.dev.yml");
        assertThat(dev).contains("sensor-registry:").contains("8080:8080")
                .contains("data-simulator:").contains("8081:8081")
                .contains("query-api:").contains("8082:8082")
                .contains("alerting-service:").contains("8083:8083")
                .contains("docker-compose.dev.yml");
        String runbook = leer("docs/RUNBOOK.md");
        assertThat(runbook).as("el override queda documentado").contains("docker-compose.dev.yml");
    }

    // ===== BR-004 / AC-011: defaults y configurabilidad =====

    @Test
    @DisplayName("BR-004: application.yml define las 4 clases con los defaults moderados del contrato")
    void br004_defaultsDocumentados() throws IOException {
        String yml = leer("services/api-gateway/src/main/resources/application.yml");
        assertThat(yml).contains("default: { peticiones: 300, ventana-segundos: 60, burst: 100 }")
                .contains("login:   { peticiones: 10,  ventana-segundos: 60, burst: 10 }")
                .contains("lectura: { peticiones: 120, ventana-segundos: 60, burst: 60 }")
                .contains("ws:      { peticiones: 30,  ventana-segundos: 60, burst: 30 }")
                .contains("GATEWAY_CONFIAR_XFF:false")
                .contains("GATEWAY_RL_EXPIRACION:300");
    }

    @Test
    @DisplayName("BR-004: application.yml declara las rutas del contrato (WS incluidas)")
    void br004_rutasDeclaradas() throws IOException {
        String yml = leer("services/api-gateway/src/main/resources/application.yml");
        assertThat(yml).contains("/api/auth/login").contains("/api/sensores/**")
                .contains("/api/sensores/*/lecturas").contains("/api/sensores/*/actual")
                .contains("/ws/alertas").contains("/ws/sensores/**")
                .contains("clase-limite: login").contains("clase-limite: ws");
    }

    @Test
    @DisplayName("AC-011: los límites se overridean por configuración sin recompilar (mismo artefacto)")
    void ac011_configurable() {
        GatewayProperties base = binder(Map.of()).bind("gateway", GatewayProperties.class)
                .orElseThrow(() -> new IllegalStateException("no bindeo gateway"));
        assertThat(base.rateLimit().clases().get("login").peticiones()).isEqualTo(10);

        GatewayProperties cambiado = binder(Map.of(
                "gateway.rate-limit.clases.login.peticiones", "3",
                "gateway.rate-limit.clases.login.burst", "3",
                "gateway.rate-limit.confiar-forwarded-for", "true"))
                .bind("gateway", GatewayProperties.class)
                .orElseThrow(() -> new IllegalStateException("no bindeo gateway"));

        assertThat(cambiado.rateLimit().clases().get("login").peticiones()).isEqualTo(3);
        assertThat(cambiado.rateLimit().clases().get("login").burst()).isEqualTo(3);
        assertThat(cambiado.rateLimit().confiarForwardedForOrDefault()).isTrue();
        assertThat(cambiado.rateLimit().clases().get("lectura").peticiones())
                .as("el resto conserva sus defaults").isEqualTo(120);
        assertThat(new Limite("login", cambiado.rateLimit().clases().get("login").peticiones(),
                cambiado.rateLimit().clases().get("login").ventanaSegundos(),
                cambiado.rateLimit().clases().get("login").burst()).peticiones()).isEqualTo(3);
    }

    /** Binder sobre las propiedades de application.yml del módulo + overrides. */
    private static Binder binder(Map<String, String> overrides) {
        Map<String, String> props = new java.util.HashMap<>();
        props.put("gateway.rate-limit.expiracion-segundos", "300");
        props.put("gateway.rate-limit.clases.default.peticiones", "300");
        props.put("gateway.rate-limit.clases.default.ventana-segundos", "60");
        props.put("gateway.rate-limit.clases.default.burst", "100");
        props.put("gateway.rate-limit.clases.login.peticiones", "10");
        props.put("gateway.rate-limit.clases.login.ventana-segundos", "60");
        props.put("gateway.rate-limit.clases.login.burst", "10");
        props.put("gateway.rate-limit.clases.lectura.peticiones", "120");
        props.put("gateway.rate-limit.clases.lectura.ventana-segundos", "60");
        props.put("gateway.rate-limit.clases.lectura.burst", "60");
        props.put("gateway.rate-limit.clases.ws.peticiones", "30");
        props.put("gateway.rate-limit.clases.ws.ventana-segundos", "60");
        props.put("gateway.rate-limit.clases.ws.burst", "30");
        props.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(props));
    }
}
