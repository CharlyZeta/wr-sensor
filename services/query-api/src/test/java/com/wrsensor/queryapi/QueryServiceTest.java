package com.wrsensor.queryapi;

import com.wrsensor.queryapi.application.port.LecturasPort;
import com.wrsensor.queryapi.application.service.QueryService;
import com.wrsensor.queryapi.application.service.QueryService.Pagina;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.QueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0013 QueryService (fake del port con corte por cursor).
 * Cubre AC-001..AC-003, AC-005/006 (nivel servicio) y las validaciones AC-004.
 */
class QueryServiceTest {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final Instant BASE = Instant.parse("2026-09-09T12:00:00Z");

    /** Filas t0..t4 (t4 la mas reciente). */
    private static List<LecturaConsulta> filas() {
        List<LecturaConsulta> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            out.add(new LecturaConsulta(ID, BASE.plusSeconds(i), new BigDecimal(i + 1),
                    "METROS", "NORMAL", "OK"));
        }
        return out;
    }

    /** Fake: filas pre-ordenadas DESC y corta por afterTs (semantica keyset real). */
    private static final class FakePort implements LecturasPort {
        final List<LecturaConsulta> filas;

        FakePort(List<LecturaConsulta> filas) {
            this.filas = filas.stream()
                    .sorted(Comparator.comparing(LecturaConsulta::timestamp).reversed())
                    .toList();
        }

        @Override
        public Flux<LecturaConsulta> listar(UUID sensorId, Instant desde, Instant hasta,
                                            Instant afterTs, int limit) {
            List<LecturaConsulta> out = new ArrayList<>();
            for (LecturaConsulta l : filas) {
                if (l.timestamp().isBefore(desde) || l.timestamp().isAfter(hasta)) continue;
                if (afterTs != null && !l.timestamp().isBefore(afterTs)) continue;
                out.add(l);
            }
            return Flux.fromIterable(out.size() > limit ? out.subList(0, limit) : out);
        }

        @Override
        public Mono<LecturaConsulta> ultima(UUID sensorId) {
            return filas.stream().findFirst().map(Mono::just).orElse(Mono.empty());
        }
    }

    private final QueryService service = new QueryService(new FakePort(filas()));

    private static Instant desde() {
        return BASE.minusSeconds(10);
    }

    private static Instant hasta() {
        return BASE.plusSeconds(10);
    }

    @Test
    @DisplayName("AC-001: pagina inicial de 2 con nextCursor")
    void ac001_paginaInicial() {
        Pagina p = service.historico(ID, desde(), hasta(), null, 2).block();
        assertThat(p.items()).hasSize(2);
        assertThat(p.items().get(0).timestamp()).isEqualTo(BASE.plusSeconds(4)); // DESC
        assertThat(p.items().get(1).timestamp()).isEqualTo(BASE.plusSeconds(3));
        assertThat(p.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("AC-002: paginando con el cursor no hay duplicados ni saltos")
    void ac002_paginacionCompleta() {
        Pagina p1 = service.historico(ID, desde(), hasta(), null, 2).block();
        Pagina p2 = service.historico(ID, desde(), hasta(), p1.nextCursor(), 2).block();
        Pagina p3 = service.historico(ID, desde(), hasta(), p2.nextCursor(), 2).block();

        assertThat(p2.items().get(0).timestamp()).isEqualTo(BASE.plusSeconds(2));
        assertThat(p3.items()).hasSize(1);
        assertThat(p3.items().get(0).timestamp()).isEqualTo(BASE.plusSeconds(0));
        assertThat(p3.nextCursor()).isNull();

        List<Instant> todos = new ArrayList<>();
        p1.items().forEach(l -> todos.add(l.timestamp()));
        p2.items().forEach(l -> todos.add(l.timestamp()));
        p3.items().forEach(l -> todos.add(l.timestamp()));
        assertThat(todos).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    @DisplayName("AF-05 / AC-003: sin datos en el rango → lista vacia sin nextCursor")
    void ac003_sinDatos() {
        Pagina p = service.historico(ID, BASE.plusSeconds(100), BASE.plusSeconds(200), null, null).block();
        assertThat(p.items()).isEmpty();
        assertThat(p.nextCursor()).isNull();
    }

    @Test
    @DisplayName("AF-04 / AC-004: validaciones (limit y rango)")
    void ac004_validaciones() {
        StepVerifier.create(service.historico(ID, desde(), hasta(), null, 1001))
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.SENSOR_INVALID_LIMIT))
                .verify();
        StepVerifier.create(service.historico(ID, hasta(), desde(), null, null)) // desde > hasta
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.INVALID_RANGE))
                .verify();
    }

    @Test
    @DisplayName("AC-005/AC-006 (servicio): ultima lectura y sin lecturas → SENSOR_NOT_FOUND")
    void ac005_006_ultima() {
        assertThat(service.ultima(ID).block().timestamp()).isEqualTo(BASE.plusSeconds(4));

        QueryService vacio = new QueryService(new FakePort(List.of()));
        StepVerifier.create(vacio.ultima(ID))
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.SENSOR_NOT_FOUND))
                .verify();
    }
}

