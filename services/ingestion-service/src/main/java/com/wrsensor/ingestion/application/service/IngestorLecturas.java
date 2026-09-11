package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.domain.AlertaEvento;
import com.wrsensor.ingestion.domain.Calidad;
import com.wrsensor.ingestion.domain.LecturaEntrada;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.Severidad;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pipeline de ingestion (Main Flow FEAT-0011, endurecido por FIX-0003 y FIX-0004):
 * validar ventana (AF-05) → resolver sensor (AF-02/AF-04) → estado (AF-03) →
 * **validar rango físico** (BR-001..BR-006: fuera de rango o marca del emisor →
 * `ERROR_SENSOR`, sin severidad ni alerta) → evaluar severidad (BR-004) →
 * persistir de forma atómica con dedupe + outbox opcional (FIX-0003 BR-003).
 */
public class IngestorLecturas {

    private static final Logger log = LoggerFactory.getLogger(IngestorLecturas.class);

    public record Resultado(UUID sensorId, Severidad severidad, Calidad calidad,
                            boolean eventoEncolado, boolean duplicado) {}

    private final SensorConfigPort sensores;
    private final IngestaTransaccionalPort store;
    private final IngestionProperties props;
    private final Map<UUID, Severidad> ultimaSeveridad = new ConcurrentHashMap<>();

    public IngestorLecturas(SensorConfigPort sensores, IngestaTransaccionalPort store,
                            IngestionProperties props) {
        this.sensores = sensores;
        this.store = store;
        this.props = props;
    }

    /** Inyectable en tests: setea la ultima severidad de un sensor. */
    public void setUltimaSeveridad(UUID id, Severidad s) {
        if (s == null) ultimaSeveridad.remove(id);
        else ultimaSeveridad.put(id, s);
    }

    public Mono<Resultado> procesar(LecturaEntrada lectura, Instant ahora) {
        return Mono.defer(() -> {
            validarVentana(lectura, ahora);
            return sensores.findById(lectura.sensorId())
                    .switchIfEmpty(Mono.error(new RechazoLecturaException(
                            RechazoLecturaException.SENSOR_UNKNOWN, "sensor desconocido: " + lectura.sensorId())))
                    .flatMap(sensor -> procesarConSensor(lectura, sensor));
        });
    }

    public Mono<Resultado> procesar(LecturaEntrada lectura) {
        return procesar(lectura, Instant.now());
    }

    private Mono<Resultado> procesarConSensor(LecturaEntrada lectura, SensorInfo sensor) {
        if ("INACTIVO".equalsIgnoreCase(sensor.estado())) {
            return Mono.error(new RechazoLecturaException(RechazoLecturaException.SENSOR_INACTIVE,
                    "sensor inactivo: " + sensor.codigo()));
        }

        // --- FIX-0004: rango fisico y calidad del dato (antes de severidad) ---
        RangoFisicoEvaluador.Veredicto veredicto = RangoFisicoEvaluador.evaluar(
                sensor, lectura.valor(), props.rangoFisico());
        boolean marcadaPorEmisor = lectura.calidadEmisor() != null
                && Calidad.ERROR_SENSOR.name().equalsIgnoreCase(lectura.calidadEmisor().trim());
        if (veredicto.fuera() || marcadaPorEmisor) {
            String motivo = marcadaPorEmisor ? "CALIDAD_EMISOR_ERROR_SENSOR" : veredicto.motivo();
            log.warn("[ingestion] lectura ERROR_SENSOR sensor={} codigo={} valor={} unidad={} motivo={}",
                    sensor.id(), sensor.codigo(), lectura.valor(), sensor.unidadMedida(), motivo);
            LecturaPersistida error = new LecturaPersistida(lectura.sensorId(), lectura.timestamp(),
                    lectura.valor(), lectura.unidadMedida(), null, Calidad.ERROR_SENSOR);
            return store.persistir(error, ClaveIdempotencia.de(lectura), null)
                    .map(r -> new Resultado(lectura.sensorId(), null, Calidad.ERROR_SENSOR,
                            false, !r.persistida()));
            // BR-005: no se toca ultimaSeveridad ni se encola outbox.
        }

        // --- flujo normal (sin cambios de FEAT-0011/FIX-0003) ---
        Severidad severidad = SeveridadEvaluador.evaluar(sensor, lectura.valor());
        Severidad anterior = ultimaSeveridad.get(lectura.sensorId());
        boolean cambio = anterior != null && anterior != severidad;

        LecturaPersistida persistida = new LecturaPersistida(lectura.sensorId(), lectura.timestamp(),
                lectura.valor(), lectura.unidadMedida(), severidad, Calidad.OK);
        String clave = ClaveIdempotencia.de(lectura);
        IngestaTransaccionalPort.OutboxAlerta outbox = null;
        if (cambio) {
            AlertaEvento evento = new AlertaEvento(lectura.sensorId(), lectura.timestamp(),
                    lectura.valor(), anterior, severidad, false);
            outbox = new IngestaTransaccionalPort.OutboxAlerta(lectura.sensorId(),
                    AlertaEventoJson.routingKey(evento), AlertaEventoJson.de(evento));
        }

        IngestaTransaccionalPort.OutboxAlerta outboxFinal = outbox;
        return store.persistir(persistida, clave, outbox).map(resultado -> {
            if (!resultado.persistida()) {
                log.info("[ingestion] redelivery descartado (clave={})", clave);
                return new Resultado(lectura.sensorId(), severidad, Calidad.OK, false, true);
            }
            ultimaSeveridad.put(lectura.sensorId(), severidad);
            return new Resultado(lectura.sensorId(), severidad, Calidad.OK,
                    outboxFinal != null, false);
        });
    }

    private void validarVentana(LecturaEntrada lectura, Instant ahora) {
        long ventana = props.ventanaSegundos();
        Duration diff = Duration.between(lectura.timestamp(), ahora).abs();
        if (diff.getSeconds() > ventana) {
            throw new RechazoLecturaException(RechazoLecturaException.TIMESTAMP_OUT_OF_WINDOW,
                    "timestamp fuera de ventana: " + lectura.timestamp());
        }
    }

    /** Expuesto para tests: normalizacion de la marca de calidad del emisor. */
    static String normalizar(String calidad) {
        return calidad == null ? null : calidad.trim().toUpperCase(Locale.ROOT);
    }
}
