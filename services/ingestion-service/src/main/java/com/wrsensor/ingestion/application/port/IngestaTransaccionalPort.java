package com.wrsensor.ingestion.application.port;

import com.wrsensor.ingestion.domain.LecturaPersistida;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Port out (FIX-0003 BR-003): persistencia **atomica** de la ingesta —
 * lectura + registro de idempotencia + (opcional) fila de outbox en UNA transaccion
 * R2DBC. Si la clave ya fue procesada: no persiste nada y devuelve
 * {@code persistida=false} (redelivery tolerado, BR-002).
 */
public interface IngestaTransaccionalPort {

    record OutboxAlerta(UUID sensorId, String routingKey, String payload) {}

    record Resultado(boolean persistida) {}

    Mono<Resultado> persistir(LecturaPersistida lectura, String claveIdempotencia, OutboxAlerta outbox);
}
