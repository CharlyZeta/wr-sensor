package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.out.TokenIssuer;
import com.wrsensor.sensorregistry.domain.model.Rol;
import com.wrsensor.sensorregistry.domain.model.Usuario;
import com.wrsensor.sensorregistry.infrastructure.adapter.out.security.JwtAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0006 BR-003/BR-004/BR-005 + AF-06 (JWT HS256 del adapter).
 * Test IDs: unit-test:FEAT-0006-br003/br004/br005, unit-test:FEAT-0006-af006.
 *
 * <p>JwtAdapter instanciado directo (sin Spring) con secret fijo. Los tokens
 * "externos" (rol ajeno, exp pasado) se mintean con {@link TestTokens} usando el
 * mismo formato/firma.
 */
class JwtAdapterTest {

    private static final String SECRET = TestTokens.DEV_SECRET;
    private static final long EXPIRATION = 3600L;
    private final JwtAdapter adapter = new JwtAdapter(SECRET, EXPIRATION);

    private static final Usuario ADMIN = new Usuario(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "admin@wrsensor.local", "$2a$hash", Rol.ADMIN);

    @Test
    @DisplayName("BR-003: issue emite JWT HS256 con claims sub/rol/iat/exp y expiraEnSegundos configurado")
    void testBR003_emisionConClaims() {
        TokenIssuer.IssuedToken issued = adapter.issue(ADMIN);

        assertThat(issued.expiresInSeconds()).isEqualTo(EXPIRATION);
        String[] parts = issued.token().split("\\.");
        assertThat(parts).hasSize(3);

        String payload = TestTokens.decodePayload(issued.token());
        assertThat(payload).contains("\"sub\":\"" + ADMIN.id() + "\"")
                .contains("\"rol\":\"ADMIN\"")
                .contains("\"exp\":");
        long exp = TestTokens.expOf(issued.token());
        assertThat(exp).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(exp - Instant.now().getEpochSecond()).isLessThanOrEqualTo(EXPIRATION + 5);
    }

    @Test
    @DisplayName("BR-003/BR-004: token emitido verifica y devuelve el rol del claim (ADMIN y VIEWER)")
    void testBR003_004_verificaTokenEmitido() {
        StepVerifier.create(adapter.verify(adapter.issue(ADMIN).token()))
                .expectNext(Rol.ADMIN)
                .verifyComplete();

        Usuario viewer = new Usuario(ADMIN.id(), "viewer@wrsensor.local", "$2a$hash", Rol.VIEWER);
        StepVerifier.create(adapter.verify(adapter.issue(viewer).token()))
                .expectNext(Rol.VIEWER)
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-004: token con payload manipulado, firma invalida o basura → empty (401 vía guard)")
    void testBR004_rechazaManipulado() {
        String token = adapter.issue(ADMIN).token();

        // payload manipulado (la firma original ya no matchea el nuevo contenido)
        String[] parts = token.split("\\.");
        String modified = TestTokens.decodePayload(token).replace("\"rol\":\"ADMIN\"", "\"rol\":\"ADMIN2\"");
        String tampered = parts[0] + "." + TestTokens.encodePayload(modified) + "." + parts[2];
        StepVerifier.create(adapter.verify(tampered)).verifyComplete();

        StepVerifier.create(adapter.verify("not-a-jwt")).verifyComplete();
        StepVerifier.create(adapter.verify("a.b.c")).verifyComplete();
    }

    @Test
    @DisplayName("BR-004 / AC-007: token expirado → empty")
    void testBR004_rechazaExpirado() {
        String expired = TestTokens.mint(ADMIN.id(), "ADMIN", Instant.now().getEpochSecond() - 60, SECRET);
        StepVerifier.create(adapter.verify(expired)).verifyComplete();
    }

    @Test
    @DisplayName("BR-004 / AF-02: firma valida con rol ajeno a {ADMIN,VIEWER} → Rol.OTHER (403 vía guard)")
    void testBR004_rolAjenoDevuelveOTHER() {
        String auditor = TestTokens.mint(ADMIN.id(), "AUDITOR");
        StepVerifier.create(adapter.verify(auditor))
                .expectNext(Rol.OTHER)
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-005: stateless — el mismo token verifica repetidamente sin estado compartido")
    void testBR005_stateless() {
        String token = adapter.issue(ADMIN).token();
        StepVerifier.create(adapter.verify(token)).expectNext(Rol.ADMIN).verifyComplete();
        StepVerifier.create(adapter.verify(token)).expectNext(Rol.ADMIN).verifyComplete();
    }

    @Test
    @DisplayName("AF-06: con el secret default dev configurado, emision y verificacion funcionan")
    void testAF06_secretDefaultDevEmiteYVerifica() {
        JwtAdapter dev = new JwtAdapter(SECRET, EXPIRATION);
        StepVerifier.create(dev.verify(dev.issue(ADMIN).token()))
                .expectNext(Rol.ADMIN)
                .verifyComplete();
    }
}
