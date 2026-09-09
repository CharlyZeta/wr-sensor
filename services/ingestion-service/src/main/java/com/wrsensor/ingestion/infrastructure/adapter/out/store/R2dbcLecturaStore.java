package com.wrsensor.ingestion.infrastructure.adapter.out.store;

import com.wrsensor.ingestion.application.port.LecturaStore;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Adapter out (persistencia R2DBC): INSERT en hypertable `lectura` (BR-003).
 * Binding naive-UTC (mismo criterio que sensor-registry FEAT-0002 fix).
 */
@Component
public class R2dbcLecturaStore implements LecturaStore {

    private static final String INSERT = """
            INSERT INTO lectura (sensor_id, ts, valor, unidad_medida, severidad)
            VALUES ($1, $2, $3, $4, $5)
            """;

    private final DatabaseClient db;

    public R2dbcLecturaStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Void> insert(LecturaPersistida l) {
        return db.sql(INSERT)
                .bind(0, l.sensorId())
                .bind(1, LocalDateTime.ofInstant(l.ts(), ZoneOffset.UTC))
                .bind(2, l.valor())
                .bind(3, l.unidadMedida())
                .bind(4, l.severidad().name())
                .fetch().rowsUpdated()
                .then();
    }
}
