package com.wrsensor.sensorregistry.infrastructure.adapter.out.persistence;

import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.ListSensorsPort;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.TipoSensor;
import com.wrsensor.sensorregistry.domain.model.UnidadMedida;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
/**
 * Adapter out (persistence R2DBC) para listing keyset de sensores (FEAT-0002).
 *
 * <p>BR-001: keyset, nunca OFFSET. BR-004: orden por fecha_instalacion DESC, id ASC.
 * El cursor {@code (afterFecha, afterId)} acota filas "posteriores" en ese orden
 * (fecha estrictamente menor, o igual fecha + id estrictamente mayor para el
 * desempate ASC). Si {@code afterFecha == null} → desde el inicio (sin WHERE).
 *
 * <p>Reusa {@link DatabaseClient} reactivo (mismo bean que {@link SensorPersistenceAdapter}).
 * El mapeo fila→Sensor refleja el schema.sql de FEAT-0001 (columnas snake_case;
 * DOUBLE→BigDecimal, TIMESTAMP→Instant interpretado como UTC, pues el adapter de
 * escritura almacena {@code java.sql.Timestamp.from(Instant)}).
 */
@Component
public class SensorListPersistenceAdapter implements ListSensorsPort, FindSensorByIdPort, UpdateSensorPort {

    private static final String SQL = """
            SELECT id, codigo, nombre, tipo, latitud, longitud, unidad_medida, estado,
                   histeresis, frecuencia_reporte_segundos, fecha_instalacion,
                   rango_normal_min, rango_normal_max,
                   rango_warning_min, rango_warning_max,
                   rango_critical_min, rango_critical_max
            FROM sensor
            WHERE ( :afterFecha IS NULL )
               OR ( fecha_instalacion < :afterFecha )
               OR ( fecha_instalacion = :afterFecha AND id > :afterId )
            ORDER BY fecha_instalacion DESC, id ASC
            LIMIT :limit
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, codigo, nombre, tipo, latitud, longitud, unidad_medida, estado,
                   histeresis, frecuencia_reporte_segundos, fecha_instalacion,
                   rango_normal_min, rango_normal_max,
                   rango_warning_min, rango_warning_max,
                   rango_critical_min, rango_critical_max
            FROM sensor
            WHERE id = $1
            LIMIT 1
            """;

    private final DatabaseClient db;

    public SensorListPersistenceAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Sensor> findById(java.util.UUID id) {
        return db.sql(FIND_BY_ID_SQL)
                .bind(0, id)
                .map((row, meta) -> mapRow(row))
                .one();
    }

    private static final String UPDATE_SQL = """
            UPDATE sensor SET
                estado = $2,
                histeresis = $3,
                frecuencia_reporte_segundos = $4,
                rango_normal_min = $5,
                rango_normal_max = $6,
                rango_warning_min = $7,
                rango_warning_max = $8,
                rango_critical_min = $9,
                rango_critical_max = $10
            WHERE id = $1
            """;

    /** FEAT-0004: persiste solo el subset config editable (BR-004) y relee el sensor. */
    @Override
    public Mono<Sensor> update(Sensor s) {
        return db.sql(UPDATE_SQL)
                .bind(0, s.id())
                .bind(1, s.estado().name())
                .bind(2, s.histeresis())
                .bind(3, s.frecuenciaReporteSegundos())
                .bind(4, s.rangoNormal().min())
                .bind(5, s.rangoNormal().max())
                .bind(6, s.rangoWarning().min())
                .bind(7, s.rangoWarning().max())
                .bind(8, s.rangoCritical().min())
                .bind(9, s.rangoCritical().max())
                .fetch().rowsUpdated()
                .then(findById(s.id()));
    }

    @Override
    public Flux<Sensor> listAfter(Instant afterFecha, UUID afterId, int limit) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(SQL);
        // FEAT-0002 fix (keyset tie-break): bind naive-UTC LocalDateTime. java.sql.Timestamp
        // era convertido por Spring R2DBC a wall-clock de la zona local (UTC-3), desplazando
        // la comparacion y rompiendo "fecha = :afterFecha" en el empate (BR-003/BR-004/AC-012).
        // LocalDateTime.ofInstant(UTC) deja almacen y predicado en el mismo espacio naive-UTC.
        spec = (afterFecha == null)
                ? spec.bindNull("afterFecha", LocalDateTime.class)
                : spec.bind("afterFecha", LocalDateTime.ofInstant(afterFecha, ZoneOffset.UTC));
        spec = (afterId == null)
                ? spec.bindNull("afterId", UUID.class)
                : spec.bind("afterId", afterId);
        return spec.bind("limit", limit)
                .map((row, meta) -> mapRow(row))
                .all();
    }

    private static Sensor mapRow(Row row) {
        return new Sensor(
                readUuid(row, "id"),
                row.get("codigo", String.class),
                row.get("nombre", String.class),
                TipoSensor.valueOf(row.get("tipo", String.class)),
                readDecimal(row, "latitud"),
                readDecimal(row, "longitud"),
                UnidadMedida.valueOf(row.get("unidad_medida", String.class)),
                EstadoSensor.valueOf(row.get("estado", String.class)),
                readDecimal(row, "histeresis"),
                row.get("frecuencia_reporte_segundos", Integer.class),
                readInstant(row, "fecha_instalacion"),
                new Rango(readDecimal(row, "rango_normal_min"), readDecimal(row, "rango_normal_max")),
                new Rango(readDecimal(row, "rango_warning_min"), readDecimal(row, "rango_warning_max")),
                new Rango(readDecimal(row, "rango_critical_min"), readDecimal(row, "rango_critical_max"))
        );
    }

    private static UUID readUuid(Row row, String col) {
        Object v = row.get(col);
        if (v instanceof UUID u) return u;
        if (v instanceof String s) return UUID.fromString(s);
        throw new IllegalStateException("tipo inesperado para " + col + ": " + (v == null ? "null" : v.getClass()));
    }

    private static BigDecimal readDecimal(Row row, String col) {
        Object v = row.get(col);
        if (v == null) throw new IllegalStateException(col + " es null");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Float f) return BigDecimal.valueOf(f.doubleValue());
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        throw new IllegalStateException("tipo inesperado para " + col + ": " + v.getClass());
    }

    private static Instant readInstant(Row row, String col) {
        Object v = row.get(col);
        if (v == null) throw new IllegalStateException(col + " es null");
        if (v instanceof Instant i) return i;
        if (v instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC);
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("tipo inesperado para " + col + ": " + v.getClass());
    }
}
