package com.wrsensor.queryapi.infrastructure.adapter.in.web;

import com.wrsensor.queryapi.application.service.QueryService;
import com.wrsensor.queryapi.application.service.QueryService.Pagina;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.QueryException;
import com.wrsensor.queryapi.infrastructure.security.QueryAuthGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adapter in (web): lecturas (FEAT-0013). Roles {ADMIN, VIEWER} (BR-006).
 */
@RestController
@RequestMapping("/api/sensores/{id}")
public class LecturasController {

    private final QueryService query;

    public LecturasController(QueryService query) {
        this.query = query;
    }

    @GetMapping("/lecturas")
    public Mono<HistoricoResponse> historico(ServerWebExchange exchange, @PathVariable String id,
                                             @RequestParam String desde, @RequestParam String hasta,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String limit) {
        UUID sensorId = parseUuid(id);
        Instant desdeI = parseInstant(desde);
        Instant hastaI = parseInstant(hasta);
        Integer limitI = limit == null ? null : parseLimit(limit);
        return QueryAuthGuard.requireReader(exchange,
                query.historico(sensorId, desdeI, hastaI, cursor, limitI)
                        .map(p -> toResponse(p)));
    }

    @GetMapping("/actual")
    public Mono<LecturaDto> actual(ServerWebExchange exchange, @PathVariable String id) {
        UUID sensorId = parseUuid(id);
        return QueryAuthGuard.requireReader(exchange,
                query.ultima(sensorId).map(LecturasController::toDto));
    }

    private static HistoricoResponse toResponse(Pagina p) {
        return new HistoricoResponse(p.items().stream().map(LecturasController::toDto).toList(), p.nextCursor());
    }

    private static LecturaDto toDto(LecturaConsulta l) {
        return new LecturaDto(l.timestamp(), l.valor(), l.unidadMedida(), l.severidad());
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new QueryException(QueryException.SENSOR_INVALID_ID, "id malformado");
        }
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            throw new QueryException(QueryException.INVALID_RANGE, "timestamp invalido");
        }
    }

    private static Integer parseLimit(String s) {
        try {
            return Integer.valueOf(s);
        } catch (RuntimeException e) {
            throw new QueryException(QueryException.SENSOR_INVALID_LIMIT, "limit invalido");
        }
    }

    public record HistoricoResponse(List<LecturaDto> items, String nextCursor) {}

    public record LecturaDto(Instant timestamp, BigDecimal valor, String unidadMedida, String severidad) {}
}
