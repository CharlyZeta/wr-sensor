package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.application.port.AlertaEventoPublisher;
import com.wrsensor.ingestion.application.port.LecturaStore;
import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.domain.AlertaEvento;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pipeline de ingestion (Main Flow FEAT-0011): validar ventana (AF-05) → resolver
 * sensor (AF-02/AF-04) → estado (AF-03) → evaluar severidad (BR-002) → persistir
 * (BR-003/BR-008) → publicar AlertaEvento si hay cambio de severidad (BR-004;
 * decision HO-Gate: evento simple, histéresis en FEAT-0012). Última severidad en
 * memoria (decision HO-Gate). Aplicacion sin Spring (ports inyectados).
 */
public class IngestorLecturas {

    private static final Logger log = LoggerFactory.getLogger(IngestorLecturas.class);

    public record Resultado(UUID sensorId, Severidad severidad, boolean eventoPublicado) {}

    private final SensorConfigPort sensores;
    private final LecturaStore store;
    private final AlertaEventoPublisher alertas;
    private final IngestionProperties props;
    private final Map<UUID, Severidad> ultimaSeveridad = new ConcurrentHashMap<>();
    private final Instant relojBase = Instant.now();

    public IngestorLecturas(SensorConfigPort sensores, LecturaStore store,
                            AlertaEventoPublisher alertas, IngestionProperties props) {
        this.sensores = sensores;
        this.store = store;
        this.alertas = alertas;
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
                    .flatMap(sensor -> procesarConSensor(lectura, sensor, ahora));
        });
    }

    public Mono<Resultado> procesar(LecturaEntrada lectura) {
        return procesar(lectura, Instant.now());
    }

    private Mono<Resultado> procesarConSensor(LecturaEntrada lectura, SensorInfo sensor, Instant ahora) {
        if ("INACTIVO".equalsIgnoreCase(sensor.estado())) {
            return Mono.error(new RechazoLecturaException(RechazoLecturaException.SENSOR_INACTIVE,
                    "sensor inactivo: " + sensor.codigo()));
        }
        Severidad severidad = SeveridadEvaluador.evaluar(sensor, lectura.valor());
        Severidad anterior = ultimaSeveridad.get(lectura.sensorId());

        LecturaPersistida persistida = new LecturaPersistida(lectura.sensorId(), lectura.timestamp(),
                lectura.valor(), lectura.unidadMedida(), severidad);
        boolean cambio = anterior != null && anterior != severidad;
        return store.insert(persistida).then(Mono.defer(() -> {
            ultimaSeveridad.put(lectura.sensorId(), severidad);
            if (cambio) {
                AlertaEvento evento = new AlertaEvento(lectura.sensorId(), lectura.timestamp(),
                        lectura.valor(), anterior, severidad, false);
                return alertas.publish(evento).thenReturn(new Resultado(lectura.sensorId(), severidad, true));
            }
            return Mono.just(new Resultado(lectura.sensorId(), severidad, false));
        }));
    }

    private void validarVentana(LecturaEntrada lectura, Instant ahora) {
        long ventana = props.ventanaSegundos();
        Duration diff = Duration.between(lectura.timestamp(), ahora).abs();
        if (diff.getSeconds() > ventana) {
            throw new RechazoLecturaException(RechazoLecturaException.TIMESTAMP_OUT_OF_WINDOW,
                    "timestamp fuera de ventana: " + lectura.timestamp());
        }
    }
}
