package com.wrsensor.ingestion.infrastructure.adapter.out.messaging;

import com.rabbitmq.client.AMQP;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Publisher de outbox (FIX-0003 BR-004..BR-008): poller **reactivo** que reclama
 * filas pendientes (claim por lote con {@code FOR UPDATE SKIP LOCKED}), publica en
 * `sensor.alertas` **en orden de id** (BR-006), marca ENVIADO y reintenta con
 * backoff configurable; al agotar intentos → FALLIDO + ultimo_error (BR-007).
 * También purga dedupe/outbox por retención (BR-008).
 */
@Component
public class OutboxAlertaPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxAlertaPoller.class);

    private final Sender sender;
    private final DatabaseClient db;
    private final IngestionProperties props;

    public OutboxAlertaPoller(Sender sender, DatabaseClient db, IngestionProperties props) {
        this.sender = sender;
        this.db = db;
        this.props = props;
    }

    @jakarta.annotation.PostConstruct
    void iniciar() {
        sender.declare(reactor.rabbitmq.ExchangeSpecification
                        .exchange(props.alertas().exchange()).type("topic").durable(true))
                .doOnError(err -> log.warn("[outbox] no se pudo declarar exchange: {}", err.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then(Mono.defer(this::loop))
                .subscribe();
    }

    /** Loop recursivo: tick → espera (intervalo o backoff si hubo fallos) → tick. */
    private Mono<Void> loop() {
        return tick()
                .flatMap(fallos -> Mono.delay(delay(fallos)).then(loop()))
                .then();
    }

    private Duration delay(int fallos) {
        IngestionProperties.Outbox o = props.outbox();
        if (fallos == 0) return Duration.ofMillis(o.intervaloMs());
        long backoff = (long) (o.backoffInicialMs() * Math.pow(o.multiplicador(), Math.max(0, fallos - 1)));
        return Duration.ofMillis(Math.min(backoff, o.backoffMaxMs()));
    }

    /** Reclama lote, publica en orden, marca y purga. Devuelve la cantidad de fallos. */
    Mono<Integer> tick() {
        IngestionProperties.Outbox o = props.outbox();
        String claim = """
                UPDATE outbox_alerta SET estado = 'ENVIANDO', intentos = intentos + 1
                WHERE id IN (
                    SELECT id FROM outbox_alerta
                    WHERE estado IN ('PENDIENTE', 'FALLIDO') AND intentos < $1
                    ORDER BY id
                    LIMIT $2
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, sensor_id, routing_key, payload, intentos
                """;
        return db.sql(claim).bind(0, o.maxIntentos()).bind(1, o.tamanoLote())
                .map((row, meta) -> new Fila(
                        row.get("id", Long.class),
                        row.get("sensor_id", UUID.class),
                        row.get("routing_key", String.class),
                        row.get("payload", String.class),
                        row.get("intentos", Integer.class)))
                .all()
                .sort(java.util.Comparator.comparing(Fila::id)) // RETURNING no garantiza orden → BR-006
                .concatMap(this::publicar) // orden por id (BR-006)
                .filter(fallo -> fallo)
                .count()
                .map(Long::intValue)
                .flatMap(fallos -> purgar().thenReturn(fallos))
                .onErrorResume(err -> {
                    log.error("[outbox] tick fallo: {}", err.getMessage());
                    return Mono.just(1);
                });
    }

    private record Fila(Long id, UUID sensorId, String routingKey, String payload, Integer intentos) {}

    /** @return true si la publicacion fallo (para backoff). */
    private Mono<Boolean> publicar(Fila fila) {
        OutboundMessage msg = new OutboundMessage(props.alertas().exchange(), fila.routingKey(),
                new AMQP.BasicProperties.Builder().contentType("application/json")
                        .headers(java.util.Map.of("claveIdempotencia",
                                ("outbox:" + fila.id()).getBytes(StandardCharsets.UTF_8)))
                        .build(),
                fila.payload().getBytes(StandardCharsets.UTF_8));
        return sender.send(Mono.just(msg))
                .then(marcarEnviado(fila.id()))
                .thenReturn(false)
                .onErrorResume(err -> marcarFallo(fila.id(), fila.intentos(), err).thenReturn(true));
    }

    private Mono<Void> marcarEnviado(Long id) {
        return db.sql("UPDATE outbox_alerta SET estado = 'ENVIADO', enviado_en = now(), ultimo_error = NULL WHERE id = $1")
                .bind(0, id).fetch().rowsUpdated().then();
    }

    /** BR-007: agotados los intentos → FALLIDO + ultimo_error (nunca se descarta). */
    private Mono<Void> marcarFallo(Long id, Integer intentos, Throwable err) {
        boolean agotado = intentos != null && intentos >= props.outbox().maxIntentos();
        String estado = agotado ? "FALLIDO" : "PENDIENTE";
        String mensaje = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
        log.warn("[outbox] publicacion fallo id={} intentos={} → {} ({})", id, intentos, estado, mensaje);
        return db.sql("UPDATE outbox_alerta SET estado = $1, ultimo_error = $2 WHERE id = $3")
                .bind(0, estado).bind(1, mensaje.substring(0, Math.min(500, mensaje.length())))
                .bind(2, id).fetch().rowsUpdated().then();
    }

    /** BR-008: purga por retención (lotes acotados), sin tocar la hypertable `lectura`. */
    Mono<Void> purgar() {
        int dias = props.outbox().retencionDias();
        int lote = props.outbox().tamanoLote();
        return db.sql("""
                        DELETE FROM outbox_alerta WHERE id IN (
                            SELECT id FROM outbox_alerta
                            WHERE estado = 'ENVIADO' AND enviado_en < now() - make_interval(days => $1::int)
                            LIMIT $2::int)
                        """)
                .bind(0, dias).bind(1, lote).fetch().rowsUpdated()
                .then(db.sql("""
                        DELETE FROM lectura_procesada WHERE clave IN (
                            SELECT clave FROM lectura_procesada
                            WHERE procesado_en < now() - make_interval(days => $1::int)
                            LIMIT $2::int)
                        """)
                        .bind(0, dias).bind(1, lote).fetch().rowsUpdated())
                .then();
    }

    /** Visible para tests: procesa un lote inmediatamente. */
    public Mono<Integer> procesarLoteAhora() {
        return tick();
    }

    /** Visible para tests: purga inmediata. */
    public Mono<Void> purgarAhora() {
        return purgar();
    }

    Flux<Long> noop() {
        return Flux.empty();
    }
}

