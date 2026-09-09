package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.domain.model.InvalidSensorIdException;
import com.wrsensor.sensorregistry.domain.model.SensorNotFoundException;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions — FEAT-0003 AC-005 y AC-006 (mapeo dominio → HTTP).
 * Test IDs: assertion:FEAT-0003-ac005/ac006.
 *
 * <p>Mismo patron que {@code CreateSensorACTest}: se aserta el mapeo real (status +
 * body code) via {@link GlobalErrorHandler#handleDomain} instanciado directamente,
 * sin Spring ni WebTestClient.
 *
 * <p>Nota de cobertura: AC-001/AC-002/AC-003/AC-004/AC-007 y los AF/BR se verifican
 * end-to-end en {@code FEAT0003MainFlowIT} (y BR-002/BR-005/BR-006 a nivel servicio
 * en {@code GetSensorDetailServiceTest}). Aca solo los codigos nuevos de FEAT-0003.
 */
class GetSensorDetailACTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    @Test
    @DisplayName("AC-005 / BR-002: SensorNotFoundException → 404 NOT FOUND + code=SENSOR_NOT_FOUND")
    void testAC005_inexistente_404NotFound() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDomain(new SensorNotFoundException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_NOT_FOUND");
    }

    @Test
    @DisplayName("AC-006 / BR-001: InvalidSensorIdException → 400 BAD REQUEST + code=SENSOR_INVALID_ID")
    void testAC006_idMalformado_400InvalidId() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDomain(new InvalidSensorIdException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_ID");
    }
}
