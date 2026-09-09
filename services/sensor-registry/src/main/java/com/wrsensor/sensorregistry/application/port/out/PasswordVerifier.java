package com.wrsensor.sensorregistry.application.port.out;

import reactor.core.publisher.Mono;

/**
 * Port out: verificacion de password contra su hash (FEAT-0006 BR-001, BCrypt).
 * El adapter decide el scheduling si el verificador es bloqueante de CPU
 * (BCrypt) para no tapar hilos de reactor.
 */
public interface PasswordVerifier {

    Mono<Boolean> matches(String rawPassword, String passwordHash);
}
