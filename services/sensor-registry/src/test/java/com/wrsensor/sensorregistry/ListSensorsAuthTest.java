package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.domain.model.Rol;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.GlobalErrorHandler;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.RolGuard;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.RolGuard.InsufficientRoleException;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0002 BR-006, BR-007, AF-02 y assertions AC-002, AC-003,
 * AC-004 (guard reactivo de lectura {@code GET /api/sensores}).
 * Test IDs: unit-test:FEAT-0002-br006/br007/af002, assertion:FEAT-0002-ac002/ac003/ac004.
 *
 * <p>Espejo de {@code AF04RolGuardTest} (FEAT-0001) pero sobre
 * {@link RolGuard#requireReader}: la lectura permite {@code {ADMIN, VIEWER}}
 * (BR-006) — diferencia clave con FEAT-0001 (escritura ADMIN-only). Mismo patron:
 * {@link StepVerifier} sobre el chain reactivo con el rol en el Reactor Context,
 * sin levantar Spring, y mapeo a 401/403 via {@link GlobalErrorHandler#handleAuth}.
 */
class ListSensorsAuthTest {

    private static final String DOWNSTREAM_VALUE = "ok";

    /** Construye el chain con el rol en el Reactor Context (upstream), igual que AF04RolGuardTest. */
    private static Mono<String> guarded(Rol rol) {
        Mono<String> core = RolGuard.requireReader(Mono.just(DOWNSTREAM_VALUE));
        Context ctx = SecurityConfig.WriteRolContext.with(rol); // Context.empty() si rol == null
        return ctx.hasKey("wrsensor.rol")
                ? core.contextWrite(ctx)
                : core;
    }

    // ============ BR-006 ============

    @Test
    @DisplayName("BR-006 / AC-001 prerequisito: rol ADMIN → el downstream de lectura pasa")
    void testBR006_adminPuedeLeer() {
        StepVerifier.create(guarded(Rol.ADMIN))
                .expectNext(DOWNSTREAM_VALUE)
                .verifyComplete();
    }

    @Test
    @DisplayName("BR-006 / AC-002: rol VIEWER → tambien puede listar (pasa)")
    void testBR006_viewerPuedeLeer() {
        StepVerifier.create(guarded(Rol.VIEWER))
                .expectNext(DOWNSTREAM_VALUE)
                .verifyComplete();
    }

    // ============ BR-007 ============

    @Test
    @DisplayName("BR-007 / AF-01 / AC-003: sin Authorization → InsufficientRoleException(UNAUTHENTICATED)")
    void testBR007_sinAuth401Unauthenticated() {
        StepVerifier.create(guarded(null))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InsufficientRoleException.class);
                    assertThat(((InsufficientRoleException) err).code).isEqualTo("UNAUTHENTICATED");
                });
    }

    // ============ AF-02 / AC-004 ============

    @Test
    @DisplayName("AF-02 / AC-004: rol fuera de {ADMIN, VIEWER} (sentinel OTHER) → InsufficientRoleException(INSUFFICIENT_ROLE)")
    void testAF02_rolFuera403InsufficientRole() {
        StepVerifier.create(guarded(Rol.OTHER))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InsufficientRoleException.class);
                    assertThat(((InsufficientRoleException) err).code).isEqualTo("INSUFFICIENT_ROLE");
                });
    }

    // ============ mapeo HTTP (assertions) ============

    @Test
    @DisplayName("AC-003: UNAUTHENTICATED → 401 UNAUTHORIZED (GlobalErrorHandler)")
    void testAC003_unauthMapeaA401() {
        ResponseEntity<Map<String, Object>> resp = new GlobalErrorHandler().handleAuth(
                new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("AC-004: INSUFFICIENT_ROLE → 403 FORBIDDEN (GlobalErrorHandler)")
    void testAC004_insufficientRoleMapeaA403() {
        ResponseEntity<Map<String, Object>> resp = new GlobalErrorHandler().handleAuth(
                new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN o VIEWER requerido"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("INSUFFICIENT_ROLE");
    }
}
