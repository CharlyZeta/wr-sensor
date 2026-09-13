package com.wrsensor.gateway;

import com.wrsensor.gateway.domain.TablaRutas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-0007 — tabla de rutas (unit tests BR-001, BR-002, BR-004 y AF-01).
 */
class TablaRutasTest {

    private static TablaRutas.Ruta ruta(String id, String patron, Set<String> metodos,
                                        String destino, String clase) {
        return new TablaRutas.Ruta(id, patron, metodos, destino, clase, 10_000);
    }

    private static TablaRutas tablaCompleta() {
        return TablaRutas.de(List.of(
                ruta("login", "/api/auth/login", Set.of("POST"), "http://registry:8080", "login"),
                ruta("auth", "/api/auth/**", Set.of("POST"), "http://registry:8080", "default"),
                ruta("sensor-lectura", "/api/sensores/**", Set.of("GET"),
                        "http://registry:8080", "lectura"),
                ruta("sensor-escritura", "/api/sensores/**", Set.of("POST", "PUT", "DELETE"),
                        "http://registry:8080", "default"),
                ruta("query-lecturas", "/api/sensores/*/lecturas", Set.of("GET"),
                        "http://query:8082", "lectura"),
                ruta("query-actual", "/api/sensores/*/actual", Set.of("GET"),
                        "http://query:8082", "lectura"),
                ruta("ws-alertas", "/ws/alertas", Set.of(), "http://alerting:8083", "ws"),
                ruta("ws-sensores", "/ws/sensores/**", Set.of(), "http://query:8082", "ws")));
    }

    // ===== BR-001: gana el patrón más específico =====

    @Test
    @DisplayName("BR-001: /api/sensores/*/lecturas va a query-api aunque /api/sensores/** vaya a registry")
    void br001_patronMasEspecificoGana() {
        TablaRutas tabla = tablaCompleta();
        assertThat(tabla.resolver("GET", "/api/sensores/abc/lecturas"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("query-lecturas");
        assertThat(tabla.resolver("GET", "/api/sensores/abc/actual"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("query-actual");
        assertThat(tabla.resolver("GET", "/api/sensores"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("sensor-lectura");
        assertThat(tabla.resolver("GET", "/api/sensores/abc"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("sensor-lectura");
    }

    @Test
    @DisplayName("BR-001: configuración ambigua (misma especificidad, destinos distintos) falla al construir")
    void br001_configAmbigua() {
        assertThatThrownBy(() -> TablaRutas.de(List.of(
                ruta("a", "/api/x/**", Set.of("GET"), "http://uno:1", "default"),
                ruta("b", "/api/x/**", Set.of("GET"), "http://dos:2", "default"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("misma especificidad")
                .hasMessageContaining("destinos distintos");
    }

    @Test
    @DisplayName("BR-001: tabla vacía o con ids duplicados falla al construir (fail-fast)")
    void br001_tablaInvalida() {
        assertThatThrownBy(() -> TablaRutas.de(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.rutas vacio");
        assertThatThrownBy(() -> TablaRutas.de(List.of(
                ruta("dup", "/api/a", Set.of(), "http://uno:1", "default"),
                ruta("dup", "/api/b", Set.of(), "http://dos:2", "default"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ids duplicados");
    }

    // ===== BR-002: cobertura de rutas declarada =====

    @Test
    @DisplayName("BR-002: la tabla cubre auth, sensores (registry), lecturas/actual (query-api) y WS")
    void br002_coberturaDeRutas() {
        TablaRutas tabla = tablaCompleta();
        assertThat(tabla.resolver("POST", "/api/auth/login"))
                .get().extracting(TablaRutas.Ruta::destino).isEqualTo("http://registry:8080");
        assertThat(tabla.resolver("PUT", "/api/sensores/abc"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("sensor-escritura");
        assertThat(tabla.resolver("DELETE", "/api/sensores/abc"))
                .get().extracting(TablaRutas.Ruta::id).isEqualTo("sensor-escritura");
        assertThat(tabla.resolver("GET", "/ws/alertas"))
                .get().extracting(TablaRutas.Ruta::destino).isEqualTo("http://alerting:8083");
        assertThat(tabla.resolver("GET", "/ws/sensores/abc"))
                .get().extracting(TablaRutas.Ruta::destino).isEqualTo("http://query:8082");
    }

    @Test
    @DisplayName("BR-002: un patrón con '/' final y otro sin él no se confunden, y el destino normaliza el slash")
    void br002_destinoNormalizado() {
        TablaRutas.Ruta r = new TablaRutas.Ruta("x", "/api/x/**", Set.of(), "http://uno:1/",
                "default", 1000);
        assertThat(r.destino()).isEqualTo("http://uno:1");
    }

    // ===== BR-004: clase de límite por método =====

    @Test
    @DisplayName("BR-004: GET de sensores usa la clase lectura; las escrituras usan default; login su clase")
    void br004_claseLimitePorMetodo() {
        TablaRutas tabla = tablaCompleta();
        assertThat(tabla.resolver("GET", "/api/sensores").orElseThrow().claseLimite())
                .isEqualTo("lectura");
        assertThat(tabla.resolver("GET", "/api/sensores/abc/lecturas").orElseThrow().claseLimite())
                .isEqualTo("lectura");
        assertThat(tabla.resolver("POST", "/api/sensores").orElseThrow().claseLimite())
                .isEqualTo("default");
        assertThat(tabla.resolver("POST", "/api/auth/login").orElseThrow().claseLimite())
                .isEqualTo("login");
        assertThat(tabla.resolver("GET", "/ws/alertas").orElseThrow().claseLimite())
                .isEqualTo("ws");
    }

    @Test
    @DisplayName("BR-004: una ruta sin clase-limite explícita cae en 'default'")
    void br004_clasePorDefecto() {
        assertThat(ruta("sin-clase", "/api/x", Set.of(), "http://uno:1", null).claseLimite())
                .isEqualTo("default");
    }

    // ===== AF-01: ruta inexistente =====

    @Test
    @DisplayName("AF-01: path no declarado no resuelve ninguna ruta (el gateway responde 404)")
    void af01_rutaInexistente() {
        TablaRutas tabla = tablaCompleta();
        assertThat(tabla.resolver("GET", "/api/desconocido")).isEmpty();
        assertThat(tabla.resolver("GET", "/api/simulador/estado")).isEmpty();
        assertThat(tabla.resolver("GET", "/api/ingestion/algo")).isEmpty();
    }

    @Test
    @DisplayName("AF-01: método no declarado para un path existente tampoco resuelve (sin fallback)")
    void af01_metodoNoDeclarado() {
        TablaRutas tabla = TablaRutas.de(List.of(
                ruta("solo-get", "/api/sensores/**", Set.of("GET"), "http://registry:8080", "lectura")));
        assertThat(tabla.resolver("GET", "/api/sensores")).isPresent();
        assertThat(tabla.resolver("POST", "/api/sensores")).isEmpty();
    }
}
