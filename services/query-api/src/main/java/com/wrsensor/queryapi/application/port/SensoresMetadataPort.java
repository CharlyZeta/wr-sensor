package com.wrsensor.queryapi.application.port;

import com.wrsensor.queryapi.domain.SensorMetadata;
import reactor.core.publisher.Flux;

/**
 * Port out (FEAT-0008 BR-007): metadata de **todos** los sensores, resuelta contra
 * {@code sensor-registry} por REST.
 *
 * <p>El adapter es dueño del protocolo (paginación keyset hasta agotar, tope de seguridad, timeout
 * explícito). Si el registry no responde o responde con error, el flujo falla con
 * {@code REGISTRY_UNAVAILABLE} y **no** emite items parciales: el contrato prohíbe un mapa a medias
 * que parezca completo (AF-06).</p>
 */
public interface SensoresMetadataPort {

    Flux<SensorMetadata> listar();
}
