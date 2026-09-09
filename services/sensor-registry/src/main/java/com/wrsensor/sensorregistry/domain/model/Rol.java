package com.wrsensor.sensorregistry.domain.model;

/**
 * Rol de Usuario (auth). ADMIN y VIEWER son los roles de negocio (stack.md:
 * "roles (ADMIN, VIEWER)"). FEAT-0002 AF-02 / AC-004 requieren distinguir
 * "sin Authorization" (401) de "Authorization presente pero rol fuera de
 * {ADMIN, VIEWER}" (403); {@link com.wrsensor.sensorregistry.infrastructure.adapter.in.web.SecurityConfig.RolFilter}
 * mapea un token no reconocido a {@link #OTHER} para que
 * {@code RolGuard.requireReader}/{@code requireAdmin} lo rechacen con 403.
 *
 * <p>OTHER es un sentinel de auth (no un rol de negocio): representa un token
 * presente cuyo rol no casa con ADMIN/VIEWER. Si el Gate rechaza este
 * sentinel, AF-02/AC-004 queda sin via 403 reachable — escalar.
 */
public enum Rol {
    ADMIN,
    VIEWER,
    OTHER
}
