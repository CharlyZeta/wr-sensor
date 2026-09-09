package com.wrsensor.ingestion.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de severidad saliente (spec §4 AlertaEvento). Publicado por ingestion en
 * cada cambio de severidad (decision HO-Gate FEAT-0011); la histéresis de
 * degradacion la aplica FEAT-0012 antes de notificar.
 */
public record AlertaEvento(
        UUID sensorId,
        Instant timestamp,
        BigDecimal valorLectura,
        Severidad severidadAnterior,
        Severidad severidadNueva,
        boolean cruceHisteresis
) {
}
