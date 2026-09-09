package com.wrsensor.sensorregistry.infrastructure.adapter.out.security;

import com.wrsensor.sensorregistry.application.port.out.TokenIssuer;
import com.wrsensor.sensorregistry.application.port.out.TokenVerifier;
import com.wrsensor.sensorregistry.domain.model.Rol;
import com.wrsensor.sensorregistry.domain.model.Usuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter out (security): emision/verificacion de JWT HS256 (FEAT-0006 BR-003/BR-004).
 *
 * <p>Implementacion JDK estandar sin dependencias nuevas (jjwt/nimbus no estan
 * completos en el .m2 offline): header/payload JSON compactos en Base64url sin
 * padding y firma HMAC-SHA256. Claims: {@code sub} (UUID), {@code rol}
 * (ADMIN|VIEWER), {@code iat}, {@code exp = iat + expiration-seconds}.
 * Construccion/parseo del payload con formato fijo y regex (ponytail: mismo
 * criterio que el {@code CursorCodec} de FEAT-0002 — cero deps en el path).
 * Verificacion: firma (comparacion constant-time) + exp; firma valida con rol
 * ajeno al par de negocio → {@link Rol#OTHER} (403); cualquier fallo → empty (401).
 *
 * <p>Cero I/O (pura CPU): no bloquea hilos de reactor.
 */
@Component
public class JwtAdapter implements TokenIssuer, TokenVerifier {

    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    // Formato fijo emitido por issue(): {"sub":"<uuid>","rol":"<ROL>","iat":<n>,"exp":<n>}
    private static final Pattern P_SUB = Pattern.compile("\"sub\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern P_ROL = Pattern.compile("\"rol\":\"([A-Z_]+)\"");
    private static final Pattern P_EXP = Pattern.compile("\"exp\":(-?\\d+)");

    private final String secret;
    private final long expirationSeconds;

    public JwtAdapter(@Value("${auth.jwt.secret}") String secret,
                      @Value("${auth.jwt.expiration-seconds:3600}") long expirationSeconds) {
        this.secret = secret;
        this.expirationSeconds = expirationSeconds;
    }

    // ============ TokenIssuer ============

    @Override
    public IssuedToken issue(Usuario usuario) {
        long now = Instant.now().getEpochSecond();
        String header = b64(HEADER_JSON);
        String payload = b64("\"sub\":\"" + usuario.id() + "\",\"rol\":\"" + usuario.rol().name()
                + "\",\"iat\":" + now + ",\"exp\":" + (now + expirationSeconds));
        String signingInput = header + "." + payload;
        return new IssuedToken(signingInput + "." + b64(sign(signingInput)), expirationSeconds);
    }

    // ============ TokenVerifier ============

    @Override
    public Mono<Rol> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Mono.empty();
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = sign(signingInput);
            byte[] given = decodeB64(parts[2]);
            if (!MessageDigest.isEqual(expected, given)) return Mono.empty();

            String payload = new String(decodeB64(parts[1]), StandardCharsets.UTF_8);
            long exp = longGroup(P_EXP, payload);
            if (exp <= 0 || exp <= Instant.now().getEpochSecond()) return Mono.empty();
            if (group(P_SUB, payload) == null) return Mono.empty();

            return Mono.just(parseRol(group(P_ROL, payload)));
        } catch (RuntimeException e) {
            return Mono.empty();
        }
    }

    private static Rol parseRol(String claim) {
        if (claim == null) return Rol.OTHER;
        for (Rol r : Rol.values()) {
            if (r.name().equalsIgnoreCase(claim)) return r;
        }
        // firma valida pero rol ajeno al par de negocio → sentinel OTHER (403 vía guard)
        return Rol.OTHER;
    }

    // ============ helpers ============

    private static String group(Pattern p, String payload) {
        Matcher m = p.matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    private static long longGroup(Pattern p, String payload) {
        Matcher m = p.matcher(payload);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }

    /** Base64url sin padding (espejo del CursorCodec de FEAT-0002). */
    private static String b64(String utf8) {
        return b64(utf8.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decodeB64(String s) {
        String padded = s;
        int mod = s.length() % 4;
        if (mod != 0) padded = s + "=".repeat(4 - mod);
        return Base64.getUrlDecoder().decode(padded);
    }
}
