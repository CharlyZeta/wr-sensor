package com.wrsensor.sensorregistry.infrastructure.adapter.out.security;

import com.wrsensor.sensorregistry.application.port.out.PasswordVerifier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adapter out (security): verificacion de password contra hash BCrypt
 * (FEAT-0006 BR-001). BCrypt es CPU-bound (~100 ms): se ejecuta en
 * boundedElastic para no tapar hilos de reactor (regla reactiva del stack).
 */
@Component
public class BCryptPasswordVerifier implements PasswordVerifier {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public Mono<Boolean> matches(String rawPassword, String passwordHash) {
        return Mono.fromCallable(() -> encoder.matches(rawPassword, passwordHash))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
