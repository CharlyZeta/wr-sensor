package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

/**
 * Response de POST /api/auth/login (FEAT-0006, AC-001): body
 * {@code {token, rol, expiraEnSegundos}} (decision humana HO-Gate: sin email).
 */
public record LoginResponse(
        String token,
        String rol,
        long expiraEnSegundos
) {
}
