package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.application.port.in.LoginUseCase;
import com.wrsensor.sensorregistry.application.port.in.LoginUseCase.LoginCommand;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.LoginRequest;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Adapter in (web): POST /api/auth/login (Main Flow FEAT-0006). Publico (sin
 * guard): el login no requiere token previo. La validacion estructural del body
 * (email formato / password no vacia) cae en Bean Validation → 400
 * SENSOR_INVALID_REQUEST (AF-01/AF-02, AC-005).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginUseCase loginUseCase;

    public AuthController(LoginUseCase loginUseCase) {
        this.loginUseCase = loginUseCase;
    }

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LoginResponse> login(@Valid @RequestBody Mono<LoginRequest> request) {
        return request.flatMap(r -> loginUseCase.login(new LoginCommand(r.email(), r.password())))
                .map(result -> new LoginResponse(result.token(), result.rol().name(), result.expiraEnSegundos()));
    }
}
