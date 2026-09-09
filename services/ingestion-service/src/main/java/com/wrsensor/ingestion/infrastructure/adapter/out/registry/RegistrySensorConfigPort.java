package com.wrsensor.ingestion.infrastructure.adapter.out.registry;

import com.wrsensor.ingestion.application.port.SensorConfigPort;
import com.wrsensor.ingestion.domain.SensorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter out: config del sensor via **REST a sensor-registry** (decision HO-Gate)
 * con cache en memoria. Login con credencial VIEWER para obtener el JWT; GET
 * /api/sensores/{id} → SensorInfo. 404 → empty (SENSOR_UNKNOWN). Parseo del JSON
 * con regex (formato fijo del registry; sin libs extra).
 */
@Component
public class RegistrySensorConfigPort implements SensorConfigPort {

    private static final Logger log = LoggerFactory.getLogger(RegistrySensorConfigPort.class);

    private final WebClient webClient;
    private final String authEmail;
    private final String authPassword;
    private final ConcurrentHashMap<UUID, SensorInfo> cache = new ConcurrentHashMap<>();
    private volatile String tokenCache;

    private static final Pattern P_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern P_ESTADO = Pattern.compile("\"estado\":\"([A-Z]+)\"");
    private static final Pattern P_UNIDAD = Pattern.compile("\"unidadMedida\":\"([A-Z_]+)\"");
    private static final Pattern P_RANGO = Pattern.compile(
            "\"rango(Normal|Warning|Critical)\":\\{\"min\":(-?\\d+(?:\\.\\d+)?),\"max\":(-?\\d+(?:\\.\\d+)?)\\}");

    public RegistrySensorConfigPort(WebClient.Builder builder,
                                    @org.springframework.beans.factory.annotation.Value("${ingestion.registry.base-url}") String baseUrl,
                                    @org.springframework.beans.factory.annotation.Value("${ingestion.registry.auth.email}") String authEmail,
                                    @org.springframework.beans.factory.annotation.Value("${ingestion.registry.auth.password}") String authPassword) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.authEmail = authEmail;
        this.authPassword = authPassword;
    }

    @Override
    public Mono<SensorInfo> findById(UUID sensorId) {
        SensorInfo cached = cache.get(sensorId);
        if (cached != null) return Mono.just(cached);
        return obtenerToken().flatMap(token ->
                        webClient.get().uri("/api/sensores/{id}", sensorId)
                                .header("Authorization", "Bearer " + token)
                                .retrieve()
                                .onStatus(s -> s.value() == 404, r -> Mono.empty())
                                .bodyToMono(String.class))
                .flatMap(json -> Mono.justOrEmpty(parse(json, sensorId)))
                .doOnNext(info -> cache.put(sensorId, info));
    }

    private Mono<String> obtenerToken() {
        String t = tokenCache;
        if (t != null) return Mono.just(t);
        return webClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + authEmail + "\",\"password\":\"" + authPassword + "\"}")
                .retrieve().bodyToMono(String.class)
                .map(json -> {
                    Matcher m = P_TOKEN.matcher(json);
                    if (!m.find()) throw new IllegalStateException("login registry sin token");
                    tokenCache = m.group(1);
                    return m.group(1);
                });
    }

    private static SensorInfo parse(String json, UUID id) {
        if (json == null || json.isBlank()) return null;
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
        if (!estado.find() || !unidad.find() || nMin == null || wMin == null || cMin == null) return null;
        return new SensorInfo(id, "sensor", estado.group(1), unidad.group(1),
                new SensorInfo.Rango(nMin, nMax),
                new SensorInfo.Rango(wMin, wMax),
                new SensorInfo.Rango(cMin, cMax));
    }
}
