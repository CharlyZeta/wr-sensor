package com.wrsensor.alerting.domain;

import java.time.Instant;
import java.util.UUID;

/** Alerta confirmada por histéresis — se notifica por WS (AC-007). */
public record AlertaConfirmada(
        UUID sensorId,
        Instant timestamp,
        Severidad severidadNueva
) {
}
