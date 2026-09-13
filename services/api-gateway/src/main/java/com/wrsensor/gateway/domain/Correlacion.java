package com.wrsensor.gateway.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Correlación de requests (FEAT-0007 BR-008 / AF-04): se acepta el {@code X-Correlation-Id}
 * del cliente sólo si es válido; si no, se genera uno. Nunca se propaga un valor basura.
 */
public final class Correlacion {

    public static final String HEADER = "X-Correlation-Id";
    public static final int MAX_LARGO = 64;

    private Correlacion() {
    }

    /** Valor válido = no vacío, ≤ {@value #MAX_LARGO} caracteres imprimibles (ASCII 0x21..0x7E). */
    public static Optional<String> valido(String valor) {
        if (valor == null) {
            return Optional.empty();
        }
        String recortado = valor.trim();
        if (recortado.isEmpty() || recortado.length() > MAX_LARGO) {
            return Optional.empty();
        }
        for (int i = 0; i < recortado.length(); i++) {
            char c = recortado.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return Optional.empty();
            }
        }
        return Optional.of(recortado);
    }

    /** Resuelve el id a usar: el del cliente si es válido, si no uno nuevo. */
    public static String resolver(String valorDelCliente) {
        return valido(valorDelCliente).orElseGet(() -> UUID.randomUUID().toString());
    }

    public static String nuevo() {
        return UUID.randomUUID().toString();
    }
}
