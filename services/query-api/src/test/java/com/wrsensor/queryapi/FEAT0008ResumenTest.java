package com.wrsensor.queryapi;

import com.wrsensor.queryapi.application.port.SensoresMetadataPort;
import com.wrsensor.queryapi.application.port.UltimasLecturasPort;
import com.wrsensor.queryapi.application.service.ResumenService;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.QueryException;
import com.wrsensor.queryapi.domain.ResumenSensor;
import com.wrsensor.queryapi.domain.SensorMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0008 BR-005/BR-006: composición del resumen del mapa con puertos fake.
 * AC-010 se verifica contando invocaciones del port de últimas lecturas (nunca N+1).
 */
class FEAT0008ResumenTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ID_SIN = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    private static final Instant BASE = Instant.parse("2026-09-09T12:00:00Z");

    private static SensorMetadata sensor(UUID id, String codigo) {
        return new SensorMetadata(id, codigo, "Sensor " + codigo, "TEMPERATURA",
                new BigDecimal("-34.60"), new BigDecimal("-58.40"), "ACTIVE", "CELSIUS");
    }

    private static LecturaConsulta lectura(UUID id, String valor, Instant ts, String severidad) {
        return new LecturaConsulta(id, ts, new BigDecimal(valor), "CELSIUS", severidad, "OK");
    }

    /** Metadata fake: tres sensores, uno de ellos sin lecturas (AF-05). */
    private static final class MetadataFake implements SensoresMetadataPort {
        private final boolean falla;

        MetadataFake(boolean falla) {
            this.falla = falla;
        }

        @Override
        public Flux<SensorMetadata> listar() {
            if (falla) {
                return Flux.error(new QueryException(QueryException.REGISTRY_UNAVAILABLE,
                        "registry no disponible: connection refused"));
            }
            return Flux.just(sensor(ID_A, "S-01"), sensor(ID_B, "S-02"), sensor(ID_SIN, "S-03"));
        }
    }

    /** Port de últimas lecturas que cuenta invocaciones (AC-010). */
    private static final class LecturasContadas implements UltimasLecturasPort {
        private final AtomicInteger invocaciones = new AtomicInteger();
        private final List<LecturaConsulta> filas;

        LecturasContadas(List<LecturaConsulta> filas) {
            this.filas = filas;
        }

        @Override
        public Flux<LecturaConsulta> ultimasPorSensor() {
            invocaciones.incrementAndGet();
            return Flux.fromIterable(filas);
        }
    }

    // ===== AC-009 / AF-05 =====

    @Test
    @DisplayName("AC-009/AF-05: todos los sensores con su última lectura y null para los que no tienen")
    void ac009_todosConUltimaLectura() {
        LecturasContadas lecturas = new LecturasContadas(List.of(
                lectura(ID_A, "21.5", BASE.plusSeconds(30), "NORMAL"),
                lectura(ID_B, "88.0", BASE.plusSeconds(10), "CRITICAL")));
        ResumenService servicio = new ResumenService(new MetadataFake(false), lecturas);

        List<ResumenSensor> resumen = servicio.resumen().block();

        assertThat(resumen).hasSize(3);
        assertThat(resumen).extracting(ResumenSensor::codigo)
                .as("el orden del registry se preserva").containsExactly("S-01", "S-02", "S-03");
        ResumenSensor a = resumen.get(0);
        assertThat(a.id()).isEqualTo(ID_A);
        assertThat(a.nombre()).isEqualTo("Sensor S-01");
        assertThat(a.tipo()).isEqualTo("TEMPERATURA");
        assertThat(a.latitud()).isEqualByComparingTo("-34.60");
        assertThat(a.longitud()).isEqualByComparingTo("-58.40");
        assertThat(a.estado()).isEqualTo("ACTIVE");
        assertThat(a.unidadMedida()).isEqualTo("CELSIUS");
        assertThat(a.ultimaLectura().valor()).isEqualByComparingTo("21.5");
        assertThat(a.ultimaLectura().timestamp()).isEqualTo(BASE.plusSeconds(30));
        assertThat(a.ultimaLectura().severidad()).isEqualTo("NORMAL");
        assertThat(a.ultimaLectura().calidad()).isEqualTo("OK");

        assertThat(resumen.get(2).codigo()).isEqualTo("S-03");
        assertThat(resumen.get(2).ultimaLectura()).as("AF-05: sin lecturas, no se omite").isNull();
    }

    @Test
    @DisplayName("AF-05: sin ninguna lectura el resumen sigue teniendo todos los sensores")
    void af05_sinLecturas() {
        ResumenService servicio = new ResumenService(new MetadataFake(false),
                new LecturasContadas(List.of()));
        assertThat(servicio.resumen().block())
                .allMatch(s -> s.ultimaLectura() == null)
                .hasSize(3);
    }

    // ===== AC-010 / BR-006 =====

    @Test
    @DisplayName("AC-010/BR-006: el port de la hypertable recibe exactamente una llamada (no N)")
    void ac010_unaSolaConsulta() {
        LecturasContadas lecturas = new LecturasContadas(List.of(
                lectura(ID_A, "1", BASE, "NORMAL"), lectura(ID_B, "2", BASE, "NORMAL")));
        new ResumenService(new MetadataFake(false), lecturas).resumen().block();
        assertThat(lecturas.invocaciones.get())
                .as("una consulta DISTINCT ON para los 3 sensores").isEqualTo(1);
    }

    @Test
    @DisplayName("BR-006: lecturas de sensores que no están en el registry se ignoran")
    void br006_lecturasHuerfanas() {
        LecturasContadas lecturas = new LecturasContadas(List.of(
                lectura(UUID.randomUUID(), "9", BASE, "NORMAL"),
                lectura(ID_SIN, "3.3", BASE.plusSeconds(5), "WARNING")));
        List<ResumenSensor> resumen = new ResumenService(new MetadataFake(false), lecturas)
                .resumen().block();
        assertThat(resumen).hasSize(3);
        assertThat(resumen.get(2).ultimaLectura().valor()).isEqualByComparingTo("3.3");
        assertThat(resumen.get(2).ultimaLectura().severidad()).isEqualTo("WARNING");
    }

    // ===== AF-06 =====

    @Test
    @DisplayName("AF-06: si el registry falla, el resumen falla con REGISTRY_UNAVAILABLE (sin parciales)")
    void af06_registryCaido() {
        StepVerifier.create(new ResumenService(new MetadataFake(true),
                        new LecturasContadas(List.of(lectura(ID_A, "1", BASE, "NORMAL"))))
                        .resumen())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify();
    }

    @Test
    @DisplayName("AF-06: si falla la lectura de la hypertable no se emite una lista a medias")
    void af06_fallaDeLecturas() {
        SensoresMetadataPort metadata = new MetadataFake(false);
        UltimasLecturasPort roto = () -> Flux.error(new IllegalStateException("base caida"));
        StepVerifier.create(new ResumenService(metadata, roto).resumen())
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("AF-06: sin sensores en el registry el resumen es una lista vacía (no un error)")
    void af06_registryVacio() {
        SensoresMetadataPort vacio = () -> Flux.empty();
        Mono<List<ResumenSensor>> resumen = new ResumenService(vacio,
                new LecturasContadas(List.of())).resumen();
        assertThat(resumen.block()).isEmpty();
    }
}
