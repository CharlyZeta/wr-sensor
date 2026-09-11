package com.wrsensor.ingestion.infrastructure.adapter.out.store;

import com.wrsensor.ingestion.application.port.IngestaTransaccionalPort;
import com.wrsensor.ingestion.domain.LecturaPersistida;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Adapter out (FIX-0003 BR-003): escribe lectura + registro de idempotencia +
 * fila de outbox en **una sola transacción R2DBC** ({@link TransactionalOperator}).
 * Si la clave ya existe (`ON CONFLICT DO NOTHING` sin filas afectadas) devuelve
 * {@code persistida=false} sin escribir nada más (BR-002, redelivery tolerado).
 * Cualquier fallo posterior (p. ej. outbox inválido) → rollback total (AC-002).
 */
@Component
public class R2dbcIngestaTransaccionalAdapter implements IngestaTransaccionalPort {

    private static final String INSERT_PROCESADA = """
            INSERT INTO lectura_procesada (clave, sensor_id, ts)
            VALUES ($1, $2, $3)
            ON CONFLICT (clave) DO NOTHING
            """;

    private static final String INSERT_LECTURA = """
            INSERT INTO lectura (sensor_id, ts, valor, unidad_medida, severidad, calidad)
            VALUES ($1, $2, $3, $4, $5, $6)
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_alerta (sensor_id, routing_key, payload)
            VALUES ($1, $2, $3)
            """;

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public R2dbcIngestaTransaccionalAdapter(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Mono<Resultado> persistir(LecturaPersistida lectura, String claveIdempotencia, OutboxAlerta outbox) {
        Mono<Resultado> operacion = db.sql(INSERT_PROCESADA)
                .bind(0, claveIdempotencia)
                .bind(1, lectura.sensorId())
                .bind(2, ts(lectura))
                .fetch().rowsUpdated()
                .flatMap(filas -> {
                    if (filas == 0) {
                        return Mono.just(new Resultado(false)); // ya procesada (BR-002)
                    }
                    DatabaseClient.GenericExecuteSpec insert = db.sql(INSERT_LECTURA)
                            .bind(0, lectura.sensorId())
                            .bind(1, ts(lectura))
                            .bind(2, lectura.valor())
                            .bind(3, lectura.unidadMedida())
                            .bind(5, lectura.calidad().name());
                    insert = lectura.severidad() == null
                            ? insert.bindNull(4, String.class)   // FIX-0004: ERROR_SENSOR no evaluada
                            : insert.bind(4, lectura.severidad().name());
                    return insert
                            .fetch().rowsUpdated()
                            .then(outbox == null
                                    ? Mono.empty()
                                    : db.sql(INSERT_OUTBOX)
                                            .bind(0, outbox.sensorId())
                                            .bind(1, outbox.routingKey())
                                            .bind(2, outbox.payload())
                                            .fetch().rowsUpdated().then())
                            .thenReturn(new Resultado(true));
                });
        return tx.transactional(operacion);
    }

    private static LocalDateTime ts(LecturaPersistida lectura) {
        return LocalDateTime.ofInstant(lectura.ts(), ZoneOffset.UTC);
    }
}
