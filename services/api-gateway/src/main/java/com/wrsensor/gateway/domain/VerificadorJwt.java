package com.wrsensor.gateway.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verificación de JWT HS256 (FEAT-0008 BR-003) para el handshake WebSocket.
 *
 * <p>Espeja la semántica del `JwtAdapter` de `sensor-registry` (mismo formato de token: header,
 * payload y firma en Base64url sin padding; claims `sub`, `rol`, `iat`, `exp`) pero **sin
 * dependencias nuevas**: JDK crypto y comparación constant-time. El gateway sólo necesita
 * *verificar* para autorizar el upgrade; la emisión y la autorización de los REST siguen en
 * `sensor-registry`/`query-api`.</p>
 *
 * <p>Clase de dominio pura: no conoce HTTP, Spring ni logging. Cero I/O.</p>
 */
public final class VerificadorJwt {

    /** Claims que necesita el gateway para autorizar. */
    public record Claims(String sujeto, String rol, long expiraEnSegundos) {

        public boolean expirado(long ahoraSegundos) {
            return expiraEnSegundos <= 0 || expiraEnSegundos <= ahoraSegundos;
        }
    }

    // Formato emitido por sensor-registry: "sub":"<uuid>","rol":"<ROL>","iat":<n>,"exp":<n>
    private static final Pattern P_SUB = Pattern.compile("\"sub\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern P_ROL = Pattern.compile("\"rol\":\"([A-Z_]+)\"");
    private static final Pattern P_EXP = Pattern.compile("\"exp\":(-?\\d+)");

    private final String secreto;

    public VerificadorJwt(String secreto) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalStateException("gateway.ws.jwt-secreto vacio: no se puede verificar "
                    + "el token del handshake WebSocket");
        }
        this.secreto = secreto;
    }

    /**
     * Verifica firma y expiración.
     *
     * @param token          JWT crudo (sin el prefijo `Bearer`)
     * @param ahoraSegundos  epoch actual (inyectable para tests)
     * @return los claims si el token es válido y no expiró; vacío en cualquier otro caso
     */
    public Optional<Claims> verificar(String token, long ahoraSegundos) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] partes = token.trim().split("\\.");
            if (partes.length != 3) {
                return Optional.empty();
            }
            String entradaFirmada = partes[0] + "." + partes[1];
            byte[] esperada = firmar(entradaFirmada);
            byte[] recibida = decodificarB64(partes[2]);
            if (!MessageDigest.isEqual(esperada, recibida)) {
                return Optional.empty();
            }
            String payload = new String(decodificarB64(partes[1]), StandardCharsets.UTF_8);
            String sujeto = grupo(P_SUB, payload);
            long expira = largo(P_EXP, payload);
            if (sujeto == null) {
                return Optional.empty();
            }
            Claims claims = new Claims(sujeto, grupo(P_ROL, payload), expira);
            return claims.expirado(ahoraSegundos) ? Optional.empty() : Optional.of(claims);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ============ helpers (mismos que el emisor) ============

    private static String grupo(Pattern p, String payload) {
        Matcher m = p.matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    private static long largo(Pattern p, String payload) {
        Matcher m = p.matcher(payload);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private byte[] firmar(String entrada) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(entrada.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }

    private static byte[] decodificarB64(String s) {
        String conPadding = s;
        int mod = s.length() % 4;
        if (mod != 0) {
            conPadding = s + "=".repeat(4 - mod);
        }
        return Base64.getUrlDecoder().decode(conPadding);
    }
}
