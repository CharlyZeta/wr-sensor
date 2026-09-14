package com.wrsensor.ingestion;

import com.wrsensor.ingestion.domain.CircuitoResiliencia;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.infrastructure.adapter.out.registry.CacheConfigSensores;
import com.wrsensor.ingestion.infrastructure.adapter.out.registry.RegistrySensorConfigPort;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIX-0007 — resiliencia del lookup de config contra un registry stub
 * (unit tests BR-001, BR-003..BR-006, AF-01..AF-05, AC-002, AC-003, AC-006, AC-007).
 */
class FIX0007ResilienciaTest {

    private static final UUID SENSOR = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

    /** Reloj mutable para simular el paso del TTL / la ventana del circuito sin esperar. */
    private static final class RelojMutable extends Clock {
        private final AtomicReference<Instant> ahora;

        RelojMutable(Instant inicio) {
            this.ahora = new AtomicReference<>(inicio);
        }

        void avanzar(Duration d) {
            ahora.updateAndGet(i -> i.plus(d));
        }

        @Override
        public Instant instant() {
            return ahora.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static final String SENSOR_OK = "{\"id\":\"" + SENSOR + "\",\"codigo\":\"PARANA-RECONQUISTA\","
            + "\"estado\":\"ACTIVO\",\"unidadMedida\":\"METROS\","
            + "\"rangoNormal\":{\"min\":4,\"max\":6},\"rangoWarning\":{\"min\":2,\"max\":8},"
            + "\"rangoCritical\":{\"min\":0,\"max\":10}}";

    private HttpServer stub;
    private int puerto;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final AtomicReference<java.util.function.Function<Integer, int[]>> comportamiento =
            new AtomicReference<>(n -> new int[]{200, 200, 200, 200, 200, 200, 200, 200, 200, 200});
    private final AtomicReference<String> cuerpo = new AtomicReference<>(SENSOR_OK);
    private long demoraGetMs = 0;

    private RelojMutable reloj;
    private CircuitoResiliencia circuito;
    private CacheConfigSensores cache;
    private RegistrySensorConfigPort port;

    @BeforeEach
    void setUp() throws IOException {
        logins.set(0);
        gets.set(0);
        demoraGetMs = 0;
        comportamiento.set(n -> new int[]{200, 200, 200, 200, 200, 200, 200, 200, 200, 200});

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/auth/login", ex -> {
            logins.incrementAndGet();
            responder(ex, 200, "{\"token\":\"tok-" + logins.get() + "\",\"rol\":\"VIEWER\","
                    + "\"expiraEnSegundos\":3600}");
        });
        stub.createContext("/api/sensores/", ex -> {
            int n = gets.incrementAndGet();
            if (demoraGetMs > 0) {
                try {
                    Thread.sleep(demoraGetMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int[] respuestas = comportamiento.get().apply(n);
            int status = respuestas[Math.min(n - 1, respuestas.length - 1)];
            responder(ex, status, status == 200 ? cuerpo.get()
                    : status == 404 ? "" : "{\"code\":\"ERROR\",\"message\":\"stub\"}");
        });
        stub.start();
        puerto = stub.getAddress().getPort();

        reloj = new RelojMutable(Instant.parse("2026-09-13T10:00:00Z"));
        cache = new CacheConfigSensores();
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private void armarPort(long timeoutMs, int fallos, long segAbierto, int exitos, long ttlSeg) {
        circuito = new CircuitoResiliencia(fallos, Duration.ofSeconds(segAbierto), exitos);
        IngestionProperties props = props(timeoutMs, ttlSeg, fallos, segAbierto, exitos);
        port = new RegistrySensorConfigPort(WebClient.builder(), props, circuito, cache, reloj);
    }

    private IngestionProperties props(long timeoutMs, long ttl, int fallos, long segAbierto, int exitos) {
        return new IngestionProperties(
                300L,
                new IngestionProperties.Registry("http://127.0.0.1:" + puerto,
                        new IngestionProperties.Registry.Auth("viewer@wrsensor.local", "Viewer123!"),
                        timeoutMs, 1000L,
                        new IngestionProperties.Registry.Cache(ttl),
                        new IngestionProperties.Registry.Circuito(fallos, segAbierto, exitos)),
                new IngestionProperties.Lecturas("sensor.lecturas", "dlq", "dlx"),
                new IngestionProperties.Alertas("sensor.alertas"),
                new IngestionProperties.Messaging(3, "dlx"),
                new IngestionProperties.Outbox(null, null, null, null, null, null, null),
                new IngestionProperties.RangoFisico(Map.of(), Map.of()),
                new IngestionProperties.Particiones(4, null, "sensor.lecturas.part",
                        "queue.sensor.lecturas.p{i}"),
                new IngestionProperties.Schema("1.0", true));
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ===== BR-001: timeout explícito =====

    @Test
    @DisplayName("BR-001: una respuesta lenta falla por timeout en el tiempo configurado, no cuelga")
    void br001_timeout() {
        armarPort(200L, 5, 30, 2, 300);
        demoraGetMs = 1500;

        long inicio = System.currentTimeMillis();
        StepVerifier.create(port.findById(SENSOR))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(RechazoLecturaException.class)
                        .extracting(x -> ((RechazoLecturaException) x).motivo)
                        .isEqualTo(RechazoLecturaException.REGISTRY_UNAVAILABLE))
                .verify(Duration.ofSeconds(5));
        long transcurrido = System.currentTimeMillis() - inicio;

        assertThat(transcurrido).as("fue timeout (~200 ms), no la demora del stub (1500 ms)")
                .isLessThan(1500L);
    }

    // ===== BR-002 / AC-002: umbral y corte de red =====

    @Test
    @DisplayName("AC-002: 3 fallos consecutivos abren el circuito y la siguiente no toca la red")
    void ac002_abreYCorta() {
        armarPort(2000L, 3, 30, 2, 300);
        comportamiento.set(n -> new int[]{500, 500, 500, 500, 500, 500});

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(port.findById(SENSOR))
                    .expectErrorMatches(e -> e instanceof RechazoLecturaException
                            && RechazoLecturaException.REGISTRY_UNAVAILABLE.equals(
                            ((RechazoLecturaException) e).motivo))
                    .verify(Duration.ofSeconds(5));
        }
        assertThat(circuito.estado()).isEqualTo(CircuitoResiliencia.Estado.ABIERTO);
        int getsAntes = gets.get();

        StepVerifier.create(port.findById(SENSOR))
                .expectErrorMatches(e -> e instanceof RechazoLecturaException
                        && RechazoLecturaException.REGISTRY_UNAVAILABLE.equals(
                        ((RechazoLecturaException) e).motivo))
                .verify(Duration.ofSeconds(5));

        assertThat(gets.get()).as("la 4ª resolución no golpeó la red").isEqualTo(getsAntes);
    }

    // ===== BR-003/BR-004: cache fresca y last-known-good =====

    @Test
    @DisplayName("BR-004: dentro del TTL se responde de cache sin red")
    void br004_cacheFresca() {
        armarPort(2000L, 5, 30, 2, 300);
        StepVerifier.create(port.findById(SENSOR)).expectNextCount(1).verifyComplete();
        int getsAntes = gets.get();

        StepVerifier.create(port.findById(SENSOR)).expectNextCount(1).verifyComplete();
        assertThat(gets.get()).as("segunda resolución: sin red (cache fresca)").isEqualTo(getsAntes);
        assertThat(cache.aciertos()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-003: TTL vencido + registry caído → se usa la copia vencida (last-known-good)")
    void ac003_lastKnownGood() {
        armarPort(2000L, 5, 30, 2, 300);
        StepVerifier.create(port.findById(SENSOR)).expectNextCount(1).verifyComplete();

        reloj.avanzar(Duration.ofSeconds(301));       // TTL vencido
        comportamiento.set(n -> new int[]{500, 500, 500, 500});

        StepVerifier.create(port.findById(SENSOR))
                .expectNextMatches(info -> "ACTIVO".equals(info.estado()))
                .verifyComplete();
        assertThat(cache.vencidasUsadas()).as("copia vencida usada").isEqualTo(1);
        assertThat(cache.tamano()).isEqualTo(1);
    }

    // ===== BR-005 / AC-006: sin copia y registry caído =====

    @Test
    @DisplayName("AC-006: sin cache y registry caído → REGISTRY_UNAVAILABLE (no INFRA_ERROR)")
    void ac006_sinCopia() {
        armarPort(2000L, 5, 30, 2, 300);
        comportamiento.set(n -> new int[]{500, 500, 500, 500});

        StepVerifier.create(port.findById(SENSOR))
                .expectErrorMatches(e -> e instanceof RechazoLecturaException
                        && RechazoLecturaException.REGISTRY_UNAVAILABLE.equals(
                        ((RechazoLecturaException) e).motivo))
                .verify(Duration.ofSeconds(5));
    }

    // ===== BR-006 / AC-007 / AF-01: refresco del token =====

    @Test
    @DisplayName("AC-007: un 401 invalida el token, revalida credenciales y reintenta una vez")
    void ac007_token401() {
        armarPort(2000L, 5, 30, 2, 300);
        comportamiento.set(n -> new int[]{401, 200, 200, 200, 200, 200});

        StepVerifier.create(port.findById(SENSOR))
                .expectNextMatches(info -> "ACTIVO".equals(info.estado()))
                .verifyComplete();

        assertThat(logins.get()).as("login inicial + relogin tras el 401").isEqualTo(2);
        assertThat(gets.get()).as("intento 401 + reintento 200").isEqualTo(2);
        assertThat(cache.tamano()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-006: un 401 persistente (tras el reintento) cuenta como fallo del circuito")
    void br006_token401Persistente() {
        armarPort(2000L, 5, 30, 2, 300);
        comportamiento.set(n -> new int[]{401, 401, 401, 401, 401, 401});

        StepVerifier.create(port.findById(SENSOR))
                .expectErrorMatches(e -> e instanceof RechazoLecturaException
                        && RechazoLecturaException.REGISTRY_UNAVAILABLE.equals(
                        ((RechazoLecturaException) e).motivo))
                .verify(Duration.ofSeconds(5));

        assertThat(logins.get()).as("login inicial + un único relogin (reintento único)").isEqualTo(2);
        assertThat(circuito.fallosConsecutivos()).as("el 401 persistente cuenta como fallo").isGreaterThanOrEqualTo(1);
    }

    // ===== AF-05: 404 no es fallo =====

    @Test
    @DisplayName("AF-05: un 404 (sensor inexistente) no cuenta como fallo del circuito")
    void af05_404NoEsFallo() {
        armarPort(2000L, 5, 30, 2, 300);
        comportamiento.set(n -> new int[]{404, 404, 404, 404, 404, 404});

        StepVerifier.create(port.findById(SENSOR)).verifyComplete();
        assertThat(circuito.estado()).isEqualTo(CircuitoResiliencia.Estado.CERRADO);
        assertThat(circuito.fallosConsecutivos()).isZero();
    }

    // ===== AF-03: copia vencida + registry caído = log WARN (cubierto por ac003) =====

    @Test
    @DisplayName("AC-004: tras el TTL se refresca la config y un cambio a INACTIVO se ve")
    void ac004_refrescoInactivo() {
        armarPort(2000L, 5, 30, 2, 300);
        StepVerifier.create(port.findById(SENSOR))
                .expectNextMatches(info -> "ACTIVO".equals(info.estado()))
                .verifyComplete();

        cuerpo.set(SENSOR_OK.replace("\"ACTIVO\"", "\"INACTIVO\""));   // el sensor pasa a INACTIVO
        reloj.avanzar(Duration.ofSeconds(301));                        // > TTL

        StepVerifier.create(port.findById(SENSOR))
                .expectNextMatches(info -> "INACTIVO".equals(info.estado()))
                .verifyComplete();
        assertThat(cache.refrescos()).as("un refresco por cada fetch exitoso (inicial + post-TTL)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("BR-005: un fallo no rompe el lookup siguiente (el stub vuelve y el breaker resetea)")
    void br005_falloLuegoExito() {
        armarPort(2000L, 5, 30, 2, 300);
        comportamiento.set(n -> new int[]{500, 200, 200, 200});

        StepVerifier.create(port.findById(SENSOR))
                .expectErrorMatches(e -> e instanceof RechazoLecturaException)
                .verify(Duration.ofSeconds(5));
        StepVerifier.create(port.findById(SENSOR))
                .expectNextMatches(info -> "ACTIVO".equals(info.estado()))
                .verifyComplete();
        assertThat(circuito.estado()).isEqualTo(CircuitoResiliencia.Estado.CERRADO);
        assertThat(circuito.fallosConsecutivos()).isZero();
    }
}
