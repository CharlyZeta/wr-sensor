package com.wrsensor.gateway.domain;

/**
 * Códigos de error del gateway (FEAT-0007 BR-009), alineados con la convención
 * {@code {"code","message"}} de {@code docs/API.md}.
 */
public final class CodigosError {

    public static final String ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
    public static final String WS_UPGRADE_REQUIRED = "WS_UPGRADE_REQUIRED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private CodigosError() {
    }

    /** JSON de error mínimo y sin dependencias (mismo estilo que el resto de los servicios). */
    public static String json(String code, String message) {
        return "{\"code\":\"" + escapar(code) + "\",\"message\":\"" + escapar(message) + "\"}";
    }

    private static String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(texto.length() + 8);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
