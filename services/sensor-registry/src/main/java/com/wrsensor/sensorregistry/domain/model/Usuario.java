package com.wrsensor.sensorregistry.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Entidad Usuario (auth, owner=sensor-registry) — spec §4.
 * El password nunca se expone: solo {@code passwordHash} (BCrypt, BR-001).
 * {@code email} se normaliza a minusculas (BR-002).
 */
public record Usuario(
        UUID id,
        String email,
        String passwordHash,
        Rol rol
) {

    public Usuario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(rol, "rol");
    }
}
