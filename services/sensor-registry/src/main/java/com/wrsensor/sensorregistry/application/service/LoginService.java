package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.LoginUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindUserByEmailPort;
import com.wrsensor.sensorregistry.application.port.out.PasswordVerifier;
import com.wrsensor.sensorregistry.application.port.out.TokenIssuer;
import com.wrsensor.sensorregistry.domain.model.InvalidCredentialsException;
import com.wrsensor.sensorregistry.domain.model.Usuario;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * Implementacion del caso de uso "Login" (Main Flow FEAT-0006).
 *
 * <p>BR-002: email normalizado a minusculas; email inexistente y password
 * incorrecta producen la MISMA excepcion {@link InvalidCredentialsException}
 * (no se enumeran cuentas). BR-001: la verificacion es contra el hash BCrypt
 * via port (nunca password en claro). El token lo emite el port {@link TokenIssuer}
 * (BR-003). Patron de los otros servicios — aplicacion sin Spring.
 */
public class LoginService implements LoginUseCase {

    private final FindUserByEmailPort findUserPort;
    private final PasswordVerifier passwordVerifier;
    private final TokenIssuer tokenIssuer;

    public LoginService(FindUserByEmailPort findUserPort,
                        PasswordVerifier passwordVerifier,
                        TokenIssuer tokenIssuer) {
        this.findUserPort = findUserPort;
        this.passwordVerifier = passwordVerifier;
        this.tokenIssuer = tokenIssuer;
    }

    @Override
    public Mono<LoginResult> login(LoginCommand command) {
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        return Mono.defer(() -> findUserPort.findByEmail(email))
                .flatMap(user -> checkPassword(user, command.password()))
                .switchIfEmpty(Mono.error(new InvalidCredentialsException()));
    }

    private Mono<LoginResult> checkPassword(Usuario user, String rawPassword) {
        return passwordVerifier.matches(rawPassword, user.passwordHash())
                .flatMap(ok -> ok ? Mono.just(issue(user)) : Mono.error(new InvalidCredentialsException()));
    }

    private LoginResult issue(Usuario user) {
        TokenIssuer.IssuedToken issued = tokenIssuer.issue(user);
        return new LoginResult(issued.token(), user.rol(), issued.expiresInSeconds());
    }
}
