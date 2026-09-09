package com.wrsensor.queryapi.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cursor opaco de keyset (FEAT-0013 BR-001): codifica el ts de la ultima fila
 * (Base64url de {"ts":<epochMilli>}). Malformado → QueryException
 * SENSOR_INVALID_CURSOR. Sin libs.
 */
public final class CursorLecturas {

    private static final Pattern JSON = Pattern.compile("\\{\"ts\":(-?\\d+)\\}");

    private CursorLecturas() {}

    public static String encode(Instant ts) {
        String json = "{\"ts\":" + ts.toEpochMilli() + "}";
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Instant decode(String cursor) {
        try {
            String padded = cursor;
            int mod = cursor.length() % 4;
            if (mod != 0) padded = cursor + "=".repeat(4 - mod);
            String json = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            Matcher m = JSON.matcher(json);
            if (!m.matches()) throw new IllegalArgumentException("formato");
            return Instant.ofEpochMilli(Long.parseLong(m.group(1)));
        } catch (RuntimeException e) {
            throw new QueryException(QueryException.SENSOR_INVALID_CURSOR, "cursor malformado");
        }
    }
}
