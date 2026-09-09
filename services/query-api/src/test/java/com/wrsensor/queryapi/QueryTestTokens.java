package com.wrsensor.queryapi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Util de tests: mint JWT HS256 (secret default dev de query-api). */
final class QueryTestTokens {

    static final String SECRET = "wrsensor-dev-secret-2026-no-usar-en-prod";

    private QueryTestTokens() {}

    static String mint(UUID sub, String rol) {
        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("\"sub\":\"" + sub + "\",\"rol\":\"" + rol
                + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600));
        String input = header + "." + payload;
        return input + "." + b64(sign(input));
    }

    private static byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
