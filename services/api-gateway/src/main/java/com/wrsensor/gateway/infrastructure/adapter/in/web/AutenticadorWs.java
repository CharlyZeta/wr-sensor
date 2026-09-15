package com.wrsensor.gateway.infrastructure.adapter.in.web;

import com.wrsensor.gateway.domain.CodigosError;
import com.wrsensor.gateway.domain.VerificadorJwt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Autorización del handshake WebSocket (FEAT-0008 BR-003/BR-004).
 *
 * <p>El navegador no puede enviar {@code Authorization} en el upgrade, así que el token se acepta
 * por query string ({@code ?token=<jwt>}, nombre configurable) o por {@code Authorization: Bearer}
 * para clientes no navegador. La verificación es síncrona y pura ({@link VerificadorJwt}): si no
 * autoriza, el gateway responde {@code 401}/{@code 403} HTTP **antes** de completar el upgrade, de
 * modo que no se abre ninguna sesión ni se contacta al downstream.</p>
 *
 * <p>El token consumido **no se propaga** al downstream ({@link #querySinToken(String)}) ni se
 * registra en los logs (los mensajes de rechazo nunca incluyen el valor).</p>
 */
public class AutenticadorWs {

    /** Rechazo del upgrade: status HTTP + código de dominio + mensaje (sin el token). */
    public record Rechazo(HttpStatus status, String codigo, String mensaje) {}

    private final VerificadorJwt verificador;
    private final List<String> rolesPermitidos;
    private final String parametroToken;
    private final Clock reloj;

    public AutenticadorWs(VerificadorJwt verificador, List<String> rolesPermitidos,
                          String parametroToken, Clock reloj) {
        this.verificador = verificador;
        this.rolesPermitidos = List.copyOf(rolesPermitidos);
        this.parametroToken = parametroToken;
        this.reloj = reloj;
    }

    /** Constructor de producción: roles y nombre del parámetro salen de {@code gateway.ws.*}. */
    public AutenticadorWs(com.wrsensor.gateway.infrastructure.config.GatewayProperties.WsCfg cfg,
                          VerificadorJwt verificador) {
        this(verificador, cfg.rolesPermitidosOrDefault(), cfg.parametroTokenOrDefault(),
                Clock.systemUTC());
    }

    /** Vacío = upgrade autorizado; presente = rechazo a responder tal cual. */
    public Optional<Rechazo> autorizar(ServerWebExchange exchange) {
        String token = tokenDe(exchange).orElse(null);
        if (token == null) {
            return Optional.of(new Rechazo(HttpStatus.UNAUTHORIZED, CodigosError.UNAUTHENTICATED,
                    "token requerido en el handshake WebSocket"));
        }
        VerificadorJwt.Claims claims = verificador
                .verificar(token, reloj.instant().getEpochSecond())
                .orElse(null);
        if (claims == null) {
            return Optional.of(new Rechazo(HttpStatus.UNAUTHORIZED, CodigosError.UNAUTHENTICATED,
                    "token invalido o expirado"));
        }
        if (claims.rol() == null || !rolesPermitidos.contains(claims.rol())) {
            return Optional.of(new Rechazo(HttpStatus.FORBIDDEN, CodigosError.INSUFFICIENT_ROLE,
                    "rol no autorizado para WebSocket (permitidos: " + rolesPermitidos + ")"));
        }
        return Optional.empty();
    }

    /**
     * Query string sin el parámetro del token, lista para reenviar al downstream (BR-004). Devuelve
     * {@code null} cuando no queda nada, para no agregar un {@code ?} colgante.
     */
    public String querySinToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String par : rawQuery.split("&")) {
            if (par.isBlank() || nombre(par).equals(parametroToken)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(par);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Token del {@code Authorization: Bearer} si está, si no el del query string. */
    public Optional<String> tokenDe(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && !header.isBlank()) {
            String valor = header.trim();
            if (valor.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = valor.substring(7).trim();
                if (!token.isEmpty()) {
                    return Optional.of(token);
                }
            }
        }
        return Optional.ofNullable(exchange.getRequest().getQueryParams().getFirst(parametroToken))
                .map(String::trim)
                .filter(t -> !t.isEmpty());
    }

    private static String nombre(String par) {
        int eq = par.indexOf('=');
        return eq < 0 ? par : par.substring(0, eq);
    }
}
