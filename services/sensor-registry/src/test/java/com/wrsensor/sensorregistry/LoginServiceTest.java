package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.out.FindUserByEmailPort;
import com.wrsensor.sensorregistry.application.port.out.PasswordVerifier;
import com.wrsensor.sensorregistry.application.port.out.TokenIssuer;
import com.wrsensor.sensorregistry.application.port.in.LoginUseCase;
import com.wrsensor.sensorregistry.application.service.LoginService;
import com.wrsensor.sensorregistry.domain.model.InvalidCredentialsException;
import com.wrsensor.sensorregistry.domain.model.Rol;
import com.wrsensor.sensorregistry.domain.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0006 BR-002 y AC-001..AC-004 (capa de aplicacion con fakes).
 * Test IDs: unit-test:FEAT-0006-br002, assertion:FEAT-0006-ac00X.
 *
 * <p>LoginService con fakes del port: la verificacion de password y la emision de
 * token son ports; aca se verifica la orquestacion: email normalizado, 401 unico
 * e indistinguible para email inexistente y password incorrecta, y exito con el
 * token/rol/expiraEnSegundos del emisor.
 */
class LoginServiceTest {

    private static final String HASH = "$2a$10$hashdeejemplo";
    private static final String PASSWORD_OK = "Admin123!";
    private static final Usuario ADMIN = new Usuario(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "admin@wrsensor.local", HASH, Rol.ADMIN);

    /** Fake del port de busqueda: devuelve el usuario o vacio. */
    private static final class FakeFindUser implements FindUserByEmailPort {
        Usuario user;
        String lastEmail;

        @Override
        public Mono<Usuario> findByEmail(String email) {
            lastEmail = email;
            return user == null ? Mono.empty() : Mono.just(user);
        }
    }

    /** Fake del verificador: ok solo si raw == PASSWORD_OK. */
    private static final class FakeVerifier implements PasswordVerifier {
        @Override
        public Mono<Boolean> matches(String rawPassword, String passwordHash) {
            return Mono.just(PASSWORD_OK.equals(rawPassword) && HASH.equals(passwordHash));
        }
    }

    /** Fake del emisor: token fijo + 3600s. */
    private static final class FakeIssuer implements TokenIssuer {
        @Override
        public IssuedToken issue(Usuario usuario) {
            return new IssuedToken("fake-token-" + usuario.rol(), 3600L);
        }
    }

    private record Harness(FakeFindUser find, LoginService svc) {}

    private static Harness harness(Usuario user) {
        FakeFindUser find = new FakeFindUser();
        find.user = user;
        return new Harness(find, new LoginService(find, new FakeVerifier(), new FakeIssuer()));
    }

    @Test
    @DisplayName("AC-001: credenciales validas (ADMIN) → token + rol ADMIN + expiraEnSegundos")
    void testAC001_loginAdminOk() {
        Harness h = harness(ADMIN);
        StepVerifier.create(h.svc().login(new LoginUseCase.LoginCommand("Admin@Wrsensor.Local", PASSWORD_OK)))
                .assertNext(r -> {
                    assertThat(r.token()).isEqualTo("fake-token-ADMIN");
                    assertThat(r.rol()).isEqualTo(Rol.ADMIN);
                    assertThat(r.expiraEnSegundos()).isEqualTo(3600L);
                })
                .verifyComplete();
        // BR-002: email normalizado a minusculas antes de buscar.
        assertThat(h.find().lastEmail).isEqualTo("admin@wrsensor.local");
    }

    @Test
    @DisplayName("AC-002: credenciales validas (VIEWER) → token + rol VIEWER")
    void testAC002_loginViewerOk() {
        Usuario viewer = new Usuario(ADMIN.id(), "viewer@wrsensor.local", HASH, Rol.VIEWER);
        Harness h = harness(viewer);
        StepVerifier.create(h.svc().login(new LoginUseCase.LoginCommand("viewer@wrsensor.local", PASSWORD_OK)))
                .assertNext(r -> assertThat(r.rol()).isEqualTo(Rol.VIEWER))
                .verifyComplete();
    }

    @Test
    @DisplayName("AC-003: password incorrecta → InvalidCredentialsException (INVALID_CREDENTIALS)")
    void testAC003_passwordIncorrecta() {
        Harness h = harness(ADMIN);
        StepVerifier.create(h.svc().login(new LoginUseCase.LoginCommand("admin@wrsensor.local", "wrong")))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(InvalidCredentialsException.class);
                    assertThat(((InvalidCredentialsException) err).code()).isEqualTo("INVALID_CREDENTIALS");
                })
                .verify();
    }

    @Test
    @DisplayName("AC-004: email inexistente → misma InvalidCredentialsException (indistinguible de AC-003)")
    void testAC004_emailInexistente() {
        Harness h = harness(null); // sin usuario
        StepVerifier.create(h.svc().login(new LoginUseCase.LoginCommand("nadie@wrsensor.local", PASSWORD_OK)))
                .expectErrorSatisfies(err -> assertThat(err).isInstanceOf(InvalidCredentialsException.class))
                .verify();
    }
}

