package com.wrsensor.sensorregistry.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entidad Sensor (owner=sensor-registry). Objeto de dominio puro: sin imports
 * de Spring ni de Reactor. Invariantes BR-001..BR-009 son responsabilidad de
 * la capa de aplicacion (validacion) — el record solo materializa el estado.
 *
 * <p>Atributos segun Contract FEAT-0001 §Entities Affected. fechaInstalacion
 * es system-set: la establece el caso de uso al crear (now), no la provee el
 * cliente (decision del Ambiguity Log: opcion (a)).
 */
public record Sensor(
        UUID id,
        String codigo,
        String nombre,
        TipoSensor tipo,
        BigDecimal latitud,
        BigDecimal longitud,
        UnidadMedida unidadMedida,
        EstadoSensor estado,
        BigDecimal histeresis,
        int frecuenciaReporteSegundos,
        Instant fechaInstalacion,
        Rango rangoNormal,
        Rango rangoWarning,
        Rango rangoCritical
) {

    public Sensor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(codigo, "codigo");
        Objects.requireNonNull(nombre, "nombre");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(latitud, "latitud");
        Objects.requireNonNull(longitud, "longitud");
        Objects.requireNonNull(unidadMedida, "unidadMedida");
        Objects.requireNonNull(estado, "estado");
        Objects.requireNonNull(histeresis, "histeresis");
        Objects.requireNonNull(fechaInstalacion, "fechaInstalacion");
        Objects.requireNonNull(rangoNormal, "rangoNormal");
        Objects.requireNonNull(rangoWarning, "rangoWarning");
        Objects.requireNonNull(rangoCritical, "rangoCritical");
    }
}
