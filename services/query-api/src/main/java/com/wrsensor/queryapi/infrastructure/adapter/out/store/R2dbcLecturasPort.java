package com.wrsensor.queryapi.infrastructure.adapter.out.store;

import com.wrsensor.queryapi.application.port.LecturasPort;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Adapter out (R2DBC): consulta de la hypertable `lectura` (BR-001). Timestamps
 * bindeados como OffsetDateTime UTC (columna timestamptz).
 *
 * <p>Implementa también {@link UltimasLecturasPort} (FEAT-0008 BR-006): el resumen del mapa
 * resuelve la última lectura de todos los sensores con un único {@code DISTINCT ON}.</p>
 */
@Component
public class R2dbcLecturasPort
        implements LecturasPort, com.wrsensor.queryapi.application.port.UltimasLecturasPort {

    private static final String LISTAR = """
            SELECT sensor_id, ts, valor, unidad_medida, severidad, calidad
            FROM lectura
            WHERE sensor_id = $1 AND ts >= $2 AND ts <= $3
              AND ($4::timestamptz IS NULL OR ts < $4)
            ORDER BY ts DESC
            LIMIT $5
            """;

    private static final String ULTIMA = """
            SELECT sensor_id, ts, valor, unidad_medida, severidad, calidad
            FROM lectura
            WHERE sensor_id = $1
            ORDER BY ts DESC
            LIMIT 1
            """;

    /**
     * FEAT-0008 BR-006: última lectura de todos los sensores en **una sola** consulta.
     * {@code DISTINCT ON (sensor_id)} con {@code ORDER BY sensor_id, ts DESC} es el idioma de
     * Postgres para "la fila más nueva por grupo" y es lo que exige el AC-010 (nunca N+1).
     */
    private static final String ULTIMAS_POR_SENSOR = """
            SELECT DISTINCT ON (sensor_id) sensor_id, ts, valor, unidad_medida, severidad, calidad
            FROM lectura
            ORDER BY sensor_id, ts DESC
            """;

    private final DatabaseClient db;

    public R2dbcLecturasPort(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<LecturaConsulta> listar(UUID sensorId, Instant desde, Instant hasta,
                                        Instant afterTs, int limit) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(LISTAR)
                .bind(0, sensorId)
                .bind(1, OffsetDateTime.ofInstant(desde, ZoneOffset.UTC))
                .bind(2, OffsetDateTime.ofInstant(hasta, ZoneOffset.UTC))
                .bind(4, limit);
        spec = afterTs == null
                ? spec.bindNull(3, OffsetDateTime.class)
                : spec.bind(3, OffsetDateTime.ofInstant(afterTs, ZoneOffset.UTC));
        return spec.map((row, meta) -> mapRow(sensorId, row.get("ts", OffsetDateTime.class).toInstant(),
                        (BigDecimal) row.get("valor"), row.get("unidad_medida", String.class),
                        str(row, "severidad"), str(row, "calidad")))
                .all();
    }

    @Override
    public Mono<LecturaConsulta> ultima(UUID sensorId) {
        return db.sql(ULTIMA)
                .bind(0, sensorId)
                .map((row, meta) -> mapRow(sensorId, row.get("ts", OffsetDateTime.class).toInstant(),
                        (BigDecimal) row.get("valor"), row.get("unidad_medida", String.class),
                        str(row, "severidad"), str(row, "calidad")))
                .one();
    }

    private static LecturaConsulta mapRow(UUID sensorId, Instant ts, BigDecimal valor,
                                          String unidad, String severidad, String calidad) {
        return new LecturaConsulta(sensorId, ts, valor, unidad, severidad, calidad);
    }

    @Override
    public Flux<LecturaConsulta> ultimasPorSensor() {
        return db.sql(ULTIMAS_POR_SENSOR)
                .map((row, meta) -> mapRow(row.get("sensor_id", UUID.class),
                        row.get("ts", OffsetDateTime.class).toInstant(),
                        (BigDecimal) row.get("valor"), row.get("unidad_medida", String.class),
                        str(row, "severidad"), str(row, "calidad")))
                .all();
    }

    /** FIX-0004: `severidad` puede ser NULL (lectura ERROR_SENSOR) — get tipado lanza NPE. */
    private static String str(io.r2dbc.spi.Row row, String col) {
        Object v = row.get(col);
        return v == null ? null : v.toString();
    }
}


