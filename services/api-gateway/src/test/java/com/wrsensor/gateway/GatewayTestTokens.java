package com.wrsensor.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Util de tests: acuña JWT HS256 con el **mismo formato** que emite {@code sensor-registry}
 * (`JwtAdapter`): header/payload/firma en Base64url sin padding, claims {@code sub}, {@code rol},
 * {@code iat} y {@code exp}. Así los tests del gateway verifican la interoperabilidad real
 * (FEAT-0008 BR-003) sin depender del emisor.
 */
final class GatewayTestTokens {

    /** Mismo secreto default de desarrollo que usa la configuración del gateway en los tests. */
    static final String SECRET = "wrsensor-dev-secret-2026-no-usar-en-prod";

    private GatewayTestTokens() {}

    /** Token vigente (exp = ahora + 1 h). */
    static String vigente(String rol) {
        return vigente(SECRET, rol, Instant.now().getEpochSecond() + 3600);
    }

    /** Token ya vencido (exp = ahora - 60 s). */
    static String expirado(String rol) {
        return vigente(SECRET, rol, Instant.now().getEpochSecond() - 60);
    }

    /** Token firmado con **otro** secreto: firma inválida para el gateway. */
    static String firmaInvalida(String rol) {
        return vigente("otro-secreto-distinto", rol, Instant.now().getEpochSecond() + 3600);
    }

    static String vigente(String secreto, String rol, long expEpochSeconds) {
        long ahora = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("\"sub\":\"" + UUID.randomUUID() + "\",\"rol\":\"" + rol
                + "\",\"iat\":" + ahora + ",\"exp\":" + expEpochSeconds);
        String entrada = header + "." + payload;
        return entrada + "." + b64(firmar(secreto, entrada));
    }

    /** Payload alterado después de firmar (firma que ya no corresponde). */
    static String alterado(String token) {
        String[] partes = token.split("\\.");
        return partes[0] + "." + b64("\"sub\":\"" + UUID.randomUUID()
                + "\",\"rol\":\"ADMIN\",\"iat\":0,\"exp\":" + (Instant.now().getEpochSecond() + 3600))
                + "." + partes[2];
    }

    /** Token bien firmado pero **sin** claim {@code sub} (formato ajeno al emisor). */
    static String sinSujeto(String rol) {
        long ahora = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("\"rol\":\"" + rol + "\",\"iat\":" + ahora + ",\"exp\":"
                + (ahora + 3600));
        String entrada = header + "." + payload;
        return entrada + "." + b64(firmar(SECRET, entrada));
    }

    private static byte[] firmar(String secreto, String entrada) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(entrada.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
