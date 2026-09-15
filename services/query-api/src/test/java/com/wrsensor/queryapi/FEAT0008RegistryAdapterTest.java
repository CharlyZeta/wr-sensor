package com.wrsensor.queryapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wrsensor.queryapi.domain.QueryException;
import com.wrsensor.queryapi.domain.SensorMetadata;
import com.wrsensor.queryapi.infrastructure.adapter.out.registry.RegistrySensoresAdapter;
import com.wrsensor.queryapi.infrastructure.config.RegistryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — FEAT-0008 BR-007: cliente REST del registry (paginación keyset, timeout explícito, token
 * con relogin y mapeo de fallos a {@code REGISTRY_UNAVAILABLE}).
 *
 * <p>Se usa un stub HTTP del JDK: así se verifica el protocolo real (query params, headers) sin
 * Docker. AC-011 se cubre acá: registry en 5xx y registry lento (falla acotada por timeout).</p>
 */
class FEAT0008RegistryAdapterTest {

    private record Peticion(String metodo, String path, String query, String authorization) {}

    private static final Instant T0 = Instant.parse("2026-09-14T10:00:00Z");
    private static final String TOKEN = "token-de-prueba";

    private HttpServer stub;
    private final List<Peticion> peticiones = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile Function<HttpExchange, Respuesta> respuesta =
            ex -> new Respuesta(200, "{\"items\":[],\"nextCursor\":null}");

    private record Respuesta(int status, String body) {}

    private String baseUrl;

    @BeforeEach
    void levantar() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            peticiones.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(),
                    ex.getRequestHeaders().getFirst("Authorization")));
            try {
                Respuesta r;
                if ("/api/auth/login".equals(ex.getRequestURI().getPath())) {
                    logins.incrementAndGet();
                    r = new Respuesta(200, "{\"token\":\"" + TOKEN
                            + "\",\"rol\":\"VIEWER\",\"expiraEnSegundos\":3600}");
                } else {
                    r = respuesta.apply(ex);
                }
                byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.status(), body.length);
                ex.getResponseBody().write(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                ex.close();
            }
        });
        stub.start();
        baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterEach
    void bajar() {
        stub.stop(0);
    }

    private RegistrySensoresAdapter adapter() {
        return adapter(2, 200, 200L, 5000L);
    }

    private RegistrySensoresAdapter adapter(int limitPagina, int maxSensores, long timeoutMs,
                                            long conexionTimeoutMs) {
        RegistryProperties cfg = new RegistryProperties(baseUrl,
                new RegistryProperties.Auth("viewer@wrsensor.local", "Viewer123!"), timeoutMs,
                conexionTimeoutMs, limitPagina, maxSensores);
        return new RegistrySensoresAdapter(cfg, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private static String sensorJson(String id, String codigo) {
        return "{\"id\":\"" + id + "\",\"codigo\":\"" + codigo + "\",\"nombre\":\"Sensor " + codigo
                + "\",\"tipo\":\"TEMPERATURA\",\"latitud\":-34.6,\"longitud\":-58.4,"
                + "\"unidadMedida\":\"CELSIUS\",\"estado\":\"ACTIVE\",\"histeresis\":0.5,"
                + "\"frecuenciaReporteSegundos\":60,\"rangoNormal\":{\"min\":0,\"max\":10}}";
    }

    private static String id(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    // ===== paginación keyset =====

    @Test
    @DisplayName("BR-007: sigue nextCursor hasta agotar y manda limit/cursor correctos")
    void br007_paginacionKeyset() {
        respuesta = ex -> ex.getRequestURI().getQuery().contains("cursor=")
                ? new Respuesta(200, "{\"items\":[" + sensorJson(id(3), "S-03")
                        + "],\"nextCursor\":null}")
                : new Respuesta(200, "{\"items\":[" + sensorJson(id(1), "S-01") + ","
                        + sensorJson(id(2), "S-02") + "],\"nextCursor\":\"cursor-1\"}");

        List<SensorMetadata> sensores = adapter().listar().collectList().block();

        assertThat(sensores).extracting(SensorMetadata::codigo)
                .containsExactly("S-01", "S-02", "S-03");
        assertThat(sensores.get(0).id().toString()).isEqualTo(id(1));
        assertThat(sensores.get(2).latitud()).isEqualByComparingTo("-34.6");
        assertThat(sensores.get(2).unidadMedida()).isEqualTo("CELSIUS");

        List<Peticion> listado = peticiones.stream()
                .filter(p -> p.path().equals("/api/sensores")).toList();
        assertThat(listado).hasSize(2);
        assertThat(listado.get(0).query()).isEqualTo("limit=2");
        assertThat(listado.get(1).query()).isEqualTo("limit=2&cursor=cursor-1");
        assertThat(listado).allSatisfy(p ->
                assertThat(p.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    @DisplayName("BR-007: el tope de seguridad corta la paginación (nunca un mapa sin fin)")
    void br007_topeDeSeguridad() {
        AtomicInteger n = new AtomicInteger();
        respuesta = ex -> new Respuesta(200, "{\"items\":[" + sensorJson(id(n.incrementAndGet()),
                "S") + "],\"nextCursor\":\"siempre-mas\"}");

        List<SensorMetadata> sensores = adapter(1, 3, 200L, 5000L).listar()
                .collectList().block();

        assertThat(sensores).hasSize(3);
        assertThat(peticiones.stream().filter(p -> p.path().equals("/api/sensores")))
                .as("deja de pedir páginas al llegar al tope (a lo sumo la ya encolada)")
                .hasSizeLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("BR-007: el limit de página se respeta tal cual (configurable)")
    void br007_limitConfigurable() {
        adapter(50, 5000, 200L, 5000L).listar().collectList().block();
        assertThat(peticiones).filteredOn(p -> p.path().equals("/api/sensores"))
                .first().extracting(Peticion::query).isEqualTo("limit=50");
    }

    @Test
    @DisplayName("BR-007/AF-06: 200 con cuerpo inválido también es REGISTRY_UNAVAILABLE")
    void br007_cuerpoInvalido() {
        respuesta = ex -> new Respuesta(200, "no-es-json");
        StepVerifier.create(adapter().listar())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify();
    }

    @Test
    @DisplayName("BR-007: id no UUID en el listado → REGISTRY_UNAVAILABLE (no 500 del query-api)")
    void br007_idInvalido() {
        respuesta = ex -> new Respuesta(200,
                "{\"items\":[{\"id\":\"no-uuid\",\"codigo\":\"S\"}],\"nextCursor\":null}");
        StepVerifier.create(adapter().listar())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify();
    }

    // ===== token =====

    @Test
    @DisplayName("BR-007: el token se cachea (un solo login para varias invocaciones)")
    void br007_tokenCacheado() {
        RegistrySensoresAdapter adapter = adapter();
        adapter.listar().collectList().block();
        adapter.listar().collectList().block();
        assertThat(logins.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-007: ante 401 se reloguea una vez y se reintenta la misma página")
    void br007_reloginAnte401() {
        AtomicInteger vistas = new AtomicInteger();
        respuesta = ex -> {
            // primer listado: 401 (token viejo); con el token nuevo: 200 con un sensor
            if (vistas.incrementAndGet() == 1) {
                return new Respuesta(401, "{\"code\":\"UNAUTHENTICATED\"}");
            }
            return new Respuesta(200, "{\"items\":[" + sensorJson(id(1), "S-01")
                    + "],\"nextCursor\":null}");
        };
        List<SensorMetadata> sensores = adapter().listar().collectList().block();
        assertThat(sensores).hasSize(1);
        assertThat(logins.get()).isEqualTo(2);
        assertThat(vistas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("BR-007: 401 persistente → REGISTRY_UNAVAILABLE (no queda reintentando)")
    void br007_401Persistente() {
        respuesta = ex -> new Respuesta(401, "{\"code\":\"UNAUTHENTICATED\"}");
        StepVerifier.create(adapter().listar())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify();
        assertThat(logins.get()).as("un relogin, no un bucle").isEqualTo(2);
    }

    // ===== AC-011: errores y timeout =====

    @Test
    @DisplayName("AC-011/AF-06: registry en 5xx → REGISTRY_UNAVAILABLE")
    void ac011_registryEnError() {
        respuesta = ex -> new Respuesta(503, "{\"code\":\"INTERNAL_ERROR\"}");
        StepVerifier.create(adapter().listar())
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(QueryException.class);
                    assertThat(((QueryException) e).code)
                            .isEqualTo(QueryException.REGISTRY_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("503");
                })
                .verify();
    }

    @Test
    @DisplayName("AC-011: registry inaccesible (puerto cerrado) → REGISTRY_UNAVAILABLE acotado")
    void ac011_registryInaccesible() {
        RegistryProperties cfg = new RegistryProperties("http://127.0.0.1:1",
                new RegistryProperties.Auth("v@w", "p"), 1000L, 500L, 10, 100);
        RegistrySensoresAdapter adapter = new RegistrySensoresAdapter(cfg,
                Clock.fixed(T0, ZoneOffset.UTC));
        long inicio = System.currentTimeMillis();
        StepVerifier.create(adapter.listar())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify(Duration.ofSeconds(10));
        assertThat(System.currentTimeMillis() - inicio).isLessThan(5000L);
    }

    @Test
    @DisplayName("AC-011: un registry que demora más que el timeout falla acotado (nunca colgado)")
    void ac011_timeoutExplicito() {
        respuesta = ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Respuesta(200, "{\"items\":[],\"nextCursor\":null}");
        };
        long inicio = System.currentTimeMillis();
        StepVerifier.create(adapter(2, 100, 300L, 300L).listar())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify(Duration.ofSeconds(5));
        long transcurrido = System.currentTimeMillis() - inicio;
        assertThat(transcurrido).as("corta por el timeout configurado, no por el default")
                .isLessThan(2500L);
    }

    @Test
    @DisplayName("BR-007: el listado vacío no es un error (registry sin sensores)")
    void br007_listaVacia() {
        respuesta = ex -> new Respuesta(200, "{\"items\":[],\"nextCursor\":null}");
        assertThat(adapter().listar().collectList().block()).isEmpty();
        assertThat(peticiones).filteredOn(p -> p.path().equals("/api/sensores")).hasSize(1);
    }

    @Test
    @DisplayName("BR-007: el login usa las credenciales configuradas (no un usuario hardcodeado)")
    void br007_credencialesConfigurables() {
        List<String> cuerpos = new CopyOnWriteArrayList<>();
        stub.removeContext("/");
        stub.createContext("/", ex -> {
            peticiones.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getQuery(),
                    ex.getRequestHeaders().getFirst("Authorization")));
            boolean esLogin = "/api/auth/login".equals(ex.getRequestURI().getPath());
            if (esLogin) {
                cuerpos.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] body = (esLogin
                    ? "{\"token\":\"" + TOKEN + "\",\"expiraEnSegundos\":60}"
                    : "{\"items\":[" + sensorJson(id(1), "S-01") + "],\"nextCursor\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                ex.close();
            }
        });
        RegistryProperties cfg = new RegistryProperties(baseUrl,
                new RegistryProperties.Auth("otro@wrsensor.local", "Otra123!"), 1000L, 1000L, 10, 100);
        new RegistrySensoresAdapter(cfg, Clock.fixed(T0, ZoneOffset.UTC)).listar()
                .collectList().block();
        assertThat(cuerpos).hasSize(1);
        assertThat(cuerpos.get(0)).contains("otro@wrsensor.local").contains("Otra123!");
    }

    /** Guarda que el adapter no dependa de defaults de la librería cuando no hay config. */
    @Test
    @DisplayName("BR-007: sin configuración explícita el adapter usa defaults documentados")
    void br007_defaults() {
        RegistryProperties vacio = new RegistryProperties(null, null, null, null, null, null);
        assertThat(vacio.baseUrlOrDefault()).isEqualTo("http://localhost:8080");
        assertThat(vacio.timeoutMsOrDefault()).isEqualTo(5000L);
        assertThat(vacio.conexionTimeoutMsOrDefault()).isEqualTo(2000L);
        assertThat(vacio.limitPaginaOrDefault()).isEqualTo(200);
        assertThat(vacio.maxSensoresOrDefault()).isEqualTo(5000);
        assertThat(vacio.authOrDefault().email()).isEqualTo("viewer@wrsensor.local");
        // valores fuera de rango no rompen el arranque: vuelven al default
        assertThat(new RegistryProperties(null, null, null, null, 0, 0).limitPaginaOrDefault())
                .isEqualTo(200);
        assertThat(new RegistryProperties(null, null, null, null, 5000, 0).limitPaginaOrDefault())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("BR-007/AF-06: si falla una página intermedia no se puede armar el resumen (sin parciales)")
    void br007_sinParciales() {
        AtomicInteger llamadas = new AtomicInteger();
        respuesta = ex -> llamadas.incrementAndGet() == 1
                ? new Respuesta(200, "{\"items\":[" + sensorJson(id(1), "S-01")
                        + "],\"nextCursor\":\"c1\"}")
                : new Respuesta(500, "{\"code\":\"INTERNAL_ERROR\"}");
        // el resumen hace collectList: al fallar una página NO hay lista que devolver
        StepVerifier.create(adapter().listar().collectList())
                .expectErrorSatisfies(e -> assertThat(((QueryException) e).code)
                        .isEqualTo(QueryException.REGISTRY_UNAVAILABLE))
                .verify();
    }
}
