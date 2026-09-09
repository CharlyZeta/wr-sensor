package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.domain.model.InvalidListCursorException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Codec opaco del cursor de paginacion keyset (FEAT-0002 BR-003).
 *
 * <p>Formato: Base64 URL-safe (sin padding) de JSON compacto
 * {@code {"ts":<epochMilli>,"id":"<uuid>"}}. El servidor lo genera desde
 * {@code (fechaInstalacion, id)} de la ultima fila; el cliente lo devuelve verbatim.
 * Sin TTL ni HMAC en v1: opaco, no secreto. Cualquier fallo de parseo →
 * {@link InvalidListCursorException}.
 *
 * <p>Vive en application (sin Spring): la logica pura de codificar/decodificar el cursor
 * es de aplicacion. Sin libs externas — Base64 + regex sobre el formato fijo.
 */
public final class CursorCodec {

    public record Cursor(Instant fecha, UUID id) {}

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    // ponytail: regex en lugar de Jackson — formato fijo y trivial; cero deps en application.
    private static final Pattern JSON =
            Pattern.compile("\\{\"ts\":(-?\\d+),\"id\":\"([0-9a-fA-F-]{36})\"\\}");

    private CursorCodec() {}

    public static String encode(Instant fecha, UUID id) {
        String json = "{\"ts\":" + fecha.toEpochMilli() + ",\"id\":\"" + id + "\"}";
        return ENC.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        try {
            String json = new String(DEC.decode(pad(cursor)), StandardCharsets.UTF_8);
            var m = JSON.matcher(json);
            if (!m.matches()) throw new InvalidListCursorException();
            Instant fecha = Instant.ofEpochMilli(Long.parseLong(m.group(1)));
            UUID id = UUID.fromString(m.group(2));
            return new Cursor(fecha, id);
        } catch (InvalidListCursorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidListCursorException();
        }
    }

    /** El encoder sin padding produce strings no multiples de 4; el decoder requiere padding. */
    private static String pad(String s) {
        int mod = s.length() % 4;
        return mod == 0 ? s : s + "=".repeat(4 - mod);
    }

    // ponytail: self-check runnable del parser (roundtrip + malformado). Ejecutar standalone.
    public static void main(String[] args) {
        java.time.Instant ts = java.time.Instant.ofEpochMilli(1_723_593_600_000L);
        java.util.UUID id = java.util.UUID.fromString("12345678-1234-4321-8765-fedcba987654");
        String enc = encode(ts, id);
        Cursor c = decode(enc);
        check(c.fecha().equals(ts), "fecha roundtrip");
        check(c.id().equals(id), "id roundtrip");
        boolean threw = false;
        try { decode("no-es-un-cursor-valido"); } catch (InvalidListCursorException e) { threw = true; }
        check(threw, "malformado -> InvalidListCursorException");
        System.out.println("CursorCodec OK");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
