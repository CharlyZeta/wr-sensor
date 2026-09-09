package com.wrsensor.sensorregistry;

import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Util de tests: mintear JWT HS256 con el mismo formato que {@code JwtAdapter}
 * (secret default dev de application.yml) para casos que el issuer de produccion
 * no puede producir (rol ajeno AUDITOR, exp pasado) — FEAT-0006 AF-02/AC-004/AC-007.
 * Duplicacion deliberada del signing en tests (solo tests).
 */
final class TestTokens {

    /** Espejo del default de {@code auth.jwt.secret} en application.yml. */
    static final String DEV_SECRET = "wrsensor-dev-secret-2026-no-usar-en-prod";

    private TestTokens() {}

    static String mint(UUID sub, String rol, long expiresAtEpochSecond, String secret) {
        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("\"sub\":\"" + sub + "\",\"rol\":\"" + rol
                + "\",\"iat\":" + now + ",\"exp\":" + expiresAtEpochSecond);
        String input = header + "." + payload;
        return input + "." + b64(sign(input, secret));
    }

    static String mint(UUID sub, String rol) {
        return mint(sub, rol, Instant.now().getEpochSecond() + 3600, DEV_SECRET);
    }

    /** Login real contra POST /api/auth/login y devuelve el token (migracion de ITs a JWT real). */
    static String login(WebTestClient webClient, String email, String password) {
        byte[] body = webClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        String json = new String(body, StandardCharsets.UTF_8);
        var m = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("token ausente en login: " + json);
        return m.group(1);
    }

    /** Decodifica el payload (JSON plano, formato fijo de JwtAdapter). */
    static String decodePayload(String token) {
        String[] parts = token.split("\\.");
        return new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
    }

    /** Re-codifica un payload (para manipular tokens en tests). */
    static String encodePayload(String jsonPayload) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));
    }

    static long expOf(String token) {
        String payload = decodePayload(token);
        var m = java.util.regex.Pattern.compile("\"exp\":(-?\\d+)").matcher(payload);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static byte[] sign(String input, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String utf8) {
        return b64(utf8.getBytes(StandardCharsets.UTF_8));
    }

    private static String pad(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "=".repeat(4 - mod);
    }
}
