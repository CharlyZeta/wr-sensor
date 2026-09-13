package com.wrsensor.datasimulator.application.service;

import com.wrsensor.datasimulator.application.port.out.LecturaPublisher;
import com.wrsensor.datasimulator.domain.model.CalidadEmisor;
import com.wrsensor.datasimulator.domain.model.Lectura;
import com.wrsensor.datasimulator.domain.model.SensorSimulado;
import com.wrsensor.datasimulator.domain.model.SimuladorException;
import com.wrsensor.datasimulator.domain.model.UnidadMedida;
import com.wrsensor.datasimulator.infrastructure.config.SimuladorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Orquestador del simulador (FEAT-0010): maquina de estados STOPPED/RUNNING
 * (BR-004) + un generador por sensor (BR-001) que publica lecturas (BR-002)
 * via {@link LecturaPublisher}. Sin auth en v1 (decision HO-Gate). Aplicacion sin
 * Spring; scheduling reactivo (BR-007).
 */
public class SimuladorService {

    private static final Logger log = LoggerFactory.getLogger(SimuladorService.class);

    public record SimuladorEstado(boolean running, List<String> sensores, long lecturasPublicadas) {}

    private final SimuladorProperties props;
    private final LecturaPublisher publisher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Instant> anomaliaHasta = new ConcurrentHashMap<>();
    private final Map<String, reactor.core.Disposable> generadores = new ConcurrentHashMap<>();
    /** FIX-0006 BR-003: secuencia creciente por sensor (en memoria, se reinicia al detener). */
    private final Map<String, AtomicLong> secuencias = new ConcurrentHashMap<>();
    private final AtomicLong publicadas = new AtomicLong(0);

    public SimuladorService(SimuladorProperties props, LecturaPublisher publisher) {
        this.props = props;
        this.publisher = publisher;
    }

    public List<SensorSimulado> sensoresConfigurados() {
        return props.sensores() == null ? List.of() : props.sensores();
    }

    // ============ control ============

    /** BR-004: iniciar corriendo → SIMULATOR_ALREADY_RUNNING (AF-01, AC-002). */
    public synchronized SimuladorEstado iniciar() {
        if (!running.compareAndSet(false, true)) {
            throw new SimuladorException(SimuladorException.ALREADY_RUNNING,
                    "la simulacion ya esta corriendo");
        }
        for (SensorSimulado sensor : sensoresConfigurados()) {
            arrancarGenerador(sensor);
        }
        return estado();
    }

    private void arrancarGenerador(SensorSimulado sensor) {
        Duration intervalo = Duration.ofSeconds(frecuenciaDe(sensor));
        reactor.core.Disposable d = Flux.interval(Duration.ZERO, intervalo, Schedulers.parallel())
                .onBackpressureDrop()
                .flatMap(tick -> publisher.publish(lecturaDe(sensor))
                        .onErrorResume(err -> {
                            log.error("[simulador] fallo publicando {}: {} — detengo su generador",
                                    sensor.codigo(), err.getMessage());
                            detenerGenerador(sensor.codigo());
                            return reactor.core.publisher.Mono.empty();
                        }))
                .doOnError(err -> log.error("[simulador] error generico: {}", err.getMessage()))
                .subscribe();
        generadores.put(sensor.codigo(), d);
    }

    private void detenerGenerador(String codigo) {
        reactor.core.Disposable d = generadores.remove(codigo);
        if (d != null) d.dispose();
    }

    /** BR-004/AC-003/AC-004: detener es idempotente (no-op si ya esta detenido). */
    public synchronized SimuladorEstado detener() {
        running.set(false);
        generadores.keySet().forEach(this::detenerGenerador);
        generadores.clear();
        anomaliaHasta.clear();
        secuencias.clear();   // FIX-0006 BR-003: al reiniciar, la secuencia arranca de nuevo en 1
        return estado();
    }

    public SimuladorEstado estado() {
        return new SimuladorEstado(running.get(), sensoresConfigurados().stream()
                .map(SensorSimulado::codigo).collect(Collectors.toList()), publicadas.get());
    }

    /** AF-05/AC-007: anomalia sin simulacion → SIMULATOR_NOT_RUNNING. */
    public SimuladorEstado inyectarAnomalia(String codigo) {
        SensorSimulado sensor = findByCodigo(codigo);
        if (!running.get()) {
            throw new SimuladorException(SimuladorException.NOT_RUNNING,
                    "la simulacion no esta corriendo");
        }
        anomaliaHasta.put(sensor.codigo(), Instant.now().plusSeconds(props.anomalia().segundos()));
        return estado();
    }

    // ============ generacion ============

    private Lectura lecturaDe(SensorSimulado sensor) {
        Instant now = Instant.now();
        boolean anomalia = enAnomalia(sensor.codigo(), now);
        BigDecimal salto = anomalia ? props.anomalia().saltoMetros() : null;
        BigDecimal valor = ValorSintetico.valor(sensor, now,
                props.ruido() == null || props.ruido().sigmaMetros() == null
                        ? new BigDecimal("0.05") : props.ruido().sigmaMetros(),
                salto, ThreadLocalRandom.current().nextGaussian());
        publicadas.incrementAndGet();
        // FIX-0006 BR-002/BR-003/BR-005: eventId por publicación, secuencia por sensor y
        // marca de calidad informativa (la anomalía se señala por código, no por ERROR_SENSOR).
        long sequence = secuencias.computeIfAbsent(sensor.codigo(), c -> new AtomicLong())
                .incrementAndGet();
        return new Lectura(idDe(sensor.codigo()), now, valor, sensor.unidadMedida(),
                UUID.randomUUID(), sequence,
                anomalia ? CalidadEmisor.conAnomalia() : CalidadEmisor.normal());
    }

    private boolean enAnomalia(String codigo, Instant now) {
        Instant hasta = anomaliaHasta.get(codigo);
        return hasta != null && now.isBefore(hasta);
    }

    private SensorSimulado findByCodigo(String codigo) {
        return sensoresConfigurados().stream()
                .filter(s -> s.codigo().equalsIgnoreCase(codigo))
                .findFirst()
                .orElseThrow(() -> new SimuladorException(SimuladorException.SENSOR_NOT_FOUND,
                        "sensor no configurado: " + codigo));
    }

    /** id estable por codigo (los sensores seed no traen UUID en la config local). */
    public static UUID idDe(String codigo) {
        return UUID.nameUUIDFromBytes(codigo.toUpperCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** BR-001: frecuencia del sensor o default global (30 s). */
    private int frecuenciaDe(SensorSimulado sensor) {
        if (sensor.frecuenciaReporteSegundos() != null) return sensor.frecuenciaReporteSegundos();
        if (props.frecuenciaReporteSegundos() != null) return props.frecuenciaReporteSegundos();
        return 30;
    }
}
