package com.wrsensor.queryapi.application.port;

import com.wrsensor.queryapi.domain.LecturaConsulta;
import reactor.core.publisher.Flux;

/**
 * Port out (FEAT-0008 BR-006): última lectura de **todos** los sensores en una sola consulta a la
 * hypertable {@code lectura} ({@code DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC}).
 *
 * <p>Existe como port propio (y no como método de {@code LecturasPort}) porque su contrato es
 * distinto: no filtra por sensor ni por rango, y el resumen del mapa depende de que se resuelva
 * con **una** invocación — nunca N+1.</p>
 */
public interface UltimasLecturasPort {

    /** Una fila por sensor con lecturas, ordenada por {@code sensor_id}; vacío si no hay ninguna. */
    Flux<LecturaConsulta> ultimasPorSensor();
}
