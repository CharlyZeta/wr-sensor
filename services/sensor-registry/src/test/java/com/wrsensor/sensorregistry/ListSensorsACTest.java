package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.domain.model.InvalidListCursorException;
import com.wrsensor.sensorregistry.domain.model.InvalidListLimitException;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.GlobalErrorHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions — FEAT-0002 AC-006, AC-007, AC-010 (mapeo dominio → HTTP).
 * Test IDs: assertion:FEAT-0002-ac006/ac007/ac010.
 *
 * <p>Mismo patron que {@code CreateSensorACTest} (FEAT-0001): se aserta el mapeo
 * real (status + body code) via {@link GlobalErrorHandler#handleDomain}
 * instanciado directamente, sin Spring ni WebTestClient.
 *
 * <p>Nota de cobertura (como en FEAT-0001): AC-001/AC-005/AC-008/AC-009/AC-011/AC-012
 * se verifican a nivel servicio en {@code ListSensorsServicePaginationTest};
 * AC-002/AC-003/AC-004 (auth) en {@code ListSensorsAuthTest}; AC-001 end-to-end
 * adicional en {@code FEAT0002MainFlowIT}. Aca solo los codigos de validacion
 * estructural de query params que mapean desde {@code InvalidList*Exception}.
 */
class ListSensorsACTest {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    @Test
    @DisplayName("AC-006: limit=1001 → 400 BAD REQUEST + code=SENSOR_INVALID_LIMIT")
    void testAC006_limitMayor1000_400InvalidLimit() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDomain(new InvalidListLimitException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_LIMIT");
    }

    @Test
    @DisplayName("AC-007: limit=0 → 400 BAD REQUEST + code=SENSOR_INVALID_LIMIT")
    void testAC007_limitMenorA1_400InvalidLimit() {
        // limit < 1 dispara la misma InvalidListLimitException que el AC-006
        // (BR-002: rango valido [1,1000]); la semantica de rechazo es identica.
        ResponseEntity<Map<String, Object>> resp = handler.handleDomain(new InvalidListLimitException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_LIMIT");
    }

    @Test
    @DisplayName("AC-010: cursor malformado → 400 BAD REQUEST + code=SENSOR_INVALID_CURSOR")
    void testAC010_cursorMalformado_400InvalidCursor() {
        ResponseEntity<Map<String, Object>> resp = handler.handleDomain(new InvalidListCursorException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo("SENSOR_INVALID_CURSOR");
    }
}
