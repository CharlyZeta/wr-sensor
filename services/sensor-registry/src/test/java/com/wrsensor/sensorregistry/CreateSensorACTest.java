package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.domain.model.InvalidCoordinatesException;
import com.wrsensor.sensorregistry.domain.model.InvalidEstadoException;
import com.wrsensor.sensorregistry.domain.model.InvalidFrecuenciaException;
import com.wrsensor.sensorregistry.domain.model.InvalidHisteresisException;
import com.wrsensor.sensorregistry.domain.model.InvalidRangesException;
import com.wrsensor.sensorregistry.domain.model.InvalidTipoException;
import com.wrsensor.sensorregistry.domain.model.InvalidUnidadException;
import com.wrsensor.sensorregistry.domain.model.SensorCodeDuplicatedException;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions — AC-002..AC-005, AC-008..AC-012 (FEAT-0001). Test ID:
 * assertion:FEAT-0001-acXXX.
 *
 * <p>Cada AC-XXX referencia un BR-XXX cuya excepcion de dominio mapea a un
 * HTTP status + body code. Aqui se aserta el mapeo real (status + code) via
 * {@link GlobalErrorHandler#handleDomain} instanciado directamente (sin Spring,
 * sin WebTestClient): el handler es una fabrica plain de {@link ResponseEntity},
 * mismo patron que {@code AF04RolGuardTest} ya uso.
 *
 * <p>AC-001 (happy) → cubierto por {@code FEAT0001MainFlowIT} (no duplicar).
 * AC-006 (403 INSUFFICIENT_ROLE) y AC-007 (401 UNAUTHENTICATED) → cubiertos por
 * {@code AF04RolGuardTest#testAF04_insufficientRoleMapeaA403} y
 * {@code AF04RolGuardTest#testAF04_unauthMapeaA401} (rol no-domamin-code; no
 * se duplica aqui — ver nota en el reporte).
 *
 * <p>Stack (stack.md): WebFlux, cero .block, hexagonal estricto. Estos tests
 * NO levantan Spring completo ni Testcontainers (eso ya lo cubre el IT); son
 * rapidos.
 */
class CreateSensorACTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    // ===================== AC-002 =====================
    @Test
    @DisplayName("AC-002: SensorCodeDuplicatedException → 409 CONFLICT + code=SENSOR_CODE_DUPLICATED")
    void testAC002_duplicado_409Duplicated() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new SensorCodeDuplicatedException("PARANA-RECONQUISTA"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_CODE_DUPLICATED");
    }

    // ===================== AC-003 =====================
    @Test
    @DisplayName("AC-003: InvalidRangesException (datos AC-003) → 400 + code=SENSOR_INVALID_RANGES")
    void testAC003_rangosVioladores_400InvalidRanges() {
        // AC-003 data: crit 5-9, warn 2-6, norm 3-5 — rompe critical.min(5) > warning.min(2).
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidRangesException(
                        "rangoCritical.min(5) > rangoWarning.min(2)"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_RANGES");
    }

    // ===================== AC-004 =====================
    @Test
    @DisplayName("AC-004: InvalidCoordinatesException (lat=95) → 400 + code=SENSOR_INVALID_COORDINATES")
    void testAC004_latitudInvalida_400InvalidCoordinates() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidCoordinatesException("latitud=95 fuera de [-90,90]"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_COORDINATES");
    }

    // ===================== AC-005 =====================
    @Test
    @DisplayName("AC-005: InvalidCoordinatesException (lon=200) → 400 + code=SENSOR_INVALID_COORDINATES")
    void testAC005_longitudInvalida_400InvalidCoordinates() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidCoordinatesException("longitud=200 fuera de [-180,180]"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_COORDINATES");
    }

    // ===================== AC-008 =====================
    @Test
    @DisplayName("AC-008: InvalidHisteresisException (h=-0.5) → 400 + code=SENSOR_INVALID_HISTERESIS")
    void testAC008_histeresisNegativa_400InvalidHisteresis() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidHisteresisException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_HISTERESIS");
    }

    // ===================== AC-009 =====================
    @Test
    @DisplayName("AC-009: InvalidFrecuenciaException (f=0) → 400 + code=SENSOR_INVALID_FRECUENCIA")
    void testAC009_frecuenciaCero_400InvalidFrecuencia() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidFrecuenciaException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_FRECUENCIA");
    }

    // ===================== AC-010 =====================
    @Test
    @DisplayName("AC-010: InvalidEstadoException (DESCONOCIDO) → 400 + code=SENSOR_INVALID_ESTADO")
    void testAC010_estadoInvalido_400InvalidEstado() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidEstadoException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_ESTADO");
    }

    // ===================== AC-011 =====================
    @Test
    @DisplayName("AC-011: InvalidUnidadException (PULGADAS) → 400 + code=SENSOR_INVALID_UNIDAD")
    void testAC011_unidadInvalida_400InvalidUnidad() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidUnidadException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_UNIDAD");
    }

    // ===================== AC-012 =====================
    @Test
    @DisplayName("AC-012: InvalidTipoException (LAGO) → 400 + code=SENSOR_INVALID_TIPO")
    void testAC012_tipoInvalido_400InvalidTipo() {
        ResponseEntity<Map<String, Object>> resp =
                handler.handleDomain(new InvalidTipoException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_TIPO");
    }
}
