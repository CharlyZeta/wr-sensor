package com.wrsensor.sensorregistry.application.port.in;

import com.wrsensor.sensorregistry.domain.model.Sensor;

/**
 * Use case port (in): creacion de un sensor. Es el puerto que el adapter web
 * invoca. El input DTO aqui es el del applicacion (no el del controller) para
 * mantener hexagonal: el controller mapea web DTO → comando de aplicacion.
 */
public interface CreateSensorUseCase {

    /** Comando de creacion. La capa de aplicacion genera id y fechaInstalacion. */
    record CreateSensorCommand(
            String codigo,
            String nombre,
            String tipo,
            java.math.BigDecimal latitud,
            java.math.BigDecimal longitud,
            String unidadMedida,
            String estado,
            java.math.BigDecimal histeresis,
            int frecuenciaReporteSegundos,
            com.wrsensor.sensorregistry.domain.model.Rango rangoNormal,
            com.wrsensor.sensorregistry.domain.model.Rango rangoWarning,
            com.wrsensor.sensorregistry.domain.model.Rango rangoCritical
    ) {}

    reactor.core.publisher.Mono<Sensor> createSensor(CreateSensorCommand command);
}
