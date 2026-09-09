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
 * Unit test — AF-04 (FEAT-0001): alternative flow de control de acceso.
 * Test ID: unit-test:FEAT-0001-af004.
 *
 * <p>Cubre los 3 caminos de AF-04 y sus AC asociados:
 * <ul>
 *   <li>rol ADMIN en Context → el downstream pasa (sin error) — prerequisito
 *       de AC-001 (control de acceso OK para el Main Flow)</li>
 *   <li>rol VIEWER → {@code InsufficientRoleException("INSUFFICIENT_ROLE")} — AC-006</li>
 *   <li>sin rol en Context (sin header Authorization) →
 *       {@code InsufficientRoleException("UNAUTHENTICATED")} — AC-007</li>
 * </ul>
 *
 * <p>Verifica el comportamiento real del SUT reactivo ({@link RolGuard#requireAdmin}
 * + {@link SecurityConfig#currentRol()} sobre Reactor Context) usando
 * {@link StepVerifier} sin levantar Spring, y el mapeo a 401/403 vía
 * {@link GlobalErrorHandler} (instancia directa, sin Spring ni WebTestClient:
 * sus handlers son plain {@link ResponseEntity} factories). Sin infraestructura
 * pesada (Testcontainers/Spring) — eso ya lo cubre {@code FEAT0001MainFlowIT}.
 */
class AF04RolGuardTest {

    private static final String DOWNSTREAM_VALUE = "ok";

    /** Construye el chain con el rol en el Reactor Context (upstream). */
    private static Mono<String> guarded(Rol rol) {
        Mono<String> core = RolGuard.requireAdmin(Mono.just(DOWNSTREAM_VALUE));
        // rol == null → no enriquecemos el Context (caso UNAUTHENTICATED).
        // Escribimos río arriba del guard: el Context viaja hacia el
        // Mono.deferContextual que SecurityConfig.currentRol() usa para leerlo.
        Context ctx = SecurityConfig.WriteRolContext.with(rol); // ctx == Context.empty() si rol==null
        return ctx.hasKey("wrsensor.rol")
                ? core.contextWrite(ctx)
                : core;
    }

    /** AC-001 prerequisite / AF-04 happy: rol ADMIN → el downstream pasa. */
    @Test
    @DisplayName("AF-04: rol ADMIN en Context → downstream emite el valor (pasa)")
    void testAF04_adminPasa() {
        StepVerifier.create(guarded(Rol.ADMIN))
                .expectNext(DOWNSTREAM_VALUE)
                .verifyComplete();
    }

    /** AF-04 / AC-006: rol VIEWER → InsufficientRoleException(INSUFFICIENT_ROLE). */
    @Test
    @DisplayName("AF-04 / AC-006: rol VIEWER → InsufficientRoleException(INSUFFICIENT_ROLE)")
    void testAF04_viewer403InsufficientRole() {
        StepVerifier.create(guarded(Rol.VIEWER))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InsufficientRoleException.class);
                    assertThat(((InsufficientRoleException) err).code).isEqualTo("INSUFFICIENT_ROLE");
                });
    }

    /** AF-04 / AC-007: sin rol en Context → InsufficientRoleException(UNAUTHENTICATED). */
    @Test
    @DisplayName("AF-04 / AC-007: sin rol en Context → InsufficientRoleException(UNAUTHENTICATED)")
    void testAF04_sinAuth401Unauthenticated() {
        // guarded(null) → WriteRolContext.with(null) devuelve Context.empty()
        // → currentRol() emite Mono.empty() → switchIfEmpty lanza UNAUTHENTICATED.
        StepVerifier.create(guarded(null))
                .verifyErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InsufficientRoleException.class);
                    assertThat(((InsufficientRoleException) err).code).isEqualTo("UNAUTHENTICATED");
                });
    }

    /** AF-04 / AC-006: el código INSUFFICIENT_ROLE mapea a 403 FORBIDDEN. */
    @Test
    @DisplayName("AF-04 / AC-006: INSUFFICIENT_ROLE → 403 FORBIDDEN (GlobalErrorHandler)")
    void testAF04_insufficientRoleMapeaA403() {
        ResponseEntity<Map<String, Object>> resp = new GlobalErrorHandler().handleAuth(
                new InsufficientRoleException("INSUFFICIENT_ROLE", "rol ADMIN requerido"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("INSUFFICIENT_ROLE");
    }

    /** AF-04 / AC-007: el código UNAUTHENTICATED mapea a 401 UNAUTHORIZED. */
    @Test
    @DisplayName("AF-04 / AC-007: UNAUTHENTICATED → 401 UNAUTHORIZED (GlobalErrorHandler)")
    void testAF04_unauthMapeaA401() {
        ResponseEntity<Map<String, Object>> resp = new GlobalErrorHandler().handleAuth(
                new InsufficientRoleException("UNAUTHENTICATED", "Authorization requerido"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("UNAUTHENTICATED");
    }
}
