package com.wrsensor.queryapi.infrastructure.adapter.in.web;

import com.wrsensor.queryapi.application.service.ResumenService;
import com.wrsensor.queryapi.domain.ResumenSensor;
import com.wrsensor.queryapi.infrastructure.security.QueryAuthGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adapter in (web): resumen de sensores para el mapa (FEAT-0008 BR-005).
 *
 * <p>Roles {ADMIN, VIEWER} (mismo criterio que el resto de las lecturas). Devuelve un **array** con
 * todos los sensores y su última lectura; es la fuente única del mapa y del listado del SPA, y no
 * cambia el contrato de los endpoints existentes.</p>
 */
@RestController
public class ResumenController {

    private final ResumenService resumen;

    public ResumenController(ResumenService resumen) {
        this.resumen = resumen;
    }

    @GetMapping("/api/sensores/resumen")
    public Mono<List<SensorResumenDto>> resumen(ServerWebExchange exchange) {
        return QueryAuthGuard.requireReader(exchange,
                resumen.resumen().map(lista -> lista.stream().map(ResumenController::toDto).toList()));
    }

    private static SensorResumenDto toDto(ResumenSensor s) {
        return new SensorResumenDto(s.id(), s.codigo(), s.nombre(), s.tipo(), s.latitud(),
                s.longitud(), s.estado(), s.unidadMedida(), s.ultimaLectura() == null ? null
                        : new UltimaLecturaDto(s.ultimaLectura().valor(), s.ultimaLectura().timestamp(),
                                s.ultimaLectura().severidad(), s.ultimaLectura().calidad()));
    }

    /** Fila del resumen (BR-005): metadata + última lectura (null si el sensor nunca reportó). */
    public record SensorResumenDto(UUID id, String codigo, String nombre, String tipo,
                                   BigDecimal latitud, BigDecimal longitud, String estado,
                                   String unidadMedida, UltimaLecturaDto ultimaLectura) {}

    public record UltimaLecturaDto(BigDecimal valor, Instant timestamp, String severidad,
                                   String calidad) {}
}
