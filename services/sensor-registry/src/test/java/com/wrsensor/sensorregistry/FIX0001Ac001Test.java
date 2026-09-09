package com.wrsensor.sensorregistry;

import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.GlobalErrorHandler;
import com.wrsensor.sensorregistry.infrastructure.adapter.in.web.SensorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test — FIX-0001 / AC-001. Test ID: {@code unit-test:FIX-0001-ac001}.
 *
 * <p>Cubre el path estructural de validacion (Bean Validation en el bind del
 * controller) que en FEAT-0001 quedo sin testear: {@link GlobalErrorHandler#handleBind}
 * mapea un {@link WebExchangeBindException} (campo faltante / blank) a un body
 * {@code {"code": "...", "message": "..."}} + 400 BAD REQUEST. El bug (FIX-0001):
 * el helper {@code deriveCodeFromBinding(ex)} devolvia el simple-name de la clase
 * de excepcion de dominio ({@code *.class.getSimpleName()}) en lugar del code
 * estable ({@code SENSOR_INVALID_*}). Este test aserta el comportamiento POST-FIX
 * (Expected Behavior del Contract FIX-0001); con el fix aplicado (commit del
 * 2026-07-31, cases {@code "tipo" -> "SENSOR_INVALID_TIPO"} y
 * {@code "codigo" -> "SENSOR_INVALID_REQUEST"}) da VERDE y queda como regression
 * guard: si el switch regresa al simple-name, este test rompe.
 *
 * <p>AC-001 (caso contratado, campo {@code tipo} = "" → SENSOR_INVALID_TIPO) es el
 * test primario. Ademass se cubren los 9 branches de la tabla de mapeo como
 * regression suite, <b>incluido</b> {@code codigo} → {@code SENSOR_INVALID_REQUEST}
 * que lockea la RESOLUCION HUMANA 2026-07-31 (sin ese test la decision puede
 * regresar silenciosamente a {@code "SensorCodeDuplicatedException"} si el coder
 * toca el switch).
 *
 * <p>Patron: handler instanciado directamente (sin Spring, sin WebTestClient),
 * mismo que {@link CreateSensorACTest} (path de dominio) y {@code AF04RolGuardTest}.
 * Stack: WebFlux, hexagonal estricto, JUnit 5 + AssertJ. NO levanta Spring ni
 * Testcontainers (eso lo cubre {@code FEAT0001MainFlowIT}).
 */
class FIX0001Ac001Test {

    private final GlobalErrorHandler handler = new GlobalErrorHandler();

    /**
     * Construye un {@link WebExchangeBindException} con un {@link FieldError} para
     * {@code field}, sin Spring ni request reales: usa un {@link MethodParameter} real
     * del {@link SensorController} + un {@link BindingResult} ({@link DirectFieldBindingResult}).
     *
     * <p>{@link GlobalErrorHandler#handleBind} solo llama a {@code ex.getFieldErrors()}
     * (no a {@code getMethodParameter()}), asi que basta con que compile y que
     * {@code getFieldErrors()} devuelva el {@link FieldError} agregado.
     */
    private static WebExchangeBindException bindEx(String field) {
        try {
            Method m = SensorController.class.getDeclaredMethod("createSensor",
                    org.springframework.web.server.ServerWebExchange.class, Mono.class);
            MethodParameter param = new MethodParameter(m, 1);
            BindingResult br = new DirectFieldBindingResult(new Object(), "sensorRequest");
            br.addError(new FieldError("sensorRequest", field, "", false, null, null, "blank"));
            return new WebExchangeBindException(param, br);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SensorController.createSensor no encontrado", e);
        }
    }

    /** Aserta el contrato del path estructural para un campo: 400 + body code = expectedCode. */
    private void assertBind(String field, String expectedCode) {
        ResponseEntity<Map<String, Object>> resp = handler.handleBind(bindEx(field));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code"))
                .as("field=\"%s\" debe mapear a code=%s", field, expectedCode)
                .isEqualTo(expectedCode);
    }

    // ===================== AC-001 (primario, contratado) =====================
    @Test
    @DisplayName("AC-001 (FIX-0001): tipo=\"\" -> 400 BAD_REQUEST + code=SENSOR_INVALID_TIPO")
    void ac001_tipoBlanco_400SensorInvalidTipo() {
        assertBind("tipo", "SENSOR_INVALID_TIPO");
    }

    // ===== Regression suite del mapeo campo -> code (tabla Expected Behavior) =====

    @Test
    @DisplayName("FIX-0001 regression: latitud -> 400 + code=SENSOR_INVALID_COORDINATES")
    void regression_latitud_400InvalidCoordinates() {
        assertBind("latitud", "SENSOR_INVALID_COORDINATES");
    }

    @Test
    @DisplayName("FIX-0001 regression: longitud -> 400 + code=SENSOR_INVALID_COORDINATES")
    void regression_longitud_400InvalidCoordinates() {
        assertBind("longitud", "SENSOR_INVALID_COORDINATES");
    }

    @Test
    @DisplayName("FIX-0001 regression: histeresis -> 400 + code=SENSOR_INVALID_HISTERESIS")
    void regression_histeresis_400InvalidHisteresis() {
        assertBind("histeresis", "SENSOR_INVALID_HISTERESIS");
    }

    @Test
    @DisplayName("FIX-0001 regression: frecuenciaReporteSegundos -> 400 + code=SENSOR_INVALID_FRECUENCIA")
    void regression_frecuenciaReporteSegundos_400InvalidFrecuencia() {
        assertBind("frecuenciaReporteSegundos", "SENSOR_INVALID_FRECUENCIA");
    }

    @Test
    @DisplayName("FIX-0001 regression: estado -> 400 + code=SENSOR_INVALID_ESTADO")
    void regression_estado_400InvalidEstado() {
        assertBind("estado", "SENSOR_INVALID_ESTADO");
    }

    @Test
    @DisplayName("FIX-0001 regression: unidadMedida -> 400 + code=SENSOR_INVALID_UNIDAD")
    void regression_unidadMedida_400InvalidUnidad() {
        assertBind("unidadMedida", "SENSOR_INVALID_UNIDAD");
    }

    @Test
    @DisplayName("FIX-0001 regression: rangoNormal -> 400 + code=SENSOR_INVALID_RANGES")
    void regression_rangoNormal_400InvalidRanges() {
        assertBind("rangoNormal", "SENSOR_INVALID_RANGES");
    }

    @Test
    @DisplayName("FIX-0001 regression: rangoWarning -> 400 + code=SENSOR_INVALID_RANGES")
    void regression_rangoWarning_400InvalidRanges() {
        assertBind("rangoWarning", "SENSOR_INVALID_RANGES");
    }

    @Test
    @DisplayName("FIX-0001 regression: rangoCritical -> 400 + code=SENSOR_INVALID_RANGES")
    void regression_rangoCritical_400InvalidRanges() {
        assertBind("rangoCritical", "SENSOR_INVALID_RANGES");
    }

    // ===== Lock de RESOLUCION HUMANA 2026-07-31: codigo ausente -> default =====

    @Test
    @DisplayName("FIX-0001 (resolucion humana 2026-07-31): codigo ausente -> 400 + code=SENSOR_INVALID_REQUEST (NO duplicado)")
    void resolucionHumana_codigoAusente_400InvalidRequest() {
        assertBind("codigo", "SENSOR_INVALID_REQUEST");
    }

    // ===== Default: campo no mapeado -> SENSOR_INVALID_REQUEST (sin cambio; verde hoy) =====

    @Test
    @DisplayName("FIX-0001 default: campo no mapeado (\"otro\") -> 400 + code=SENSOR_INVALID_REQUEST (default OK)")
    void default_campoNoMapeado_400InvalidRequest() {
        assertBind("otro", "SENSOR_INVALID_REQUEST");
    }
}
