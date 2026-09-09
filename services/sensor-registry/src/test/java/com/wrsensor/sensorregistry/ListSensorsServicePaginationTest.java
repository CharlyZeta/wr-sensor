package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase;
import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase.ListSensorsQuery;
import com.wrsensor.sensorregistry.application.port.in.ListSensorsUseCase.SensorPage;
import com.wrsensor.sensorregistry.application.port.out.ListSensorsPort;
import com.wrsensor.sensorregistry.application.service.CursorCodec;
import com.wrsensor.sensorregistry.application.service.ListSensorsService;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.InvalidListCursorException;
import com.wrsensor.sensorregistry.domain.model.InvalidListLimitException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.TipoSensor;
import com.wrsensor.sensorregistry.domain.model.UnidadMedida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — FEAT-0002 BR-001..BR-005 + assertions AC-001, AC-005, AC-008,
 * AC-009, AC-011 y AC-012 (a nivel servicio/aplicacion, con fakes del port out).
 * Test IDs: unit-test:FEAT-0002-br00X / assertion:FEAT-0002-acXXX.
 *
 * <p>Stack (stack.md): hexagonal estricto — los tests de aplicacion usan fakes
 * del port (no mocks de framework), cero Spring. El SUT es
 * {@link ListSensorsService}; el port fake implementa la semantica keyset real
 * (recorte por cursor {@code (fechaInstalacion, id)}) para poder verificar el
 * encadenamiento entre paginas sin infraestructura.
 *
 * <p>El orden DESC por fechaInstalacion con desempate ASC por id (BR-004) vive
 * en el adapter SQL (verificado end-to-end por {@code FEAT0002MainFlowIT}); aqui
 * se verifica que el servicio pagina por keyset, respeta el orden entregado por
 * el port, aplica default/max de limit y emite nextCursor solo cuando hay mas.
 */
class ListSensorsServicePaginationTest {

    // ============ fixtures ============

    private static final BigDecimal CERO = new BigDecimal("0");
    private static final BigDecimal DOS = new BigDecimal("2");
    private static final BigDecimal CUATRO = new BigDecimal("4");
    private static final BigDecimal SEIS = new BigDecimal("6");
    private static final BigDecimal OCHO = new BigDecimal("8");
    private static final BigDecimal DIEZ = new BigDecimal("10");
    private static final BigDecimal HISTERESIS = new BigDecimal("0.5");
    private static final BigDecimal LAT = new BigDecimal("-29.15");
    private static final BigDecimal LON = new BigDecimal("-59.65");

    private static final Instant FEB1 = Instant.parse("2024-02-01T00:00:00Z");
    private static final Instant MAR1 = Instant.parse("2024-03-01T00:00:00Z");
    private static final Instant APR1 = Instant.parse("2024-04-01T00:00:00Z");
    private static final Instant MAY1 = Instant.parse("2024-05-01T00:00:00Z");
    private static final Instant JUN1 = Instant.parse("2024-06-01T00:00:00Z");

    // Misma semilla que FEAT0002MainFlowIT: orden DESC fecha, desempate ASC id.
    // APR-A y APR-B comparten fecha (empate deliberado, BR-004/AC-012).
    private static final UUID ID_1 = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ID_3 = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ID_4 = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID ID_5 = UUID.fromString("00000000-0000-4000-8000-000000000005");
    private static final UUID ID_6 = UUID.fromString("00000000-0000-4000-8000-000000000006");

    private static Sensor sensor(String codigo, Instant fecha, UUID id) {
        return new Sensor(
                id, codigo, "seed", TipoSensor.RIO, LAT, LON, UnidadMedida.METROS,
                EstadoSensor.ACTIVO, HISTERESIS, 60, fecha,
                new Rango(CUATRO, SEIS), new Rango(DOS, OCHO), new Rango(CERO, DIEZ));
    }

    /** Port fake que entrega exactamente {@code rows} (ignora cursor/limit de recorte). */
    private static final class PassthroughPort implements ListSensorsPort {
        List<Sensor> rows = List.of();
        Instant lastFecha;
        UUID lastId;
        int lastLimit;

        @Override
        public Flux<Sensor> listAfter(Instant afterFecha, UUID afterId, int limit) {
            lastFecha = afterFecha;
            lastId = afterId;
            lastLimit = limit;
            return Flux.fromIterable(rows);
        }
    }

    /**
     * Port fake con semantica keyset real: filas pre-ordenadas (DESC fecha, ASC id);
     * {@code listAfter} recorta "posteriores al cursor" y devuelve hasta {@code limit}.
     * Espejo del predicado del adapter (fecha < afterFecha OR (fecha = afterFecha AND id > afterId)).
     */
    private static final class KeysetPort implements ListSensorsPort {
        final List<Sensor> all;
        Instant lastFecha;
        UUID lastId;
        int lastLimit;

        KeysetPort(List<Sensor> all) { this.all = all; }

        @Override
        public Flux<Sensor> listAfter(Instant afterFecha, UUID afterId, int limit) {
            lastFecha = afterFecha;
            lastId = afterId;
            lastLimit = limit;
            List<Sensor> out = new ArrayList<>();
            for (Sensor s : all) {
                if (afterFecha == null) { out.add(s); }
                else {
                    boolean after = s.fechaInstalacion().isBefore(afterFecha)
                            || (s.fechaInstalacion().equals(afterFecha) && s.id().compareTo(afterId) > 0);
                    if (after) out.add(s);
                }
            }
            return Flux.fromIterable(out.size() > limit ? out.subList(0, limit) : out);
        }
    }

    private static SensorPage list(ListSensorsPort port, Integer limit, String cursor) {
        return new ListSensorsService(port).list(new ListSensorsQuery(limit, cursor)).block();
    }

    private static List<Sensor> seedSeisConEmpate() {
        return List.of(
                sensor("S-JUN", JUN1, ID_1),
                sensor("S-MAY", MAY1, ID_2),
                sensor("S-APR-A", APR1, ID_3),
                sensor("S-APR-B", APR1, ID_4),
                sensor("S-MAR", MAR1, ID_5),
                sensor("S-FEB", FEB1, ID_6));
    }

    // ============ BR-001 ============

    @Test
    @DisplayName("BR-001: paginacion por keyset (cursor = (fecha,id) de la ultima fila), nunca OFFSET ni numero de pagina")
    void testBR001_keysetCursorSinOffset() {
        KeysetPort port = new KeysetPort(seedSeisConEmpate());
        SensorPage p1 = list(port, 3, null);
        assertThat(p1.items()).extracting(Sensor::codigo).containsExactly("S-JUN", "S-MAY", "S-APR-A");

        // La segunda pagina no pide "offset 3": pide filas posteriores al cursor de la ultima fila.
        SensorPage p2 = list(port, 3, p1.nextCursor());
        assertThat(port.lastFecha).isEqualTo(APR1);
        assertThat(port.lastId).isEqualTo(ID_3);
        assertThat(p2.items()).extracting(Sensor::codigo).containsExactly("S-APR-B", "S-MAR", "S-FEB");
    }

    // ============ BR-002 ============

    @Test
    @DisplayName("BR-002: limit ausente → default 100; el servicio pide limit+1 para detectar mas paginas")
    void testBR002_limitDefault100Pide101() {
        PassthroughPort port = new PassthroughPort();
        port.rows = muchosSensores(101);
        SensorPage page = list(port, null, null);

        assertThat(port.lastLimit).isEqualTo(101); // fetch = limit(default 100) + 1
        assertThat(page.items()).hasSize(100);
        assertThat(page.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("BR-002: limit fuera de [1,1000] → InvalidListLimitException (1001, 0, -3)")
    void testBR002_limitFueraDeRangoRechaza() {
        PassthroughPort port = new PassthroughPort();
        ListSensorsService svc = new ListSensorsService(port);
        for (int bad : new int[]{1001, 0, -3}) {
            StepVerifier.create(svc.list(new ListSensorsQuery(bad, null)))
                    .expectError(InvalidListLimitException.class)
                    .verify();
        }
    }

    @Test
    @DisplayName("BR-002: limit valido en rango se respeta (fetch = limit+1)")
    void testBR002_limitValido() {
        PassthroughPort port = new PassthroughPort();
        port.rows = muchosSensores(500);
        SensorPage page = list(port, 500, null);
        assertThat(port.lastLimit).isEqualTo(501);
        assertThat(page.items()).hasSize(500);
    }

    // ============ BR-003 ============

    @Test
    @DisplayName("BR-003: cursor opaco — roundtrip encode/decode de (fechaInstalacion, id)")
    void testBR003_cursorOpacoRoundtrip() {
        String cursor = CursorCodec.encode(JUN1, ID_1);
        CursorCodec.Cursor decoded = CursorCodec.decode(cursor);
        assertThat(decoded.fecha()).isEqualTo(JUN1);
        assertThat(decoded.id()).isEqualTo(ID_1);
        // Opacidad: no es texto plano legible del par (esta codificado).
        assertThat(cursor).doesNotContain("S-JUN").doesNotContain(ID_1.toString());
    }

    @Test
    @DisplayName("BR-003: cursor malformado / no reconocido → InvalidListCursorException (servicio)")
    void testBR003_cursorMalformadoAlServicio() {
        PassthroughPort port = new PassthroughPort();
        ListSensorsService svc = new ListSensorsService(port);
        StepVerifier.create(svc.list(new ListSensorsQuery(null, "no-es-un-cursor-valido")))
                .expectError(InvalidListCursorException.class)
                .verify();
    }

    @Test
    @DisplayName("BR-003: cursor null o blank → pagina inicial (sin filtro, port recibe afterFecha null)")
    void testBR003_cursorAusenteEsInicio() {
        PassthroughPort port = new PassthroughPort();
        port.rows = muchosSensores(5);
        list(port, null, null);
        assertThat(port.lastFecha).isNull();
        assertThat(port.lastId).isNull();

        port.lastFecha = MAY1; // reset del flag
        list(port, null, "");
        assertThat(port.lastFecha).as("cursor blank equivale a ausente").isNull();
    }

    // ============ BR-004 ============

    @Test
    @DisplayName("BR-004: el servicio conserva el orden del port (DESC fecha, ASC id) y corta sin reordenar")
    void testBR004_conservaOrdenDeterministico() {
        KeysetPort port = new KeysetPort(seedSeisConEmpate());
        SensorPage page = list(port, null, null); // limit default 100 > 6 → todo, sin nextCursor
        assertThat(page.items()).extracting(Sensor::codigo)
                .containsExactly("S-JUN", "S-MAY", "S-APR-A", "S-APR-B", "S-MAR", "S-FEB");
        assertThat(page.nextCursor()).isNull();
    }

    // ============ BR-005 ============

    @Test
    @DisplayName("BR-005: nextCursor presente solo si hay mas filas (101 → presente; 100 → ausente)")
    void testBR005_nextCursorSoloSiHayMas() {
        PassthroughPort port = new PassthroughPort();

        port.rows = muchosSensores(101);
        assertThat(list(port, null, null).nextCursor()).isNotNull();

        port.rows = muchosSensores(100);
        assertThat(list(port, null, null).nextCursor()).isNull();

        port.rows = List.of();
        assertThat(list(port, null, null).nextCursor()).isNull();
    }

    // ============ AC-001 ============

    @Test
    @DisplayName("AC-001: >= 2 paginas, sin cursor/limit → exactamente 100 items (default) ordenados + nextCursor")
    void testAC001_paginaDefault100ConNextCursor() {
        KeysetPort port = new KeysetPort(descendentes(101));
        SensorPage page = list(port, null, null);

        assertThat(page.items()).hasSize(100);
        assertThat(page.items().get(0).codigo()).isEqualTo("S-1"); // mas reciente primero (orden DESC)
        assertThat(page.nextCursor()).isNotNull();
    }

    // ============ AC-005 ============

    @Test
    @DisplayName("AC-005: exactamente 100 sensores y limit ausente → 100 items y sin nextCursor")
    void testAC005_limitDefaultConCienExactos() {
        PassthroughPort port = new PassthroughPort();
        port.rows = muchosSensores(100);
        SensorPage page = list(port, null, null);
        assertThat(page.items()).hasSize(100);
        assertThat(page.nextCursor()).isNull();
    }

    // ============ AC-008 ============

    @Test
    @DisplayName("AC-008: con cursor de la pagina anterior, devuelve la siguiente pagina (filas posteriores al par fecha,id)")
    void testAC008_siguientePaginaDesdeCursor() {
        KeysetPort port = new KeysetPort(seedSeisConEmpate());
        SensorPage p1 = list(port, 3, null);
        assertThat(p1.nextCursor()).isNotNull();

        SensorPage p2 = list(port, 3, p1.nextCursor());
        assertThat(p2.items()).extracting(Sensor::codigo).containsExactly("S-APR-B", "S-MAR", "S-FEB");
    }

    // ============ AC-009 ============

    @Test
    @DisplayName("AC-009: cursor apuntando al final → lista vacia o menor a limit y sin nextCursor")
    void testAC009_cursorEnElFinalSinNextCursor() {
        KeysetPort port = new KeysetPort(seedSeisConEmpate());
        String ultimoCursor = CursorCodec.encode(FEB1, ID_6); // S-FEB es la ultima fila
        SensorPage page = list(port, 3, ultimoCursor);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // ============ AC-011 ============

    @Test
    @DisplayName("AC-011: sin sensores registrados → lista vacia y sin nextCursor")
    void testAC011_sinSensoresListaVacia() {
        PassthroughPort port = new PassthroughPort();
        port.rows = List.of();
        SensorPage page = list(port, null, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // ============ AC-012 ============

    @Test
    @DisplayName("AC-012: empate de fechaInstalacion entre paginas — sin duplicados ni saltos (desempate por id)")
    void testAC012_empateSinDuplicadosNiSaltos() {
        KeysetPort port = new KeysetPort(seedSeisConEmpate());
        SensorPage p1 = list(port, 3, null);
        SensorPage p2 = list(port, 3, p1.nextCursor());

        // El corte cae dentro del grupo APR (empate). P1 termina en APR-A (id menor),
        // P2 arranca en APR-B: nada se duplica ni se saltea.
        assertThat(p1.items()).extracting(Sensor::codigo).containsExactly("S-JUN", "S-MAY", "S-APR-A");
        assertThat(p2.items()).extracting(Sensor::codigo).containsExactly("S-APR-B", "S-MAR", "S-FEB");
        List<String> todos = new ArrayList<>();
        p1.items().forEach(s -> todos.add(s.codigo()));
        p2.items().forEach(s -> todos.add(s.codigo()));
        assertThat(todos).containsExactlyInAnyOrder("S-JUN", "S-MAY", "S-APR-A", "S-APR-B", "S-MAR", "S-FEB");
        assertThat(todos).doesNotHaveDuplicates();
    }

    // ============ helpers ============

    /** N sensores pre-ordenados DESC por fecha (S-1 el mas reciente, S-N el mas antiguo). */
    private static List<Sensor> descendentes(int n) {
        List<Sensor> out = new ArrayList<>();
        Instant base = Instant.parse("2024-12-01T00:00:00Z");
        for (int i = 1; i <= n; i++) {
            out.add(sensor("S-" + i, base.minusSeconds((long) (i - 1) * 3600), UUID.nameUUIDFromBytes(("s" + i).getBytes())));
        }
        return out;
    }

    /** N sensores con fechas distintas (S-001..S-NNN), orden irrelevante para estos casos. */
    private static List<Sensor> muchosSensores(int n) {
        List<Sensor> out = new ArrayList<>();
        Instant base = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 1; i <= n; i++) {
            out.add(sensor(String.format("S-%03d", i), base.minusSeconds(i * 60L), UUID.nameUUIDFromBytes(("m" + i).getBytes())));
        }
        return out;
    }
}
