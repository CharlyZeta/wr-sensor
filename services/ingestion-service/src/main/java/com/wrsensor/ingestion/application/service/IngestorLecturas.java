package com.wrsensor.ingestion.application.service;

import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
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
 * Pipeline de ingestion (Main Flow FEAT-0011, endurecido por FIX-0003):
 * validar ventana (AF-05) → resolver sensor (AF-02/AF-04) → estado (AF-03) →
 * evaluar severidad (BR-002) → **persistir de forma atómica** (lectura + dedupe +
 * outbox opcional) por {@link IngestaTransaccionalPort}.
 *
 * <p>FIX-0003: ya **no** publica en RabbitMQ en el camino de consumo (BR-004); la
 * entrega la hace el poller de outbox. El redelivery se descarta por clave de
 * idempotencia (`ClaveIdempotencia`, BR-001/BR-002).
 */
public class IngestorLecturas {

    private static final Logger log = LoggerFactory.getLogger(IngestorLecturas.class);

    public record Resultado(UUID sensorId, Severidad severidad, boolean eventoEncolado, boolean duplicado) {}

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
        Severidad severidad = SeveridadEvaluador.evaluar(sensor, lectura.valor());
        Severidad anterior = ultimaSeveridad.get(lectura.sensorId());
        boolean cambio = anterior != null && anterior != severidad;

        LecturaPersistida persistida = new LecturaPersistida(lectura.sensorId(), lectura.timestamp(),
                lectura.valor(), lectura.unidadMedida(), severidad);
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
                return new Resultado(lectura.sensorId(), severidad, false, true);
            }
            ultimaSeveridad.put(lectura.sensorId(), severidad);
            return new Resultado(lectura.sensorId(), severidad, outboxFinal != null, false);
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
}
