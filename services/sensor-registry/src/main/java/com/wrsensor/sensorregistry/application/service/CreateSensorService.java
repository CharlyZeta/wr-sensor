package com.wrsensor.sensorregistry.application.service;

import com.wrsensor.sensorregistry.application.port.in.CreateSensorUseCase;
import com.wrsensor.sensorregistry.application.port.out.ContainsSensorWithCodePort;
import com.wrsensor.sensorregistry.application.port.out.PublishSensorCreatedPort;
import com.wrsensor.sensorregistry.application.port.out.SaveSensorPort;
import com.wrsensor.sensorregistry.domain.model.EstadoSensor;
import com.wrsensor.sensorregistry.domain.model.InvalidCoordinatesException;
import com.wrsensor.sensorregistry.domain.model.InvalidFrecuenciaException;
import com.wrsensor.sensorregistry.domain.model.InvalidHisteresisException;
import com.wrsensor.sensorregistry.domain.model.InvalidRangesException;
import com.wrsensor.sensorregistry.domain.model.Rango;
import com.wrsensor.sensorregistry.domain.model.Sensor;
import com.wrsensor.sensorregistry.domain.model.SensorCodeDuplicatedException;
import com.wrsensor.sensorregistry.domain.model.TipoSensor;
import com.wrsensor.sensorregistry.domain.model.UnidadMedida;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Implementacion del caso de uso "Create Sensor" (Main Flow FEAT-0001).
 *
 * <p>Valida los invariantes BR-001..BR-009 contra el dominio (sin Spring/Reactor
 * en las reglas — el flujo reactivo solo orquesta ports) y, si todo pasa:
 * <ol>
 *   <li>genera id (UUID) y fechaInstalacion (now) — decision Ambiguity Log (a): system-set</li>
 *   <li>persiste via {@link SaveSensorPort}</li>
 *   <li>publica evento "sensor creado" via {@link PublishSensorCreatedPort}</li>
 * </ol>
 *
 * <p>Las validaciones BR son simetricas a las que el tester-agent cubre con unit
 * tests; este servicio las aplica en el flujo principal para garantizarlas en
 * runtime. El orden (checks antes de I/O) sigue el patron "validar, persistir,
 * publicar" del Main Flow.
 */
public class CreateSensorService implements CreateSensorUseCase {

    private final ContainsSensorWithCodePort containsPort;
    private final SaveSensorPort savePort;
    private final PublishSensorCreatedPort publishPort;

    public CreateSensorService(ContainsSensorWithCodePort containsPort,
                               SaveSensorPort savePort,
                               PublishSensorCreatedPort publishPort) {
        this.containsPort = containsPort;
        this.savePort = savePort;
        this.publishPort = publishPort;
    }

    @Override
    public Mono<Sensor> createSensor(CreateSensorCommand command) {
        return Mono.defer(() -> {
                    // --- validaciones sincronicas (no I/O) ---
                    validateCoordinates(command.latitud(), command.longitud());
                    validateHisteresis(command.histeresis());
                    validateFrecuencia(command.frecuenciaReporteSegundos());
                    var estado = parseEstado(command.estado());
                    var unidad = parseUnidad(command.unidadMedida());
                    var tipo = parseTipo(command.tipo());
                    validateRanges(command.rangoNormal(), command.rangoWarning(), command.rangoCritical());

                    // --- BR-001: unicidad de codigo (I/O reactivo) ---
                    return containsPort.existsByCodigo(command.codigo())
                            .flatMap(exists -> {
                                if (Boolean.TRUE.equals(exists)) {
                                    return Mono.<Sensor>error(new SensorCodeDuplicatedException(command.codigo()));
                                }
                                Sensor sensor = new Sensor(
                                        UUID.randomUUID(),
                                        command.codigo(),
                                        command.nombre(),
                                        tipo,
                                        command.latitud(),
                                        command.longitud(),
                                        unidad,
                                        estado,
                                        command.histeresis(),
                                        command.frecuenciaReporteSegundos(),
                                        Instant.now(),
                                        command.rangoNormal(),
                                        command.rangoWarning(),
                                        command.rangoCritical()
                                );
                                return savePort.save(sensor);
                            });
                })
                .flatMap(saved -> publishPort.publish(saved).thenReturn(saved));
    }

    // --- BR-003 / BR-004 ---
    private static void validateCoordinates(BigDecimal lat, BigDecimal lon) {
        if (lat == null || lat.compareTo(new BigDecimal("-90")) < 0 || lat.compareTo(new BigDecimal("90")) > 0
                || lon == null || lon.compareTo(new BigDecimal("-180")) < 0 || lon.compareTo(new BigDecimal("180")) > 0) {
            throw new InvalidCoordinatesException("latitud ∈ [-90,90] y longitud ∈ [-180,180]");
        }
    }

    // --- BR-005 ---
    private static void validateHisteresis(BigDecimal h) {
        if (h == null || h.signum() < 0) {
            throw new InvalidHisteresisException();
        }
    }

    // --- BR-006 ---
    private static void validateFrecuencia(int f) {
        if (f <= 0) {
            throw new InvalidFrecuenciaException();
        }
    }

    // --- BR-002: anidamiento inclusivo rangoNormal ⊆ rangoWarning ⊆ rangoCritical ---
    private static void validateRanges(Rango normal, Rango warning, Rango critical) {
        if (normal == null || warning == null || critical == null) {
            throw new InvalidRangesException("rangos requeridos");
        }
        // rangoCritical.min ≤ rangoWarning.min ≤ rangoNormal.min
        if (warning.min().compareTo(critical.min()) < 0
                || normal.min().compareTo(warning.min()) < 0
                // rangoNormal.max ≤ rangoWarning.max ≤ rangoCritical.max
                || normal.max().compareTo(warning.max()) > 0
                || warning.max().compareTo(critical.max()) > 0) {
            throw new InvalidRangesException(
                    "rangoCritical.min ≤ rangoWarning.min ≤ rangoNormal.min ≤ rangoNormal.max"
                            + " ≤ rangoWarning.max ≤ rangoCritical.max");
        }
    }

    // --- BR-007 / BR-008 / BR-009: parsing que admite solo valores del enum ---
    private static EstadoSensor parseEstado(String estado) {
        try {
            return EstadoSensor.valueOf(estado == null ? "" : estado.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.wrsensor.sensorregistry.domain.model.InvalidEstadoException();
        }
    }

    private static UnidadMedida parseUnidad(String unidad) {
        try {
            return UnidadMedida.valueOf(unidad == null ? "" : unidad.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.wrsensor.sensorregistry.domain.model.InvalidUnidadException();
        }
    }

    private static TipoSensor parseTipo(String tipo) {
        try {
            return TipoSensor.valueOf(tipo == null ? "" : tipo.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.wrsensor.sensorregistry.domain.model.InvalidTipoException();
        }
    }
}
