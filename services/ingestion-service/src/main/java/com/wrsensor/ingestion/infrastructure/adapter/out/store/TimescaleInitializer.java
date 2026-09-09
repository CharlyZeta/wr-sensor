package com.wrsensor.ingestion.infrastructure.adapter.out.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

/**
 * Schema TimescaleDB idempotente al arranque (BR-003/BR-007; R2DBC no ejecuta
 * multi-sentencias): crea la hypertable `lectura` y llama `create_hypertable`.
 * La extension timescaledb ya viene preload en la imagen (dev/IT).
 */
@Component
public class TimescaleInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimescaleInitializer.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS lectura (
                sensor_id      UUID           NOT NULL,
                ts             TIMESTAMPTZ    NOT NULL,
                valor          NUMERIC(12,2)  NOT NULL,
                unidad_medida  VARCHAR(32)    NOT NULL,
                severidad      VARCHAR(16)    NOT NULL
            )
            """;

    private final DatabaseClient db;

    public TimescaleInitializer(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        db.sql(CREATE_TABLE).fetch().rowsUpdated()
                .then(db.sql("SELECT create_hypertable('lectura', 'ts', if_not_exists => TRUE)")
                        .fetch().rowsUpdated())
                .then(db.sql("CREATE INDEX IF NOT EXISTS idx_lectura_sensor_ts ON lectura (sensor_id, ts DESC)")
                        .fetch().rowsUpdated())
                .then()
                .doOnError(err -> log.error("[ingestion] init TimescaleDB fallo: {}", err.getMessage()))
                .subscribe();
    }
}
