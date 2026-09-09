package com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Body de POST /api/auth/login (FEAT-0006). */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
