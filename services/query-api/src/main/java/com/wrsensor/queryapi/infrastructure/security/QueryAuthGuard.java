package com.wrsensor.queryapi.infrastructure.security;

import com.wrsensor.queryapi.domain.QueryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guard de lectura {ADMIN, VIEWER} (FEAT-0013 BR-006): verifica el JWT HS256
 * (formato FEAT-0006, secret compartido AUTH_JWT_SECRET) una vez por request y
 * guarda el rol como atributo del exchange (patron de sensor-registry ADR-0006).
 */
@Component
public class QueryAuthGuard {

    public static final String ATTR_ROL = "query.rol";

    private final String secret;

    public QueryAuthGuard(@Value("${query.jwt.secret:${AUTH_JWT_SECRET:wrsensor-dev-secret-2026-no-usar-en-prod}}") String secret) {
        this.secret = secret;
    }

    public static <T> Mono<T> requireReader(ServerWebExchange exchange, Mono<T> downstream) {
        String rol = exchange.getAttribute(ATTR_ROL);
        if (rol == null) {
            return Mono.error(new QueryException(QueryException.UNAUTHENTICATED, "Authorization requerido"));
        }
        if (!"ADMIN".equals(rol) && !"VIEWER".equals(rol)) {
            return Mono.error(new QueryException(QueryException.INSUFFICIENT_ROLE, "rol ADMIN o VIEWER requerido"));
        }
        return downstream;
    }

    /** Rol del header; null si no hay header o el token no verifica. */
    public String rolDe(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) return null;
        String token = authorizationHeader.trim();
        if (token.startsWith("Bearer ")) token = token.substring(7).trim();
        if (token.isEmpty()) return null;
        return verify(token).orElse(null);
    }

    private Optional<String> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] given = decode(parts[2]);
            if (!MessageDigest.isEqual(expected, given)) return Optional.empty();
            String payload = new String(decode(parts[1]), StandardCharsets.UTF_8);
            if (!expValido(payload)) return Optional.empty();
            return Optional.ofNullable(rol(payload));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final Pattern P_EXP = Pattern.compile("\"exp\":(-?\\d+)");
    private static final Pattern P_ROL = Pattern.compile("\"rol\":\"([A-Z_]+)\"");

    private static boolean expValido(String payload) {
        Matcher m = P_EXP.matcher(payload);
        return m.find() && Long.parseLong(m.group(1)) > Instant.now().getEpochSecond();
    }

    private static String rol(String payload) {
        Matcher m = P_ROL.matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decode(String s) {
        String padded = s;
        int mod = s.length() % 4;
        if (mod != 0) padded = s + "=".repeat(4 - mod);
        return Base64.getUrlDecoder().decode(padded);
    }
}
