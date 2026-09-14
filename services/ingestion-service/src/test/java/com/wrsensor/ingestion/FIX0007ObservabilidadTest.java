package com.wrsensor.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wrsensor.ingestion.domain.CircuitoResiliencia;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores;
import com.wrsensor.ingestion.infrastructure.config.IngestionInfraConfig;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0007 — observabilidad del circuit breaker (unit tests BR-007, BR-010, AC-009, AC-010):
 * logs en las transiciones y el endpoint interno de estado (sin actuator).
 */
class FIX0007ObservabilidadTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");

    private static IngestionProperties props() {
        return new IngestionProperties(
                300L,
                new IngestionProperties.Registry("http://localhost:8080",
                        new IngestionProperties.Registry.Auth("viewer@wrsensor.local", "Viewer123!"),
                        2000L, 1000L,
                        new IngestionProperties.Registry.Cache(300L),
                        new IngestionProperties.Registry.Circuito(3, 30L, 2)),
                new IngestionProperties.Lecturas("sensor.lecturas", "dlq", "dlx"),
                new IngestionProperties.Alertas("sensor.alertas"),
                new IngestionProperties.Messaging(3, "dlx"),
                new IngestionProperties.Outbox(null, null, null, null, null, null, null),
                new IngestionProperties.RangoFisico(Map.of(), Map.of()),
                new IngestionProperties.Particiones(4, null, "sensor.lecturas.part",
                        "queue.sensor.lecturas.p{i}"),
                new IngestionProperties.Schema("1.0", true));
    }

    private static ListAppender<ILoggingEvent> capturarLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(CircuitoResiliencia.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    @DisplayName("AC-009: WARN al abrir el circuito e INFO al semi-abrir y cerrar")
    void ac009_logsDeTransicion() {
        CircuitoResiliencia c = new IngestionInfraConfig().circuitoResiliencia(props());
        ListAppender<ILoggingEvent> logs = capturarLog();
        try {
            for (int i = 0; i < 3; i++) {
                c.registrarFallo(T0);
            }
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("ABIERTO"));

            c.permitir(T0.plusSeconds(31));   // → SEMIABIERTO
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("SEMIABIERTO"));

            c.registrarExito(T0.plusSeconds(31));
            c.registrarExito(T0.plusSeconds(32));   // → CERRADO
            assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getFormattedMessage().contains("CERRADO"));
        } finally {
            ((Logger) LoggerFactory.getLogger(CircuitoResiliencia.class)).detachAppender(logs);
        }
    }

    @Test
    @DisplayName("AC-010: el endpoint devuelve el estado y NO expone datos de sensores ni credenciales")
    void ac010_endpoint() {
        CircuitoResiliencia c = new IngestionInfraConfig().circuitoResiliencia(props());
        CacheConfigSensores cache = new IngestionInfraConfig().cacheConfigSensores();

        UUID id = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
        cache.poner(id, new SensorInfo(id, "S", "ACTIVO", "METROS",
                new SensorInfo.Rango(new BigDecimal("4"), new BigDecimal("6")),
                new SensorInfo.Rango(new BigDecimal("2"), new BigDecimal("8")),
                new SensorInfo.Rango(new BigDecimal("0"), new BigDecimal("10"))), T0);
        cache.contabilizarAcierto();
        cache.contabilizarRefresco();

        var rutas = new IngestionInfraConfig().resilienciaRoutes(c, cache);
        WebTestClient cliente = WebTestClient.bindToRouterFunction(rutas).build();

        byte[] body = cliente.get().uri("/api/ingestion/resiliencia").exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody(byte[].class).returnResult().getResponseBody();

        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("\"circuito\":\"CERRADO\"")
                .contains("\"fallosConsecutivos\":0")
                .contains("\"cacheTamano\":1")
                .contains("\"cacheAciertos\":1")
                .contains("\"cacheRefrescos\":1")
                .contains("\"cacheVencidasUsadas\":0")
                .contains("\"llamadas\":0");
        assertThat(json).as("sin datos de sensores ni credenciales")
                .doesNotContain("sensorId", "codigo", "email", "password", "token", "Viewer123");
    }
}
