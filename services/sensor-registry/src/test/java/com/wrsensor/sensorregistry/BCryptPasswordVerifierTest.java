package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.infrastructure.adapter.out.security.BCryptPasswordVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0006 BR-001 (password nunca en claro; verificacion BCrypt).
 * Test ID: unit-test:FEAT-0006-br001.
 */
class BCryptPasswordVerifierTest {

    private final BCryptPasswordVerifier verifier = new BCryptPasswordVerifier();

    @Test
    @DisplayName("BR-001: el hash BCrypt matchea la password original y no es igual al texto plano")
    void testBR001_hashNoEsPlaintextYMatchea() {
        String raw = "Admin123!";
        StepVerifier.create(verifier.matches(raw, "$2a$10$hashinvalido"))
                .expectNext(false)
                .verifyComplete();

        // Generamos un hash con el encoder (mismo algoritmo que el seeder) y verificamos.
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(raw);
        assertThat(hash).isNotEqualTo(raw).startsWith("$2a$");
        StepVerifier.create(verifier.matches(raw, hash))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(verifier.matches("wrong", hash))
                .expectNext(false)
                .verifyComplete();
    }
}
