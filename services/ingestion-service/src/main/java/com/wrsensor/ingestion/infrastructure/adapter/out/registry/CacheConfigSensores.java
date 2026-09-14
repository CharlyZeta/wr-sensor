package com.wrsensor.ingestion.infrastructure.adapter.out.registry;

import com.wrsensor.ingestion.domain.SensorInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache de config de sensores con TTL y contadores (FIX-0007 BR-004/BR-007).
 *
 * <p>Guarda {@code SensorInfo} con el instante de carga. La decisión de si una copia está vencida
 * y de si se usa como *last-known-good* la toma el adapter; acá vive el almacenamiento y la
 * telemetría (tamaño, aciertos sin red, refrescos exitosos y copias vencidas usadas).</p>
 */
public class CacheConfigSensores {

    public record Entrada(SensorInfo info, Instant cargadoEn) {}

    private final Map<UUID, Entrada> cache = new ConcurrentHashMap<>();
    private final AtomicLong aciertos = new AtomicLong();
    private final AtomicLong refrescos = new AtomicLong();
    private final AtomicLong vencidasUsadas = new AtomicLong();

    public Entrada obtener(UUID sensorId) {
        return cache.get(sensorId);
    }

    public void poner(UUID sensorId, SensorInfo info, Instant ahora) {
        cache.put(sensorId, new Entrada(info, ahora));
    }

    /** ¿La copia venció su TTL? */
    public boolean vencida(Entrada entrada, Duration ttl, Instant ahora) {
        return entrada != null && Duration.between(entrada.cargadoEn(), ahora).compareTo(ttl) > 0;
    }

    public int tamano() {
        return cache.size();
    }

    /** Copia servida sin red (dentro del TTL). */
    public void contabilizarAcierto() {
        aciertos.incrementAndGet();
    }

    /** Refresco exitoso contra el registry. */
    public void contabilizarRefresco() {
        refrescos.incrementAndGet();
    }

    /** Copia vencida usada por last-known-good. */
    public void contabilizarVencidaUsada() {
        vencidasUsadas.incrementAndGet();
    }

    public long aciertos() {
        return aciertos.get();
    }

    public long refrescos() {
        return refrescos.get();
    }

    public long vencidasUsadas() {
        return vencidasUsadas.get();
    }
}
