package com.wrsensor.datasimulator;

import com.wrsensor.datasimulator.application.port.out.LecturaPublisher;
import com.wrsensor.datasimulator.application.service.SimuladorService;
import com.wrsensor.datasimulator.domain.model.CalidadEmisor;
import com.wrsensor.datasimulator.domain.model.Lectura;
import com.wrsensor.datasimulator.domain.model.SensorSimulado;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import com.wrsensor.datasimulator.infrastructure.config.SimuladorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0006 — payload v1 en el publisher (unit tests BR-002, BR-003, BR-005 y AC-002, AC-003).
 * Scheduler reactivo real con frecuencia de 1 s (primer tick inmediato), como FEAT-0010.
 */
class FIX0006SimuladorTest {

    private static final SensorSimulado S1 = new SensorSimulado("PARANA-RECONQUISTA", "Reconquista",
            UnidadMedida.METROS, 1, new BigDecimal("5.1"), new BigDecimal("7.0"));
    private static final SensorSimulado S2 = new SensorSimulado("SALADO-SANJUSTO", "San Justo",
            UnidadMedida.METROS, 1, new BigDecimal("9.0"), new BigDecimal("11.0"));

    private FakePublisher publisher;
    private SimuladorService service;

    private static final class FakePublisher implements LecturaPublisher {
        final CopyOnWriteArrayList<Lectura> lecturas = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> publish(Lectura lectura) {
            lecturas.add(lectura);
            return Mono.empty();
        }
    }

    private static SimuladorProperties props() {
        return new SimuladorProperties(
                List.of(S1, S2),
                new SimuladorProperties.Ruido(BigDecimal.ZERO),
                new SimuladorProperties.Anomalia(30, new BigDecimal("3.0")),
                1,
                new SimuladorProperties.Lecturas("sensor.lecturas", "1.0"));
    }

    @BeforeEach
    void setUp() {
        publisher = new FakePublisher();
        service = new SimuladorService(props(), publisher);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.detener();
        }
    }

    private static void esperar(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Lectura> de(String codigo) {
        var id = SimuladorService.idDe(codigo);
        return publisher.lecturas.stream().filter(l -> l.sensorId().equals(id))
                .collect(Collectors.toList());
    }

    // ===== BR-002: eventId único por publicación =====

    @Test
    @DisplayName("BR-002: cada publicación lleva su propio eventId UUID v4")
    void br002_eventIdUnico() {
        service.iniciar();
        esperar(2200);
        service.detener();

        List<Lectura> lecturas = de("PARANA-RECONQUISTA");
        assertThat(lecturas).as("el generador publicó al menos dos lecturas").hasSizeGreaterThanOrEqualTo(2);
        assertThat(lecturas).allSatisfy(l -> {
            assertThat(l.eventId()).isNotNull();
            assertThat(l.eventId().version()).as("UUID v4").isEqualTo(4);
        });
        assertThat(lecturas.stream().map(Lectura::eventId).distinct().count())
                .as("eventIds distintos").isEqualTo(lecturas.size());
    }

    // ===== BR-003 / AC-002: secuencia por sensor y reinicio =====

    @Test
    @DisplayName("AC-002: la secuencia crece de a 1 por sensor y vuelve a 1 al reiniciar la simulación")
    void ac002_secuenciaPorSensor() {
        service.iniciar();
        esperar(2200);
        service.detener();

        List<Lectura> primeras = de("PARANA-RECONQUISTA");
        assertThat(primeras).hasSizeGreaterThanOrEqualTo(2);
        assertThat(primeras.stream().map(Lectura::sequence).collect(Collectors.toList()))
                .as("1, 2, 3… sin huecos dentro de la misma corrida")
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(
                        1, primeras.size()).boxed().collect(Collectors.toList()));

        List<Lectura> delOtroSensor = de("SALADO-SANJUSTO");
        assertThat(delOtroSensor.get(0).sequence())
                .as("el contador es por sensor, no global").isEqualTo(1);

        publisher.lecturas.clear();
        service.iniciar();
        esperar(1200);
        service.detener();
        List<Lectura> despuesDelReinicio = de("PARANA-RECONQUISTA");
        assertThat(despuesDelReinicio).isNotEmpty();
        assertThat(despuesDelReinicio.get(0).sequence())
                .as("tras detener+iniciar la secuencia arranca de nuevo en 1").isEqualTo(1);
    }

    // ===== BR-005 / AC-003: calidad informativa =====

    @Test
    @DisplayName("AC-003: normal → calidad OK sin códigos; anomalía → código ANOMALIA_INYECTADA y confianza menor")
    void ac003_calidadDeLaAnomalia() {
        service.iniciar();
        esperar(1200);
        List<Lectura> normales = de("PARANA-RECONQUISTA");
        assertThat(normales).isNotEmpty();
        Lectura normal = normales.get(normales.size() - 1);
        assertThat(normal.calidad().estado()).isEqualTo(CalidadEmisor.OK);
        assertThat(normal.calidad().codigosAnomalias()).isEmpty();

        service.inyectarAnomalia("PARANA-RECONQUISTA");
        esperar(1200);
        List<Lectura> conAnomalia = de("PARANA-RECONQUISTA");
        Lectura anomalia = conAnomalia.get(conAnomalia.size() - 1);

        assertThat(anomalia.calidad().codigosAnomalias())
                .containsExactly(CalidadEmisor.ANOMALIA_INYECTADA);
        assertThat(anomalia.calidad().confianza())
                .as("la confianza baja durante la anomalía")
                .isLessThan(normal.calidad().confianza());
        assertThat(anomalia.calidad().estado())
                .as("una anomalía de valor no se marca ERROR_SENSOR: debe seguir alertando")
                .isEqualTo(CalidadEmisor.OK);
        assertThat(anomalia.calidad().confianza()).isBetween(0.0, 1.0);
    }
}
