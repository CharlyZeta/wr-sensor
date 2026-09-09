package com.wrsensor.datasimulator.application.service;

import com.wrsensor.datasimulator.domain.model.SensorSimulado;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Generador de valores sinteticos plausibles (FEAT-0010 BR-003): nivel base +
 * oscilacion diaria (seno de 24 h, amplitud 0.5 m) + ruido gaussiano (sigma en m)
 * + salto opcional de anomalia. Funcion pura (sin I/O ni estado) — deterministico
 * si el ruido y el instante lo son.
 */
public final class ValorSintetico {

    private static final BigDecimal AMPLITUD_DIARIA = new BigDecimal("0.5");

    private ValorSintetico() {}

    /**
     * @param sensor      sensor simulado
     * @param now         instante del tick (define la fase diaria)
     * @param sigmaMetros desvio del ruido gaussiano (config)
     * @param salto       desplazamiento de anomalia (null = sin anomalia)
     * @param ruido       muestra de ruido gaussiano (N(0,1)); 0 = determinista
     */
    public static BigDecimal valor(SensorSimulado sensor, Instant now, BigDecimal sigmaMetros,
                                   BigDecimal salto, double ruido) {
        double horaUtc = now.atOffset(ZoneOffset.UTC).getHour()
                + now.atOffset(ZoneOffset.UTC).getMinute() / 60.0;
        double fase = Math.sin(2 * Math.PI * horaUtc / 24.0); // [-1,1] diario

        BigDecimal valor = sensor.nivelBase()
                .add(AMPLITUD_DIARIA.multiply(BigDecimal.valueOf(fase)))
                .add(sigmaMetros.multiply(BigDecimal.valueOf(ruido)));
        if (salto != null) {
            valor = valor.add(salto);
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
