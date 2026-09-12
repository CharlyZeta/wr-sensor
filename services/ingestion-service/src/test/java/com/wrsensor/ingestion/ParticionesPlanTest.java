package com.wrsensor.ingestion;

import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import com.wrsensor.ingestion.infrastructure.config.ParticionesPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FIX-0005 — plan de particionamiento (unit tests BR-001, BR-004, BR-007, AF-01, AF-02).
 */
class ParticionesPlanTest {

    private static IngestionProperties.Particiones cfg(Integer total, List<Integer> asignadas) {
        return new IngestionProperties.Particiones(total, asignadas,
                "sensor.lecturas.part", "queue.sensor.lecturas.p{i}");
    }

    // ===== BR-004: configurabilidad (nunca hardcodeado) =====

    @Test
    @DisplayName("BR-004: sin configuración → default 4 particiones y todas asignadas")
    void br004_defaults() {
        ParticionesPlan plan = ParticionesPlan.de(null);
        assertThat(plan.total()).isEqualTo(4);
        assertThat(plan.asignadas()).containsExactly(0, 1, 2, 3);
        assertThat(plan.exchange()).isEqualTo("sensor.lecturas.part");
        assertThat(plan.cola(0)).isEqualTo("queue.sensor.lecturas.p0");
        assertThat(plan.cola(3)).isEqualTo("queue.sensor.lecturas.p3");
        assertThat(plan.cubreTodo()).isTrue();
        assertThat(plan.sinConsumer()).isEmpty();
    }

    @Test
    @DisplayName("BR-004: el número de particiones y la asignación vienen de configuración, no del código")
    void br004_configurableSinRecompilar() {
        ParticionesPlan cuatro = ParticionesPlan.de(cfg(null, null));
        ParticionesPlan dos = ParticionesPlan.de(cfg(2, null));
        ParticionesPlan unaAsignada = ParticionesPlan.de(cfg(4, List.of(2, 3)));

        assertThat(cuatro.colas()).containsExactly(
                "queue.sensor.lecturas.p0", "queue.sensor.lecturas.p1",
                "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3");
        assertThat(dos.colas()).containsExactly(
                "queue.sensor.lecturas.p0", "queue.sensor.lecturas.p1");
        assertThat(unaAsignada.asignadas()).containsExactly(2, 3);
        assertThat(unaAsignada.colasAsignadas()).containsExactly(
                "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3");
        assertThat(unaAsignada.sinConsumer()).containsExactly(0, 1);
        assertThat(unaAsignada.cubreTodo()).isFalse();
    }

    @Test
    @DisplayName("BR-004: patrón y exchange de particiones son configurables")
    void br004_patronYExchangeConfigurables() {
        ParticionesPlan plan = ParticionesPlan.de(new IngestionProperties.Particiones(
                3, null, "otro.exchange", "cola.propia.{i}.part"));
        assertThat(plan.exchange()).isEqualTo("otro.exchange");
        assertThat(plan.colas()).containsExactly("cola.propia.0.part", "cola.propia.1.part",
                "cola.propia.2.part");
    }

    // ===== BR-001 / BR-007: afinidad y cobertura disjunta =====

    @Test
    @DisplayName("BR-001: el plan no transforma el sensorId — la afinidad la resuelve el exchange "
            + "de hash sobre la routing key del publisher (lectura.{sensorId})")
    void br001_routingKeyIntacta() {
        // La app sólo nombra colas de partición; no calcula el destino de un sensor
        // (lo hashea el broker), de modo que el reparto no depende del tráfico ni del orden
        // de arranque, y la topología declarada es idéntica en cada arranque (no hay churn
        // de bindings que remapee sensores).
        ParticionesPlan primera = ParticionesPlan.de(cfg(4, null));
        ParticionesPlan segunda = ParticionesPlan.de(cfg(4, null));
        assertThat(primera.colas()).isEqualTo(segunda.colas());
        assertThat(primera.exchange()).isEqualTo(segunda.exchange());
        assertThat(primera.colas()).doesNotHaveDuplicates();
        assertThat(primera.asignadas()).isEqualTo(segunda.asignadas());
    }

    @Test
    @DisplayName("BR-007: la asignación de instancias disjuntas cubre cada partición una sola vez "
            + "(precondición de que el estado en memoria por sensor sea coherente)")
    void br007_coberturaDisjunta() {
        ParticionesPlan i0 = ParticionesPlan.de(cfg(4, List.of(0)));
        ParticionesPlan i1 = ParticionesPlan.de(cfg(4, List.of(1)));
        ParticionesPlan i2 = ParticionesPlan.de(cfg(4, List.of(2, 3)));

        java.util.Set<Integer> union = new java.util.HashSet<>();
        union.addAll(i0.setAsignadas());
        union.addAll(i1.setAsignadas());
        union.addAll(i2.setAsignadas());
        assertThat(union).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(union).as("sin solapamiento: cada partición tiene un único dueño").hasSize(4);

        assertThat(i0.setAsignadas()).doesNotContainAnyElementsOf(i1.setAsignadas());
        assertThat(i1.setAsignadas()).doesNotContainAnyElementsOf(i2.setAsignadas());
        assertThat(i0.colasAsignadas()).containsExactly("queue.sensor.lecturas.p0");
        assertThat(i2.colasAsignadas()).containsExactly(
                "queue.sensor.lecturas.p2", "queue.sensor.lecturas.p3");
    }

    // ===== AF-01 / AF-02 / BR-009: configuración inválida =====

    @Test
    @DisplayName("AF-01: partición asignada fuera de rango → error de configuración explícito")
    void af01_particionFueraDeRango() {
        assertThatThrownBy(() -> ParticionesPlan.de(cfg(4, List.of(0, 7))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fuera de rango")
                .hasMessageContaining("[0, 3]");
    }

    @Test
    @DisplayName("AF-02: total inválido o asignadas vacío → error de configuración explícito")
    void af02_configInvalida() {
        assertThatThrownBy(() -> ParticionesPlan.de(cfg(0, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total invalido");
        assertThatThrownBy(() -> ParticionesPlan.de(cfg(-1, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total invalido");
        assertThatThrownBy(() -> ParticionesPlan.de(cfg(4, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asignadas vacio");
        assertThatThrownBy(() -> ParticionesPlan.de(cfg(4, List.of(1, 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicadas");
        assertThatThrownBy(() -> ParticionesPlan.de(new IngestionProperties.Particiones(
                4, null, "e", "sin-marca")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{i}");
    }
}
