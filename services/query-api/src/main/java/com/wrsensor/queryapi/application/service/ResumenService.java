package com.wrsensor.queryapi.application.service;

import com.wrsensor.queryapi.application.port.SensoresMetadataPort;
import com.wrsensor.queryapi.application.port.UltimasLecturasPort;
import com.wrsensor.queryapi.domain.LecturaConsulta;
import com.wrsensor.queryapi.domain.ResumenSensor;
import com.wrsensor.queryapi.domain.SensorMetadata;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Caso de uso del resumen del mapa (FEAT-0008 BR-005/BR-006/BR-007).
 *
 * <p>Compone dos fuentes en **una** pasada: la metadata de todos los sensores (registry por REST,
 * paginada) y la última lectura de cada uno (una sola consulta a la hypertable). El orden de salida
 * es el del registry y **todos** los sensores aparecen: los que no tienen lecturas van con
 * {@code ultimaLectura} nulo (AF-05). Como se espera a las dos fuentes antes de emitir, un fallo
 * del registry no produce una lista parcial (AF-06).</p>
 */
public class ResumenService {

    private final SensoresMetadataPort metadata;
    private final UltimasLecturasPort lecturas;

    public ResumenService(SensoresMetadataPort metadata, UltimasLecturasPort lecturas) {
        this.metadata = metadata;
        this.lecturas = lecturas;
    }

    public Mono<List<ResumenSensor>> resumen() {
        return Mono.zip(metadata.listar().collectList(),
                        lecturas.ultimasPorSensor().collectMap(LecturaConsulta::sensorId))
                .map(fuentes -> armar(fuentes.getT1(), fuentes.getT2()));
    }

    private static List<ResumenSensor> armar(List<SensorMetadata> sensores,
                                             Map<UUID, LecturaConsulta> ultimas) {
        return sensores.stream()
                .map(meta -> ResumenSensor.de(meta, ultimas.get(meta.id())))
                .toList();
    }
}
