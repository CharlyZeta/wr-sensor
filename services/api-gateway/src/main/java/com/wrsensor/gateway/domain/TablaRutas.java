package com.wrsensor.gateway.domain;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tabla de rutas del gateway (FEAT-0007 BR-001/BR-002/BR-004).
 *
 * <p>El matching usa {@link PathPattern} (Spring, ya en el classpath): gana el patrón
 * <b>más específico</b> (el propio {@code compareTo} de {@code PathPattern} define ese orden),
 * y ante un empate gana la ruta que declara métodos explícitos. Eso es lo que permite que
 * {@code /api/sensores/{id}/lecturas} vaya a {@code query-api} mientras {@code /api/sensores/**}
 * va a {@code sensor-registry}, aunque compartan prefijo.</p>
 *
 * <p>Clase de dominio pura: no conoce HTTP ni WebClient.</p>
 */
public final class TablaRutas {

    private final List<Ruta> rutas;

    private TablaRutas(List<Ruta> rutas) {
        this.rutas = List.copyOf(rutas);
    }

    /**
     * Construye y valida la tabla (BR-001): falla si dos rutas con la misma especificidad y
     * métodos solapados apuntan a destinos distintos (config ambigua → no se arranca).
     */
    public static TablaRutas de(List<Ruta> definiciones) {
        if (definiciones == null || definiciones.isEmpty()) {
            throw new IllegalStateException("gateway.rutas vacio: el gateway necesita al menos una ruta");
        }
        List<Ruta> copia = new ArrayList<>(definiciones);
        Set<String> ids = copia.stream().map(Ruta::id).collect(Collectors.toSet());
        if (ids.size() != copia.size()) {
            throw new IllegalStateException("gateway.rutas tiene ids duplicados: " + ids);
        }
        for (int i = 0; i < copia.size(); i++) {
            for (int j = i + 1; j < copia.size(); j++) {
                Ruta a = copia.get(i);
                Ruta b = copia.get(j);
                boolean mismaEspecificidad = a.patron().compareTo(b.patron()) == 0;
                boolean metodosSolapados = a.aplicaA(b.metodos()) || b.aplicaA(a.metodos());
                if (mismaEspecificidad && metodosSolapados && !a.destino().equals(b.destino())) {
                    throw new IllegalStateException("gateway.rutas ambiguo: '" + a.id() + "' ("
                            + a.patronTexto() + ") y '" + b.id() + "' (" + b.patronTexto()
                            + ") tienen la misma especificidad pero destinos distintos: "
                            + a.destino() + " vs " + b.destino());
                }
            }
        }
        return new TablaRutas(copia);
    }

    /** Ruta aplicable a método+path (otra vez: gana la más específica). */
    public Optional<Ruta> resolver(String metodo, String path) {
        PathContainer contenedor = PathContainer.parsePath(path);
        String m = metodo == null ? "" : metodo.toUpperCase(Locale.ROOT);
        return rutas.stream()
                .filter(r -> r.metodos().isEmpty() || r.metodos().contains(m))
                .filter(r -> r.patron().matches(contenedor))
                // PathPattern.SPECIFICITY_COMPARATOR ordena "más específico primero" (compareTo
                // negativo), así que el mínimo es el patrón más específico; ante empate gana la
                // ruta que declara métodos explícitos.
                .min(Comparator.comparing(Ruta::patron, PathPattern.SPECIFICITY_COMPARATOR)
                        .thenComparing(r -> r.metodos().isEmpty() ? 1 : 0));
    }

    public List<Ruta> rutas() {
        return rutas;
    }

    /** Definición de una ruta: patrón, métodos (vacío = todos), destino, límite y timeout. */
    public static final class Ruta {

        private final String id;
        private final String patronTexto;
        private final PathPattern patron;
        private final Set<String> metodos;
        private final String destino;
        private final String claseLimite;
        private final long timeoutMs;

        public Ruta(String id, String patronTexto, Set<String> metodos, String destino,
                    String claseLimite, long timeoutMs) {
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("gateway.rutas: id vacio");
            }
            if (patronTexto == null || !patronTexto.startsWith("/")) {
                throw new IllegalStateException("gateway.rutas." + id
                        + ": patron invalido (debe empezar con '/'): " + patronTexto);
            }
            if (destino == null || destino.isBlank()) {
                throw new IllegalStateException("gateway.rutas." + id + ": destino vacio");
            }
            if (timeoutMs <= 0) {
                throw new IllegalStateException("gateway.rutas." + id + ": timeout-ms invalido: "
                        + timeoutMs);
            }
            this.id = id;
            this.patronTexto = patronTexto;
            this.patron = PathPatternParser.defaultInstance.parse(patronTexto);
            this.metodos = metodos == null ? Set.of()
                    : metodos.stream().map(x -> x.toUpperCase(Locale.ROOT))
                            .collect(Collectors.toUnmodifiableSet());
            this.destino = destino.endsWith("/") ? destino.substring(0, destino.length() - 1) : destino;
            this.claseLimite = claseLimite == null || claseLimite.isBlank() ? "default" : claseLimite.trim();
            this.timeoutMs = timeoutMs;
        }

        public String id() {
            return id;
        }

        public String patronTexto() {
            return patronTexto;
        }

        public PathPattern patron() {
            return patron;
        }

        public Set<String> metodos() {
            return metodos;
        }

        public String destino() {
            return destino;
        }

        public String claseLimite() {
            return claseLimite;
        }

        public long timeoutMs() {
            return timeoutMs;
        }

        boolean aplicaA(Set<String> otros) {
            return metodos.isEmpty() || otros.isEmpty() || metodos.stream().anyMatch(otros::contains);
        }

        @Override
        public String toString() {
            return id + " " + patronTexto + " → " + destino;
        }
    }
}
