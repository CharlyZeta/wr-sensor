package com.wrsensor.queryapi.infrastructure.adapter.in.messaging;

import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.VersionSchema;
import com.wrsensor.queryapi.infrastructure.realtime.LecturaRealtimeBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consumer de `sensor.lecturas` (cola propia de query-api) para el WS tiempo real
 * (FEAT-0013 AC-007). Solo broadcast: no persiste. Malformados → log + ack.
 */
@Component
public class LecturasRealtimeConsumer {

    private static final Logger log = LoggerFactory.getLogger(LecturasRealtimeConsumer.class);

    private final Sender sender;
    private final Receiver receiver;
    private final LecturaRealtimeBus bus;
    private final String exchange;
    private final String queue;

    public LecturasRealtimeConsumer(Sender sender, Receiver receiver, LecturaRealtimeBus bus,
                                    @Value("${query.lecturas.exchange:sensor.lecturas}") String exchange,
                                    @Value("${query.lecturas.queue:query.sensor.lecturas}") String queue) {
        this.sender = sender;
        this.receiver = receiver;
        this.bus = bus;
        this.exchange = exchange;
        this.queue = queue;
    }

    @jakarta.annotation.PostConstruct
    void iniciar() {
        sender.declare(ExchangeSpecification.exchange(exchange).type("topic").durable(true))
                .then(sender.declare(QueueSpecification.queue(queue).durable(true)))
                .then(sender.bind(BindingSpecification.binding().exchange(exchange).queue(queue).routingKey("lectura.#")))
                .then(Mono.defer(this::consumir))
                .doOnError(err -> log.error("[query] setup rabbit fallo: {}", err.getMessage()))
                .subscribe();
    }

    private Mono<Void> consumir() {
        Flux<AcknowledgableDelivery> deliveries = receiver.consumeManualAck(queue, new ConsumeOptions().qos(50));
        deliveries
                .flatMap(d -> Mono.fromRunnable(() -> procesar(d)).thenReturn(d))
                .flatMap(d -> Mono.fromRunnable(() -> ack(d)))
                .doOnError(err -> log.error("[query] error consumo realtime: {}", err.getMessage()))
                .subscribe();
        return Mono.empty();
    }

    private void procesar(AcknowledgableDelivery d) {
        try {
            LecturaConsulta l = parseLectura(d.getBody());
            bus.publicar(l);
        } catch (RuntimeException e) {
            // BR-009: el descarte deja evidencia con el motivo (antes era un mensaje genérico)
            log.warn("[query] lectura realtime descartada: {}", e.getMessage());
        }
    }

    private void ack(AcknowledgableDelivery d) {
        try {
            d.ack();
        } catch (Exception e) {
            log.warn("[query] ack fallo: {}", e.getMessage());
        }
    }

    // ============ parseo payload (FIX-0006 BR-006/BR-009: DTO + Jackson) ============

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    /** Versiones mayores ya avisadas (BR-007: un WARN por versión, no por evento). */
    private static final java.util.Set<Integer> MAYORES_AVISADAS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static LecturaConsulta parseLectura(byte[] body) {
        LecturaMensaje m;
        try {
            m = MAPPER.readValue(body, LecturaMensaje.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload malformado: " + e.getMessage());
        }
        if (m == null || m.sensorId() == null || m.timestamp() == null || m.valor() == null
                || m.unidadMedida() == null) {
            throw new IllegalArgumentException("payload incompleto");
        }
        avisarVersion(m.schemaVersion());
        UUID sensorId;
        Instant ts;
        try {
            sensorId = UUID.fromString(m.sensorId().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sensorId no es UUID: " + m.sensorId());
        }
        try {
            ts = Instant.parse(m.timestamp().trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("timestamp no es ISO-8601 con offset: " + m.timestamp());
        }
        String calidad = m.calidad() == null ? null : m.calidad().estado();
        // severidad no viaja en `sensor.lecturas` (la calcula ingestion); el WS la deja null
        return new LecturaConsulta(sensorId, ts, m.valor(), m.unidadMedida().trim(), null, calidad);
    }

    /** FIX-0006 BR-007: evidencia de versión desconocida sin romper el stream (tolerancia). */
    private static void avisarVersion(String schemaVersion) {
        var mayor = VersionSchema.mayorDe(schemaVersion);
        if (mayor.isEmpty()) {
            return;   // sin versión (legado) o mayor soportada: nada que avisar
        }
        if (mayor.getAsInt() > VersionSchema.mayorSoportada(null)
                && MAYORES_AVISADAS.add(mayor.getAsInt())) {
            log.warn("[query] schemaVersion mayor desconocida {}: se entrega igual por tolerancia "
                    + "hacia adelante (aviso único por versión)", schemaVersion);
        }
    }
}

