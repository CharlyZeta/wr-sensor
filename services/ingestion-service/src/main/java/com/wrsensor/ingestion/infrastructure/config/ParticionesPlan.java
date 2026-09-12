package com.wrsensor.ingestion.infrastructure.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plan de particionamiento derivado de la configuración (FIX-0005 BR-003/BR-004/BR-005):
 * cuántas particiones hay, cuáles consume esta instancia y cómo se llaman las colas.
 *
 * <p>Es un value object puro y validado: {@link #de(IngestionProperties.Particiones)}
 * falla con {@link IllegalStateException} ante configuración inválida (AF-01/AF-02/BR-009),
 * de modo que la instancia puede abortar el consumo sin degradar a "consumir sin
 * particionar".</p>
 */
public record ParticionesPlan(int total, List<Integer> asignadas, String exchange, String patron) {

    public static final int TOTAL_DEFAULT = 4;
    public static final String EXCHANGE_DEFAULT = "sensor.lecturas.part";
    public static final String PATRON_DEFAULT = "queue.sensor.lecturas.p{i}";
    private static final String MARCA = "{i}";

    public ParticionesPlan {
        asignadas = List.copyOf(asignadas);
    }

    /** Construye y valida el plan (defaults documentados: total 4, todas asignadas). */
    public static ParticionesPlan de(IngestionProperties.Particiones cfg) {
        if (cfg == null) {
            return new ParticionesPlan(TOTAL_DEFAULT,
                    rango(0, TOTAL_DEFAULT), EXCHANGE_DEFAULT, PATRON_DEFAULT);
        }

        Integer totalCfg = cfg.total();
        int total = totalCfg == null ? TOTAL_DEFAULT : totalCfg;
        if (total <= 0) {
            throw new IllegalStateException(
                    "ingestion.particiones.total invalido: " + total + " (debe ser > 0)");
        }

        List<Integer> asignadas;
        if (cfg.asignadas() == null) {
            asignadas = rango(0, total);                       // default: todas las particiones
        } else if (cfg.asignadas().isEmpty()) {
            throw new IllegalStateException(
                    "ingestion.particiones.asignadas vacio: indicar al menos una particion "
                            + "(o omitir la propiedad para consumir todas)");
        } else {
            asignadas = List.copyOf(cfg.asignadas());
        }

        for (Integer i : asignadas) {
            if (i == null || i < 0 || i >= total) {
                throw new IllegalStateException("ingestion.particiones.asignadas contiene la "
                        + "particion " + i + " fuera de rango [0, " + (total - 1) + "]");
            }
        }
        if (new HashSet<>(asignadas).size() != asignadas.size()) {
            throw new IllegalStateException(
                    "ingestion.particiones.asignadas tiene particiones duplicadas: " + asignadas);
        }

        String exchange = texto(cfg.exchange(), EXCHANGE_DEFAULT);
        String patron = texto(cfg.patron(), PATRON_DEFAULT);
        if (!patron.contains(MARCA)) {
            throw new IllegalStateException("ingestion.particiones.patron sin " + MARCA
                    + ": " + patron + " (ejemplo: " + PATRON_DEFAULT + ")");
        }
        return new ParticionesPlan(total, asignadas, exchange, patron);
    }

    private static String texto(String valor, String def) {
        return valor == null || valor.isBlank() ? def : valor.trim();
    }

    private static List<Integer> rango(int desde, int hasta) {
        List<Integer> l = new ArrayList<>();
        for (int i = desde; i < hasta; i++) {
            l.add(i);
        }
        return List.copyOf(l);
    }

    /** Nombre de la cola de la partición {@code i} (BR-003/BR-004). */
    public String cola(int i) {
        return patron.replace(MARCA, String.valueOf(i));
    }

    /** Todas las colas de la topología (se declaran siempre, BR-005). */
    public List<String> colas() {
        List<String> l = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            l.add(cola(i));
        }
        return List.copyOf(l);
    }

    /** Particiones que esta instancia consume (BR-005). */
    public List<String> colasAsignadas() {
        List<String> l = new ArrayList<>(asignadas.size());
        for (Integer i : asignadas) {
            l.add(cola(i));
        }
        return List.copyOf(l);
    }

    /** Particiones declaradas pero sin consumer en esta instancia (BR-008, log WARN). */
    public List<Integer> sinConsumer() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (!asignadas.contains(i)) {
                l.add(i);
            }
        }
        return List.copyOf(l);
    }

    /** ¿Esta instancia cubre todas las particiones? (BR-008) */
    public boolean cubreTodo() {
        return asignadas.size() == total;
    }

    /** Set de particiones asignadas, para validar que no hay solapamiento entre instancias. */
    public Set<Integer> setAsignadas() {
        return Set.copyOf(asignadas);
    }
}
