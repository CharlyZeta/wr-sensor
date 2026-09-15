package com.wrsensor.queryapi.infrastructure.adapter.out.registry;

import com.wrsensor.queryapi.application.port.SensoresMetadataPort;
import com.wrsensor.queryapi.domain.QueryException;
import com.wrsensor.queryapi.domain.SensorMetadata;
import com.wrsensor.queryapi.infrastructure.config.RegistryProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adapter out (FEAT-0008 BR-007): metadata de sensores contra {@code sensor-registry} por REST.
 *
 * <p>Paginación keyset hasta agotar ({@code nextCursor} nulo) con tope de seguridad
 * ({@code query.registry.max-sensores}) y {@code limit} de página configurable; timeouts explícitos
 * de conexión y de respuesta — nunca el default de la librería. Ante {@code 401} se reloguea **una**
 * vez por página (token cacheado con vencimiento); cualquier otro fallo (red, timeout, 4xx/5xx,
 * respuesta inválida) se propaga como {@code REGISTRY_UNAVAILABLE} **sin** items parciales
 * (AF-06/AC-011).</p>
 *
 * <p>El parseo usa un {@code JsonMapper} propio con la misma configuración que el consumidor de
 * Rabbit de este servicio (DTOs, sin propiedades desconocidas).</p>
 */
public class RegistrySensoresAdapter implements SensoresMetadataPort {

    private static final Logger log = LoggerFactory.getLogger(RegistrySensoresAdapter.class);

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder()
                    .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    /** Señal de 401: dispara un único relogin de la página, no un fallo definitivo. */
    private static final class TokenInvalido extends RuntimeException {
        TokenInvalido() {
            super("token del registry invalido o expirado");
        }
    }

    private record TokenJwt(String valor, Instant expiraEn) {}

    /** Página del listado keyset del registry ({@code GET /api/sensores}). */
    private record PaginaDto(List<SensorDto> items, String nextCursor) {}

    /** Sensor tal como lo publica el registry (subconjunto que usa el mapa). */
    private record SensorDto(String id, String codigo, String nombre, String tipo, BigDecimal latitud,
                             BigDecimal longitud, String estado, String unidadMedida) {}

    private record LoginResponseDto(String token, Integer expiraEnSegundos) {}

    private record CredencialesDto(String email, String password) {}

    private record Pagina(List<SensorMetadata> items, String nextCursor) {}

    private final WebClient webClient;
    private final RegistryProperties cfg;
    private final Clock reloj;
    private volatile TokenJwt tokenCache;

    public RegistrySensoresAdapter(RegistryProperties cfg, Clock reloj) {
        this.cfg = cfg;
        this.reloj = reloj;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) cfg.conexionTimeoutMsOrDefault())
                .responseTimeout(Duration.ofMillis(cfg.timeoutMsOrDefault()));
        this.webClient = WebClient.builder().baseUrl(cfg.baseUrlOrDefault())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    @Override
    public Flux<SensorMetadata> listar() {
        return token().flatMapMany(this::paginasDeTodos)
                // BR-007: cualquier fallo del registry se ve como REGISTRY_UNAVAILABLE (502),
                // nunca como lista parcial ni como 500 del propio query-api.
                .onErrorMap(e -> e instanceof QueryException ? e
                        : new QueryException(QueryException.REGISTRY_UNAVAILABLE,
                                "registry no disponible: " + e.getMessage()));
    }

    // ============ paginación keyset hasta agotar, con tope de seguridad ============

    private Flux<SensorMetadata> paginasDeTodos(String token) {
        return pedir(token, null).flux()
                .expand(pagina -> pagina.nextCursor() == null || pagina.items().isEmpty()
                        ? Mono.empty() : pedir(token, pagina.nextCursor()))
                .flatMapIterable(Pagina::items)
                .take(cfg.maxSensoresOrDefault());
    }

    /**
     * Una página. Un {@code 401} se resuelve con **un** relogin y la repetición de esa misma página
     * (no se reinicia la paginación, así no hay items duplicados).
     */
    private Mono<Pagina> pedir(String token, String cursor) {
        return pedirCon(token, cursor)
                .onErrorResume(TokenInvalido.class, e -> {
                    invalidarToken();
                    return token().flatMap(nuevo -> pedirCon(nuevo, cursor));
                });
    }

    private Mono<Pagina> pedirCon(String token, String cursor) {
        int limit = cfg.limitPaginaOrDefault();
        return webClient.get()
                .uri(builder -> {
                    builder.path("/api/sensores").queryParam("limit", limit);
                    if (cursor != null && !cursor.isBlank()) {
                        builder.queryParam("cursor", cursor);
                    }
                    return builder.build();
                })
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> status.value() == 401, respuesta -> Mono.error(new TokenInvalido()))
                .onStatus(status -> status.isError(),
                        respuesta -> Mono.error(new IllegalStateException(
                                "registry respondio " + respuesta.statusCode().value())))
                .bodyToMono(String.class)
                .map(this::parsePagina);
    }

    private Pagina parsePagina(String cuerpo) {
        PaginaDto dto;
        try {
            dto = MAPPER.readValue(cuerpo == null || cuerpo.isBlank() ? "{}" : cuerpo,
                    PaginaDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("respuesta invalida del registry: " + e.getMessage(), e);
        }
        if (dto == null || dto.items() == null) {
            throw new IllegalStateException("respuesta del registry sin 'items'");
        }
        List<SensorMetadata> sensores = new ArrayList<>(dto.items().size());
        for (SensorDto s : dto.items()) {
            sensores.add(parseSensor(s));
        }
        return new Pagina(sensores, dto.nextCursor());
    }

    private static SensorMetadata parseSensor(SensorDto s) {
        if (s == null || s.id() == null) {
            throw new IllegalStateException("sensor del registry sin 'id'");
        }
        UUID id;
        try {
            id = UUID.fromString(s.id().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("sensor con id no UUID: " + s.id());
        }
        return new SensorMetadata(id, s.codigo(), s.nombre(), s.tipo(), s.latitud(), s.longitud(),
                s.estado(), s.unidadMedida());
    }

    // ============ token con expiración (una sola vez por ventana) ============

    private Mono<String> token() {
        TokenJwt cacheado = tokenCache;
        if (cacheado != null && Instant.now(reloj).isBefore(cacheado.expiraEn())) {
            return Mono.just(cacheado.valor());
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
        RegistryProperties.Auth auth = cfg.authOrDefault();
        String cuerpo;
        try {
            cuerpo = MAPPER.writeValueAsString(new CredencialesDto(
                    auth.email() == null ? "" : auth.email(),
                    auth.password() == null ? "" : auth.password()));
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("no se pudo serializar el login", e));
        }
        return webClient.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseLogin)
                .doOnError(e -> log.warn("[query-api] login contra el registry fallo: {}",
                        e.getMessage()));
    }

    private TokenJwt parseLogin(String cuerpo) {
        LoginResponseDto dto;
        try {
            dto = MAPPER.readValue(cuerpo == null ? "{}" : cuerpo, LoginResponseDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("login del registry con respuesta invalida: "
                    + e.getMessage(), e);
        }
        if (dto == null || dto.token() == null || dto.token().isBlank()) {
            throw new IllegalStateException("login del registry sin token");
        }
        long segundos = dto.expiraEnSegundos() == null ? 3600L : dto.expiraEnSegundos();
        return new TokenJwt(dto.token(), Instant.now(reloj).plusSeconds(segundos));
    }
}
