package com.wrsensor.gateway.infrastructure.config;

import com.wrsensor.gateway.domain.Limite;
import com.wrsensor.gateway.domain.RateLimiterEnMemoria;
import com.wrsensor.gateway.domain.TablaRutas;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiring del gateway (FEAT-0007 BR-013): tabla de rutas, clases de límite y rate limiter
 * construidos y validados en el arranque (fail-fast ante configuración ambigua o incompleta).
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    /** Clases de límite por nombre (BR-004): debe existir al menos {@code default}. */
    @Bean
    Map<String, Limite> clasesLimite(GatewayProperties props) {
        Map<String, Limite> clases = new HashMap<>();
        GatewayProperties.RateLimitCfg cfg = props.rateLimit();
        if (cfg == null || cfg.clases() == null || cfg.clases().isEmpty()) {
            throw new IllegalStateException(
                    "gateway.rate-limit.clases vacio: definir al menos la clase 'default'");
        }
        cfg.clases().forEach((nombre, l) -> clases.put(nombre, new Limite(nombre,
                valor(l == null ? null : l.peticiones(), "gateway.rate-limit.clases." + nombre + ".peticiones"),
                valor(l == null ? null : l.ventanaSegundos(), "gateway.rate-limit.clases." + nombre + ".ventana-segundos"),
                valor(l == null ? null : l.burst(), "gateway.rate-limit.clases." + nombre + ".burst"))));
        if (!clases.containsKey("default")) {
            throw new IllegalStateException(
                    "gateway.rate-limit.clases: falta la clase 'default' (fallback obligatorio)");
        }
        return Map.copyOf(clases);
    }

    private static int valor(Integer v, String clave) {
        if (v == null) {
            throw new IllegalStateException(clave + " es obligatorio");
        }
        return v;
    }

    /** Tabla de rutas validada (BR-001/BR-002). */
    @Bean
    TablaRutas tablaRutas(GatewayProperties props, Map<String, Limite> clasesLimite) {
        if (props.rutas() == null || props.rutas().isEmpty()) {
            throw new IllegalStateException("gateway.rutas vacio: el gateway necesita rutas");
        }
        List<TablaRutas.Ruta> rutas = props.rutas().stream()
                .map(r -> new TablaRutas.Ruta(r.id(), r.patron(),
                        r.metodos() == null ? java.util.Set.of()
                                : java.util.Set.copyOf(r.metodos()),
                        r.destino(), r.claseLimiteOrDefault(), r.timeoutMsOrDefault()))
                .toList();        for (TablaRutas.Ruta r : rutas) {
            if (!clasesLimite.containsKey(r.claseLimite())) {
                throw new IllegalStateException("gateway.rutas." + r.id() + ".clase-limite desconocida: '"
                        + r.claseLimite() + "' (clases: " + clasesLimite.keySet() + ")");
            }
        }
        return TablaRutas.de(rutas);
    }

    @Bean
    RateLimiterEnMemoria rateLimiter(GatewayProperties props) {
        long expiracion = props.rateLimit() == null ? 300L
                : props.rateLimit().expiracionSegundosOrDefault();
        return new RateLimiterEnMemoria(Duration.ofSeconds(expiracion));
    }

    /**
     * Verificador HS256 del handshake WS (FEAT-0008 BR-003). Fail-fast: sin secreto configurado el
     * gateway no arranca en vez de aceptar upgrades sin poder validarlos.
     */
    @Bean
    com.wrsensor.gateway.domain.VerificadorJwt verificadorJwt(GatewayProperties props) {
        String secreto = props.ws() == null ? null : props.ws().jwtSecreto();
        return new com.wrsensor.gateway.domain.VerificadorJwt(secreto);
    }

    /** Autorizador del handshake (roles permitidos + nombre del query param del token). */
    @Bean
    com.wrsensor.gateway.infrastructure.adapter.in.web.AutenticadorWs autenticadorWs(
            GatewayProperties props, com.wrsensor.gateway.domain.VerificadorJwt verificadorJwt) {
        GatewayProperties.WsCfg cfg = props.ws() == null
                ? new GatewayProperties.WsCfg(null, null, null) : props.ws();
        return new com.wrsensor.gateway.infrastructure.adapter.in.web.AutenticadorWs(cfg,
                verificadorJwt);
    }

    /**
     * WebClient del proxy: sin buffer de request (streaming), sin codecs de error que
     * intercepten el status downstream (se devuelve el status tal cual, BR-009).
     */
    @Bean
    WebClient webClientProxy() {
        HttpClient http = HttpClient.create()
                .compress(false)
                .responseTimeout(Duration.ofSeconds(60))
                .followRedirect(false);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }
}
