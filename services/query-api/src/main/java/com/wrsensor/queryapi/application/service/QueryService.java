package com.wrsensor.queryapi.application.service;

import com.wrsensor.queryapi.application.port.LecturasPort;
import com.wrsensor.queryapi.domain.CursorLecturas;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.QueryException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orquestacion de consultas (FEAT-0013 BR-001/BR-002/BR-004). Validacion de
 * limit/rango + paginacion keyset (limit+1 → nextCursor) + ultima lectura.
 * Aplicacion sin Spring.
 */
public class QueryService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    public record Pagina(List<LecturaConsulta> items, String nextCursor) {}

    private final LecturasPort lecturas;

    public QueryService(LecturasPort lecturas) {
        this.lecturas = lecturas;
    }

    public Mono<Pagina> historico(UUID sensorId, Instant desde, Instant hasta,
                                  String cursorStr, Integer limitRaw) {
        return Mono.defer(() -> {
            int limit = validarLimit(limitRaw);
            validarRango(desde, hasta);
            Instant after = (cursorStr == null || cursorStr.isBlank())
                    ? null : CursorLecturas.decode(cursorStr);
            return lecturas.listar(sensorId, desde, hasta, after, limit + 1)
                    .collectList()
                    .map(rows -> pagina(rows, limit));
        });
    }

    public Mono<LecturaConsulta> ultima(UUID sensorId) {
        return lecturas.ultima(sensorId)
                .switchIfEmpty(Mono.error(new QueryException(
                        QueryException.SENSOR_NOT_FOUND, "sin lecturas para el sensor")));
    }

    private static Pagina pagina(List<LecturaConsulta> rows, int limit) {
        boolean hayMas = rows.size() > limit;
        List<LecturaConsulta> items = hayMas ? rows.subList(0, limit) : rows;
        String next = null;
        if (hayMas && !items.isEmpty()) {
            next = CursorLecturas.encode(items.get(items.size() - 1).timestamp());
        }
        return new Pagina(items, next);
    }

    private static int validarLimit(Integer raw) {
        int l = raw == null ? DEFAULT_LIMIT : raw;
        if (l < 1 || l > MAX_LIMIT) {
            throw new QueryException(QueryException.SENSOR_INVALID_LIMIT, "limit fuera de [1,1000]");
        }
        return l;
    }

    private static void validarRango(Instant desde, Instant hasta) {
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            throw new QueryException(QueryException.INVALID_RANGE, "rango invalido: desde<=hasta obligatorio");
        }
    }
}
