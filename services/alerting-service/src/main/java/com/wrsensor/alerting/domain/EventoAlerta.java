package com.wrsensor.alerting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Evento de entrada de `sensor.alertas` (payload FEAT-0011). */
public record EventoAlerta(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valorLectura,
        Severidad severidadAnterior,
        Severidad severidadNueva,
        boolean cruceHisteresis
) {
}
