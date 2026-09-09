package com.wrsensor.datasimulator;

import com.wrsensor.datasimulator.application.port.out.LecturaPublisher;
import com.wrsensor.datasimulator.application.service.SimuladorService;
import com.wrsensor.datasimulator.domain.model.Lectura;
import com.wrsensor.datasimulator.domain.model.SensorSimulado;
import com.wrsensor.datasimulator.domain.model.SimuladorException;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import com.wrsensor.datasimulator.infrastructure.config.SimuladorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — FEAT-0010 BR-001/BR-004/BR-005 y AC-001..AC-008 (SimuladorService
 * con publisher fake; scheduling reactivo real con frecuencia de 1 s — primer
 * tick inmediato). Test IDs: unit-test:FEAT-0010-brXXX, assertion:FEAT-0010-acXXX.
 */
class SimuladorServiceTest {

    private static final SensorSimulado S1 = new SensorSimulado("PARANA-RECONQUISTA", "Reconquista",
            UnidadMedida.METROS, 1, new BigDecimal("5.1"), new BigDecimal("7.0"));
    private static final SensorSimulado S2 = new SensorSimulado("SALADO-SANJUSTO", "San Justo",
            UnidadMedida.METROS, null, new BigDecimal("9.0"), new BigDecimal("11.0"));

    private FakePublisher publisher;
    private SimuladorService service;

    private static final class FakePublisher implements LecturaPublisher {
        final CopyOnWriteArrayList<Lectura> lecturas = new CopyOnWriteArrayList<>();
        final AtomicInteger llamadas = new AtomicInteger();

        @Override
        public Mono<Void> publish(Lectura lectura) {
            llamadas.incrementAndGet();
            lecturas.add(lectura);
            return Mono.empty();
        }
    }

    private static SimuladorProperties props() {
        return new SimuladorProperties(
                List.of(S1, S2),
                new SimuladorProperties.Ruido(new BigDecimal("0")),
                new SimuladorProperties.Anomalia(30, new BigDecimal("3.0")),
                1,
                new SimuladorProperties.Lecturas("sensor.lecturas"));
    }

    @BeforeEach
    void setUp() {
        publisher = new FakePublisher();
        service = new SimuladorService(props(), publisher);
    }

    @AfterEach
    void tearDown() {
        service.detener();
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("AC-001: iniciar → RUNNING con los sensores configurados y empiezan a publicarse lecturas")
    void ac001_iniciarPublica() {
        SimuladorService.SimuladorEstado e = service.iniciar();
        assertThat(e.running()).isTrue();
        assertThat(e.sensores()).containsExactlyInAnyOrder(S1.codigo(), S2.codigo());

        esperar(1200); // primer tick inmediato + intervalo 1 s
        assertThat(publisher.lecturas).isNotEmpty();
        assertThat(publisher.lecturas).allSatisfy(l -> {
            assertThat(l.unidadMedida()).isNotNull();
            assertThat(l.valor()).isPositive();
            assertThat(l.timestamp()).isNotNull();
        });
    }

    @Test
    @DisplayName("AF-01 / BR-004 / AC-002: iniciar dos veces → SIMULATOR_ALREADY_RUNNING")
    void ac002_iniciarDosVeces409() {
        service.iniciar();
        esperar(100);
        assertThatThrownBy(service::iniciar)
                .isInstanceOf(SimuladorException.class)
                .extracting(e -> ((SimuladorException) e).code)
                .isEqualTo(SimuladorException.ALREADY_RUNNING);
    }

    @Test
    @DisplayName("AF-02 / BR-004 / AC-003 y AC-004: detener detiene publicacion y es idempotente")
    void ac003_004_detenerIdempotente() {
        service.iniciar();
        esperar(1100);
        long antes = publisher.lecturas.size();
        assertThat(antes).isGreaterThanOrEqualTo(2); // primer tick de ambos sensores

        SimuladorService.SimuladorEstado e = service.detener();
        assertThat(e.running()).isFalse();
        esperar(1600);
        assertThat(publisher.lecturas.size()).as("no se publica tras detener").isEqualTo(antes);

        SimuladorService.SimuladorEstado otra = service.detener(); // idempotente
        assertThat(otra.running()).isFalse();
    }

    @Test
    @DisplayName("AF-05 / BR-005 / AC-007: anomalia con simulacion detenida → SIMULATOR_NOT_RUNNING")
    void ac007_anomaliaSinRunning() {
        assertThatThrownBy(() -> service.inyectarAnomalia(S1.codigo()))
                .isInstanceOf(SimuladorException.class)
                .extracting(e -> ((SimuladorException) e).code)
                .isEqualTo(SimuladorException.NOT_RUNNING);
    }

    @Test
    @DisplayName("AF-03 / BR-005 / AC-006: anomalia en sensor no configurado → SENSOR_NOT_FOUND")
    void ac006_anomaliaSensorDesconocido() {
        service.iniciar();
        esperar(100);
        assertThatThrownBy(() -> service.inyectarAnomalia("NO-EXISTE"))
                .isInstanceOf(SimuladorException.class)
                .extracting(e -> ((SimuladorException) e).code)
                .isEqualTo(SimuladorException.SENSOR_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-005: anomalia en sensor configurado → lectura posterior supera su rango normal")
    void ac005_anomaliaSubeElValor() {
        service.iniciar();
        esperar(1050);
        boolean s1Supera = publisher.lecturas.stream()
                .anyMatch(l -> l.sensorId().equals(SimuladorService.idDe(S1.codigo()))
                        && l.valor().compareTo(S1.rangoNormalMax()) > 0);
        assertThat(s1Supera).as("sin anomalia S1 no supera su rango normal (salto 0)").isFalse();

        service.inyectarAnomalia(S1.codigo());
        Instant limite = Instant.now().plusSeconds(5);
        boolean supera = false;
        while (Instant.now().isBefore(limite) && !supera) {
            esperar(200);
            supera = publisher.lecturas.stream()
                    .anyMatch(l -> l.sensorId().equals(SimuladorService.idDe(S1.codigo()))
                            && l.valor().compareTo(S1.rangoNormalMax()) > 0);
        }
        assertThat(supera).as("la anomalia produce al menos una lectura sobre rangoNormalMax").isTrue();
    }

    @Test
    @DisplayName("AC-008: estado refleja running/sensores/contador de lecturas")
    void ac008_estado() {
        SimuladorService.SimuladorEstado detenido = service.estado();
        assertThat(detenido.running()).isFalse();

        service.iniciar();
        esperar(1100);
        SimuladorService.SimuladorEstado corriendo = service.estado();
        assertThat(corriendo.running()).isTrue();
        assertThat(corriendo.sensores()).hasSize(2);
        assertThat(corriendo.lecturasPublicadas()).isEqualTo(publisher.lecturas.size());
    }

    @Test
    @DisplayName("BR-008: servicio nuevo arranca detenido (stateless)")
    void testBR008_arrancaDetenido() {
        assertThat(service.estado().running()).isFalse();
        assertThat(publisher.lecturas).isEmpty();
    }

    @Test
    @DisplayName("AF-04: fallo de Rabbit no propaga a iniciar() y el servicio sigue controlable")
    void af004_errorDePublicacion() {
        LecturaPublisher fallando = l -> Mono.error(new RuntimeException("broker down"));
        SimuladorService srv = new SimuladorService(props(), fallando);

        SimuladorService.SimuladorEstado e = srv.iniciar(); // no lanza: errores absorbidos por generador
        assertThat(e.running()).isTrue();
        esperar(1600); // varios ticks fallan y se absorben
        SimuladorService.SimuladorEstado detenido = srv.detener();
        assertThat(detenido.running()).isFalse();
    }
}
