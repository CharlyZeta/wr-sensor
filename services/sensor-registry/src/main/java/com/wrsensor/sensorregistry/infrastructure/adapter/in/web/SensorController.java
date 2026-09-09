package com.wrsensor.sensorregistry.infrastructure.adapter.in.web;

import com.wrsensor.sensorregistry.application.port.in.CreateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.in.DeactivateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.in.GetSensorDetailUseCase;
import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase;
import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase.ListSensorsQuery;
import com.wrsensor.sensorregistry.application.port.in.UpdateSensorUseCase;
import com.wrsensor.sensorregistry.domain.model.InvalidSensorIdException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorListResponse;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorRequest;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorResponse;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.dto.SensorUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Adapter in (web): controlador WebFlux para /api/sensores.
 *
 * <p>POST /api/sensores  — creacion (FEAT-0001, requiere ADMIN).
 * GET  /api/sensores    — listado keyset (FEAT-0002, requiere ADMIN o VIEWER).
 */
@RestController
@RequestMapping("/api/sensores")
public class SensorController {

    private final CreateSensorUseCase createSensorUseCase;
    private final ListSensorsUseCase listSensorsUseCase;
    private final GetSensorDetailUseCase getSensorDetailUseCase;
    private final UpdateSensorUseCase updateSensorUseCase;
    private final DeactivateSensorUseCase deactivateSensorUseCase;

    public SensorController(CreateSensorUseCase createSensorUseCase,
                            ListSensorsUseCase listSensorsUseCase,
                            GetSensorDetailUseCase getSensorDetailUseCase,
                            UpdateSensorUseCase updateSensorUseCase,
                            DeactivateSensorUseCase deactivateSensorUseCase) {
        this.createSensorUseCase = createSensorUseCase;
        this.listSensorsUseCase = listSensorsUseCase;
        this.getSensorDetailUseCase = getSensorDetailUseCase;
        this.updateSensorUseCase = updateSensorUseCase;
        this.deactivateSensorUseCase = deactivateSensorUseCase;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SensorResponse> createSensor(ServerWebExchange exchange, @Valid @RequestBody Mono<SensorRequest> request) {
        // AF-04 / AC-006 / AC-007: rol ADMIN requerido (guard reactivo sobre Context).
        return RolGuard.requireAdmin(exchange,
                request.map(SensorController::toCommand)
                        .flatMap(createSensorUseCase::createSensor)
                        .map(SensorResponse::from)
        );
    }

    /**
     * GET /api/sensores — listado paginado keyset (FEAT-0002 Main Flow).
     * AF-01/AC-003 sin auth → 401; AF-02/AC-004 rol fuera de {ADMIN,VIEWER} → 403.
     * Limit ausente → default 100 (AF-03); fuera de [1,1000] → 400 SENSOR_INVALID_LIMIT
     * (AF-04/05/06, AC-006/007); cursor malformado → 400 SENSOR_INVALID_CURSOR (AF-07, AC-010).
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SensorListResponse> listSensors(ServerWebExchange exchange,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return RolGuard.requireReader(exchange,
                listSensorsUseCase.list(new ListSensorsQuery(limit, cursor))
                        .map(SensorListResponse::from)
        );
    }

    /**
     * GET /api/sensores/{id} — detalle de un sensor (FEAT-0003 Main Flow).
     * AF-01/AC-003 sin auth → 401; AF-02/AC-004 rol fuera de {ADMIN,VIEWER} → 403.
     * AF-03/AC-005 id UUID valido inexistente → 404 SENSOR_NOT_FOUND.
     * AF-04/AC-006 id malformado → 400 SENSOR_INVALID_ID (BR-001).
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SensorResponse> getSensor(ServerWebExchange exchange, @PathVariable String id) {
        return RolGuard.requireReader(exchange,
                getSensorDetailUseCase.getById(parseUuid(id))
                        .map(SensorResponse::from)
        );
    }

    /** BR-001: el path {id} debe ser UUID; si no, InvalidSensorIdException → 400 SENSOR_INVALID_ID. */
    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new InvalidSensorIdException();
        }
    }

    /**
     * PUT /api/sensores/{id} — edicion de configuracion (FEAT-0004 Main Flow).
     * AF-01/AC-003 sin auth → 401; AF-02/AC-002 rol != ADMIN → 403; AF-03/AC-005
     * id malformado → 400 SENSOR_INVALID_ID; AF-04/AC-004 inexistente → 404;
     * AF-05 validaciones de config → 400 con code de dominio; AF-06 body invalido
     * (faltantes / campos inmutables) → 400 SENSOR_INVALID_REQUEST.
     */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<SensorResponse> updateSensor(ServerWebExchange exchange, @PathVariable String id,
                                             @Valid @RequestBody Mono<SensorUpdateRequest> request) {
        UUID sensorId = parseUuid(id);
        return RolGuard.requireAdmin(exchange,
                request.flatMap(r -> updateSensorUseCase.update(sensorId, toUpdateCommand(r)))
                        .map(SensorResponse::from)
        );
    }

    private static UpdateSensorUseCase.UpdateSensorCommand toUpdateCommand(SensorUpdateRequest r) {
        return new UpdateSensorUseCase.UpdateSensorCommand(
                r.estado(),
                r.histeresis(),
                r.frecuenciaReporteSegundos(),
                toRango(r.rangoNormal()),
                toRango(r.rangoWarning()),
                toRango(r.rangoCritical())
        );
    }

    /**
     * DELETE /api/sensores/{id} — baja logica (FEAT-0005 Main Flow): estado →
     * INACTIVO, nunca DELETE fisico. AF-01/AC-003 sin auth → 401; AF-02/AC-002
     * rol != ADMIN → 403; AF-03/AC-005 id malformado → 400 SENSOR_INVALID_ID;
     * AF-04/AC-004 inexistente → 404; AF-05/AC-006 ya INACTIVO → 204 idempotente.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivateSensor(ServerWebExchange exchange, @PathVariable String id) {
        return RolGuard.requireAdmin(exchange,
                deactivateSensorUseCase.deactivate(parseUuid(id))
        );
    }

    private static CreateSensorUseCase.CreateSensorCommand toCommand(SensorRequest r) {
        return new CreateSensorUseCase.CreateSensorCommand(
                r.codigo(),
                r.nombre(),
                r.tipo(),
                r.latitud(),
                r.longitud(),
                r.unidadMedida(),
                r.estado(),
                r.histeresis(),
                r.frecuenciaReporteSegundos() != null ? r.frecuenciaReporteSegundos() : 0,
                toRango(r.rangoNormal()),
                toRango(r.rangoWarning()),
                toRango(r.rangoCritical())
        );
    }

    private static Rango toRango(SensorRequest.RangoDto dto) {
        return dto == null ? null : new Rango(dto.min(), dto.max());
    }
}

