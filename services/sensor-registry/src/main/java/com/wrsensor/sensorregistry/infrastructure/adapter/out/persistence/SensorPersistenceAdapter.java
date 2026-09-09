package com.wrsensor.sensorregistry.infrastructure.adapter.out.persistence;

import com.wrsensor.sensorregistry.application.port.out.ContainsSensorWithCodePort;
import com.wrsensor.sensorregistry.application.port.out.SaveSensorPort;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Adapter out (persistence R2DBC) que implementa los ports de salida de Sensor.
 *
 * <p>Usa {@link DatabaseClient} reactivo (sin Spring Data R2DBC repositories
 * declarativos) para minimizar bias hacia JPA-like y focalizar en queries SQL
 * explicitas. El adapter traduce entidad de dominio ↔ fila de tabla `sensor`.
 */
@Component
public class SensorPersistenceAdapter implements SaveSensorPort, ContainsSensorWithCodePort {

    private final DatabaseClient db;

    public SensorPersistenceAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Sensor> save(Sensor s) {
        var sql = """
                INSERT INTO sensor (
                    id, codigo, nombre, tipo, latitud, longitud, unidad_medida, estado,
                    histeresis, frecuencia_reporte_segundos, fecha_instalacion,
                    rango_normal_min, rango_normal_max,
                    rango_warning_min, rango_warning_max,
                    rango_critical_min, rango_critical_max
                ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)
                """;
        return db.sql(sql)
                .bind(0, s.id())
                .bind(1, s.codigo())
                .bind(2, s.nombre())
                .bind(3, s.tipo().name())
                .bind(4, s.latitud())
                .bind(5, s.longitud())
                .bind(6, s.unidadMedida().name())
                .bind(7, s.estado().name())
                .bind(8, s.histeresis())
                .bind(9, s.frecuenciaReporteSegundos())
                // FEAT-0002 fix (keyset tie-break): bind naive-UTC LocalDateTime, nunca
                // java.sql.Timestamp (Spring R2DBC lo convierte a wall-clock local y
                // desplaza la comparacion en cada cruce write/read/predicado).
                .bind(10, LocalDateTime.ofInstant(s.fechaInstalacion(), ZoneOffset.UTC))
                .bind(11, s.rangoNormal().min())
                .bind(12, s.rangoNormal().max())
                .bind(13, s.rangoWarning().min())
                .bind(14, s.rangoWarning().max())
                .bind(15, s.rangoCritical().min())
                .bind(16, s.rangoCritical().max())
                .fetch().rowsUpdated()
                .thenReturn(s);
    }

    @Override
    public Mono<Boolean> existsByCodigo(String codigo) {
        return db.sql("SELECT 1 FROM sensor WHERE codigo = $1 LIMIT 1")
                .bind(0, codigo)
                .map(row -> 1)
                .first()
                .hasElement();
    }
}
