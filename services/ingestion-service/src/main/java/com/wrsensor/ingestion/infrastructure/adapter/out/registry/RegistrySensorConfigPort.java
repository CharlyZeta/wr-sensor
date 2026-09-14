package com.wrsensor.ingestion.infrastructure.adapter.out.registry;

import com.wrsensor.ingestion.domain.CircuitoResiliencia;
import com.wrsensor.ingestion.domain.RechazoLecturaException;
import com.wrsensor.ingestion.domain.SensorInfo;
import com.wrsensor.ingestion.infrastructure.config.IngestionProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter out: resuelve la config de un sensor contra `sensor-registry` por HTTP (FEAT-0011),
 * con la **resiliencia de FIX-0007**: timeouts explícitos de conexión/respuesta, circuit breaker
 * propio, cache de config con TTL + last-known-good y refresco del token ante {@code 401}.
 *
 * <p>El 404 (sensor inexistente) es una respuesta válida y **no** cuenta como fallo; un fallo de
 * red/timeout/5xx/401-persistente cuenta como fallo del circuito. Si no hay copia cacheada y el
 * registry no responde, se emite {@code REGISTRY_UNAVAILABLE} (BR-005).</p>
 */
@Component
public class RegistrySensorConfigPort implements com.wrsensor.ingestion.application.port.SensorConfigPort {

    private static final Logger log = LoggerFactory.getLogger(RegistrySensorConfigPort.class);

    /** Señal de 401 para forzar relogin (BR-006): no es un error de infraestructura final. */
    private static final class TokenInvalido extends RuntimeException {
        TokenInvalido() {
            super("token invalido o expirado");
        }
    }

    private record TokenJwt(String valor, Instant expiraEn) {}

    private final WebClient webClient;
    private final IngestionProperties.Registry cfg;
    private final CircuitoResiliencia circuito;
    private final CacheConfigSensores cache;
    private final Clock reloj;
    private final Duration ttl;
    private volatile TokenJwt tokenCache;

    private static final Pattern P_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern P_EXPIRA = Pattern.compile("\"expiraEnSegundos\":(\\d+)");
    private static final Pattern P_ESTADO = Pattern.compile("\"estado\":\"([A-Z]+)\"");
    private static final Pattern P_UNIDAD = Pattern.compile("\"unidadMedida\":\"([A-Z_]+)\"");
    private static final Pattern P_RANGO = Pattern.compile(
            "\"rango(Normal|Warning|Critical)\":\\{\"min\":(-?\\d+(?:\\.\\d+)?),\"max\":(-?\\d+(?:\\.\\d+)?)\\}");

    public RegistrySensorConfigPort(WebClient.Builder builder, IngestionProperties props,
                                    CircuitoResiliencia circuito, CacheConfigSensores cache,
                                    Clock reloj) {
        this.cfg = props.registry();
        this.circuito = circuito;
        this.cache = cache;
        this.reloj = reloj;
        this.ttl = Duration.ofSeconds(cfg.cache() == null ? 300L : cfg.cache().ttlSegundosOrDefault());
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) cfg.conexionTimeoutMsOrDefault())
                .responseTimeout(Duration.ofMillis(cfg.timeoutMsOrDefault()));
        this.webClient = builder.clone().baseUrl(cfg.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Override
    public Mono<SensorInfo> findById(UUID sensorId) {
        Instant ahora = Instant.now(reloj);
        CacheConfigSensores.Entrada entrada = cache.obtener(sensorId);
        if (entrada != null && !cache.vencida(entrada, ttl, ahora)) {
            cache.contabilizarAcierto();
            return Mono.just(entrada.info());
        }
        if (!circuito.permitir(ahora)) {
            return degradar(sensorId, entrada, "circuito abierto");
        }
        return lookup(sensorId, ahora)
                .doOnNext(info -> {
                    cache.poner(sensorId, info, ahora);
                    cache.contabilizarRefresco();
                })
                .onErrorResume(e -> degradar(sensorId, entrada, e.getMessage()));
    }

    // ============ lookup con un único reintento ante 401 ============

    private Mono<SensorInfo> lookup(UUID sensorId, Instant ahora) {
        return intento(sensorId, ahora, false)
                .onErrorResume(TokenInvalido.class, e -> intento(sensorId, ahora, true));
    }

    private Mono<SensorInfo> intento(UUID sensorId, Instant ahora, boolean reLogin) {
        if (reLogin) {
            invalidarToken();
        }
        return token()
                .flatMap(t -> webClient.get().uri("/api/sensores/{id}", sensorId)
                        .header("Authorization", "Bearer " + t)
                        .retrieve()
                        .onStatus(s -> s.value() == 404, r -> Mono.empty())
                        .onStatus(s -> s.value() == 401, r -> Mono.error(new TokenInvalido()))
                        .bodyToMono(String.class))
                // contabilidad del breaker por INTENTO HTTP: un 404 completa OK (éxito),
                // un error de red/timeout/5xx/401-persistente es un fallo.
                .doOnError(e -> circuito.registrarFallo(ahora))
                .doFinally(senal -> {
                    if (senal == SignalType.ON_COMPLETE) {
                        circuito.registrarExito(ahora);
                    }
                })
                .flatMap(json -> Mono.justOrEmpty(parse(json, sensorId)));
    }

    private Mono<SensorInfo> degradar(UUID sensorId, CacheConfigSensores.Entrada entrada, String motivo) {
        if (entrada != null) {
            cache.contabilizarVencidaUsada();
            long antiguedad = Duration.between(entrada.cargadoEn(), Instant.now(reloj)).toSeconds();
            log.warn("[ingestion] usando config vencida del sensor {} (antigüedad={}s): {}",
                    sensorId, antiguedad, motivo);
            return Mono.just(entrada.info());
        }
        log.warn("[ingestion] sensor {} sin config cacheada y registry no disponible: {}", sensorId, motivo);
        return Mono.error(new RechazoLecturaException(RechazoLecturaException.REGISTRY_UNAVAILABLE,
                "registry no disponible y sin config cacheada para " + sensorId + " (" + motivo + ")"));
    }

    // ============ token con expiración (BR-006) ============

    private Mono<String> token() {
        TokenJwt t = tokenCache;
        if (t != null && Instant.now(reloj).isBefore(t.expiraEn)) {
            return Mono.just(t.valor());
        }
        return login().map(nuevo -> {
            tokenCache = nuevo;
            return nuevo.valor();
        });
    }

    private void invalidarToken() {
        tokenCache = null;
    }

    private Mono<TokenJwt> login() {
        return webClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + cfg.auth().email() + "\",\"password\":\""
                        + cfg.auth().password() + "\"}")
                .retrieve().bodyToMono(String.class)
                .map(json -> {
                    Matcher m = P_TOKEN.matcher(json);
                    if (!m.find()) {
                        throw new IllegalStateException("login registry sin token");
                    }
                    Matcher e = P_EXPIRA.matcher(json);
                    long expira = e.find() ? Long.parseLong(e.group(1)) : 3600L;
                    return new TokenJwt(m.group(1), Instant.now(reloj).plusSeconds(expira));
                });
    }

    // ============ parseo de la respuesta (formato fijo del registry) ============

    private static SensorInfo parse(String json, UUID id) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Matcher estado = P_ESTADO.matcher(json);
        Matcher unidad = P_UNIDAD.matcher(json);
        BigDecimal nMin = null, nMax = null, wMin = null, wMax = null, cMin = null, cMax = null;
        Matcher r = P_RANGO.matcher(json);
        while (r.find()) {
            BigDecimal min = new BigDecimal(r.group(2));
            BigDecimal max = new BigDecimal(r.group(3));
            switch (r.group(1)) {
                case "Normal" -> { nMin = min; nMax = max; }
                case "Warning" -> { wMin = min; wMax = max; }
                case "Critical" -> { cMin = min; cMax = max; }
                default -> { }
            }
        }
        if (!estado.find() || !unidad.find() || nMin == null || wMin == null || cMin == null) {
            return null;
        }
        return new SensorInfo(id, "sensor", estado.group(1), unidad.group(1),
                new SensorInfo.Rango(nMin, nMax),
                new SensorInfo.Rango(wMin, wMax),
                new SensorInfo.Rango(cMin, cMax));
    }
}
