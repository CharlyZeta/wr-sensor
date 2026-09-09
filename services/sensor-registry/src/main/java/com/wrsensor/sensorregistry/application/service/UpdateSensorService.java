package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.UpdateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.FindSensorByIdPort;
import com.wrsensor.sensorregistry.application.port.out.UpdateSensorPort;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.InvalidEstadoException;
import com.wrsensor.sensorregistry.domain.model.InvalidFrecuenciaException;
import com.wrsensor.sensorregistry.domain.model.InvalidHisteresisException;
import com.wrsensor.sensorregistry.domain.model.InvalidRangesException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorNotFoundException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * Implementacion del caso de uso "Update Sensor" (Main Flow FEAT-0004).
 *
 * <p>BR-002: sensor inexistente → {@link SensorNotFoundException} (404). BR-006:
 * invariantes de configuracion identicas al alta (histeresis >= 0, frecuencia > 0,
 * cadena inclusiva de rangos). BR-007: {@code estado=INACTIVO} se rechaza en PUT
 * (baja logica = FEAT-0005). BR-008: solo se persiste la configuracion (identidad
 * del sensor intacta; sin reproceso de lecturas). Aplicacion sin Spring — los
 * validadores replican los de {@code CreateSensorService}.
 */
public class UpdateSensorService implements UpdateSensorUseCase {

    private final FindSensorByIdPort findPort;
    private final UpdateSensorPort updatePort;

    public UpdateSensorService(FindSensorByIdPort findPort, UpdateSensorPort updatePort) {
        this.findPort = findPort;
        this.updatePort = updatePort;
    }

    @Override
    public Mono<Sensor> update(UUID id, UpdateSensorCommand command) {
        return Mono.defer(() -> {
                    // validaciones sincronicas (no I/O), mismas reglas que el alta
                    EstadoSensor estado = parseEstadoEditable(command.estado());
                    validateHisteresis(command.histeresis());
                    validateFrecuencia(command.frecuenciaReporteSegundos());
                    validateRanges(command.rangoNormal(), command.rangoWarning(), command.rangoCritical());
                    return findPort.findById(id)
                            .switchIfEmpty(Mono.error(new SensorNotFoundException()))
                            .map(existing -> updated(existing, estado, command));
                })
                .flatMap(updatePort::update);
    }

    /** BR-004/BR-007: solo cambian los campos editables; la identidad se conserva. */
    private static Sensor updated(Sensor existing, EstadoSensor estado, UpdateSensorCommand cmd) {
        return new Sensor(
                existing.id(), existing.codigo(), existing.nombre(), existing.tipo(),
                existing.latitud(), existing.longitud(), existing.unidadMedida(),
                estado, cmd.histeresis(), cmd.frecuenciaReporteSegundos(),
                existing.fechaInstalacion(),
                cmd.rangoNormal(), cmd.rangoWarning(), cmd.rangoCritical());
    }

    // --- BR-007: estado editable {ACTIVO, MANTENIMIENTO}; INACTIVO es FEAT-0005 ---
    private static EstadoSensor parseEstadoEditable(String estado) {
        EstadoSensor parsed;
        try {
            parsed = EstadoSensor.valueOf(estado == null ? "" : estado.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidEstadoException();
        }
        if (parsed == EstadoSensor.INACTIVO) {
            throw new InvalidEstadoException();
        }
        return parsed;
    }

    // --- BR-006: mismas invariantes que el alta (FEAT-0001) ---
    private static void validateHisteresis(BigDecimal h) {
        if (h == null || h.signum() < 0) throw new InvalidHisteresisException();
    }

    private static void validateFrecuencia(int f) {
        if (f <= 0) throw new InvalidFrecuenciaException();
    }

    private static void validateRanges(Rango normal, Rango warning, Rango critical) {
        if (normal == null || warning == null || critical == null) {
            throw new InvalidRangesException("rangos requeridos");
        }
        if (warning.min().compareTo(critical.min()) < 0
                || normal.min().compareTo(warning.min()) < 0
                || normal.max().compareTo(warning.max()) > 0
                || warning.max().compareTo(critical.max()) > 0) {
            throw new InvalidRangesException(
                    "rangoCritical.min ≤ rangoWarning.min ≤ rangoNormal.min ≤ rangoNormal.max"
                            + " ≤ rangoWarning.max ≤ rangoCritical.max");
        }
    }
}
