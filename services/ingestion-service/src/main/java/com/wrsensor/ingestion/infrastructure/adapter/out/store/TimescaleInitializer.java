package com.wrsensor.ingestion.infrastructure.adapter.out.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Schema idempotente al arranque: hypertable `lectura` (BR-003 FEAT-0011) +
 * tablas de trabajo de FIX-0003: `lectura_procesada` (dedupe por clave) y
 * `outbox_alerta` (patrón outbox). Por BR-009 estas dos son tablas **normales**
 * (no hypertables, sin políticas de compresión).
 */
@Component
public class TimescaleInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimescaleInitializer.class);

    private static final String CREATE_LECTURA = """
            CREATE TABLE IF NOT EXISTS lectura (
                sensor_id      UUID           NOT NULL,
                ts             TIMESTAMPTZ    NOT NULL,
                valor          NUMERIC(12,2)  NOT NULL,
                unidad_medida  VARCHAR(32)    NOT NULL,
                severidad      VARCHAR(16)    NOT NULL
            )
            """;

    private static final String CREATE_PROCESADA = """
            CREATE TABLE IF NOT EXISTS lectura_procesada (
                clave         VARCHAR(160)  PRIMARY KEY,
                sensor_id     UUID          NOT NULL,
                ts            TIMESTAMPTZ   NOT NULL,
                procesado_en  TIMESTAMPTZ   NOT NULL DEFAULT now()
            )
            """;

    private static final String CREATE_OUTBOX = """
            CREATE TABLE IF NOT EXISTS outbox_alerta (
                id            BIGSERIAL     PRIMARY KEY,
                sensor_id     UUID          NOT NULL,
                routing_key   VARCHAR(120)  NOT NULL,
                payload       TEXT          NOT NULL,
                estado        VARCHAR(16)   NOT NULL DEFAULT 'PENDIENTE',
                intentos      INTEGER       NOT NULL DEFAULT 0,
                ultimo_error  TEXT,
                creado_en     TIMESTAMPTZ   NOT NULL DEFAULT now(),
                enviado_en    TIMESTAMPTZ
            )
            """;

    private final DatabaseClient db;

    public TimescaleInitializer(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Bloqueante a proposito: el schema debe existir ANTES de aceptar trafico
        // (arranque, no camino de request). Tolerante a entornos sin extension
        // TimescaleDB (create_hypertable se omite con warning).
        db.sql(CREATE_LECTURA).fetch().rowsUpdated()
                .then(db.sql("SELECT create_hypertable('lectura', 'ts', if_not_exists => TRUE)")
                        .fetch().rowsUpdated()
                        .onErrorResume(err -> {
                            log.warn("[ingestion] create_hypertable no aplicado (¿sin extension timescaledb?): {}",
                                    err.getMessage());
                            return Mono.empty();
                        }))
                .then(db.sql("CREATE INDEX IF NOT EXISTS idx_lectura_sensor_ts ON lectura (sensor_id, ts DESC)")
                        .fetch().rowsUpdated())
                .then(db.sql(CREATE_PROCESADA).fetch().rowsUpdated())
                .then(db.sql("CREATE INDEX IF NOT EXISTS idx_procesada_purga ON lectura_procesada (procesado_en)")
                        .fetch().rowsUpdated())
                .then(db.sql(CREATE_OUTBOX).fetch().rowsUpdated())
                .then(db.sql("CREATE INDEX IF NOT EXISTS idx_outbox_estado_id ON outbox_alerta (estado, id)")
                        .fetch().rowsUpdated())
                .then()
                .doOnError(err -> log.error("[ingestion] init schema fallo: {}", err.getMessage()))
                .onErrorResume(err -> Mono.empty())
                .block();
    }
}
