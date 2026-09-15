# WR-Sensor — Arquitectura

> Documentación senior · fecha 2026-09-09 · fuente: spec `docs/water-monitoring-spec.md`,
> `stack.md` y contracts SDD-GL.

## 1. Visión de sistema

WR-Sensor es una plataforma de telemetría fluvial por **microservicios reactivos
(Java 25 / Spring Boot 4.1 WebFlux)** con arquitectura hexagonal y mensajería
RabbitMQ como columna vertebral. Los 6 servicios viven como módulos Maven
**standalone** (`services/<nombre>`, pom propio) y se orquestan con el aggregator
`services/pom.xml` + `docker-compose.yml`.

### Diagrama de flujo de datos

```
   clientes (dashboard / curl)                    ┌────────────────────┐
            │ HTTPS/WS (puerto único 8084)        │  sensor-registry   │ CRUD + auth JWT
            ▼                                     │  8080 · Postgres   │
   ┌──────────────────┐  REST/WS  ┌──────────────▶└────────┬───────────┘
   │   api-gateway    │───────────┤  8082 query-api (histórico/última/WS)
   │  (FEAT-0007)     │           └──────────────▶ 8083 alerting-service (WS /ws/alertas)
   │  rate limit +    │
   │  correlación     │        red interna de Compose (sin puertos publicados)
   └──────────────────┘
                                  ┌────────────────┐   publica   ┌──────────────────┐
                                  │ data-simulator │───────────▶ │ ingestion-service│
                                  │ (generador)    │  sensor.    │ (consumer)       │
                                  └────────────────┘  lecturas   └────────┬─────────┘
                                                                          │ persiste (hypertable 'lectura')
                                                                          ▼  TimescaleDB
                                                                          │ publica (cambios de severidad)
                                                                          ▼  sensor.alertas → alerting-service
```

**Topología de puertos (FEAT-0007 BR-012):** sólo `api-gateway` (:8084) publica puerto al
host. `sensor-registry`, `query-api`, `alerting-service`, `data-simulator` e
`ingestion-service` viven en la red interna de Compose; el acceso directo para debug se
habilita con `docker-compose.dev.yml` (ver RUNBOOK §4). Los puertos de infraestructura
(RabbitMQ, Postgres, Timescale, Redis) siguen publicados por ser herramientas de desarrollo.

### Rutas del gateway (FEAT-0007 + FEAT-0008)

| Ruta | Patrón | Destino | Clase de límite |
|---|---|---|---|
| `registry-login` | `POST /api/auth/login` | `sensor-registry:8080` | `login` (10/60 s) |
| `registry-auth` | `POST /api/auth/**` | `sensor-registry:8080` | `default` |
| `query-resumen` | `GET /api/sensores/resumen` | `query-api:8082` | `lectura` (120/60 s) |
| `registry-sensores-lectura` | `GET /api/sensores/**` | `sensor-registry:8080` | `lectura` |
| `registry-sensores-escritura` | `POST/PUT/DELETE /api/sensores/**` | `sensor-registry:8080` | `default` (300/60 s) |
| `query-lecturas` | `GET /api/sensores/*/lecturas` | `query-api:8082` | `lectura` |
| `query-actual` | `GET /api/sensores/*/actual` | `query-api:8082` | `lectura` |
| `ws-alertas` | `/ws/alertas` (upgrade) | `alerting-service:8083` | `ws` (30/60 s) |
| `ws-sensores` | `/ws/sensores/**` (upgrade) | `query-api:8082` | `ws` |

Gana siempre el **patrón más específico** (`PathPattern.SPECIFICITY_COMPARATOR`), que es lo
que permite que `/api/sensores/{id}/lecturas` y `/api/sensores/resumen` vayan a `query-api`
mientras el resto de `/api/sensores/**` va al registry. Cualquier path no declarado responde
`404 ROUTE_NOT_FOUND` sin fallback (el **simulador** sigue fuera del gateway: la demo usa
`docker-compose.dev.yml`).

### Cadena de filtros del gateway (FEAT-0008)

```
request ─▶ FiltroCors (HIGHEST_PRECEDENCE)
             │  · sin Origin, o Origin propio ⇒ pasa sin headers (no es CORS)
             │  · preflight de origen permitido ⇒ 204 + headers, FIN (sin cupo, sin downstream)
             │  · origen cruzado no permitido ⇒ 403 ORIGIN_NOT_ALLOWED sin headers Access-Control-*
             ▼
           FiltroCorrelacion (+10)  → X-Correlation-Id + log de acceso (path, nunca el query)
             ▼
           FiltroRateLimit (+20)    → token bucket por clase|IP ⇒ 429 + Retry-After
             ▼
           RouterFunction           → ManejadorRuta (REST) | ManejadorWs (túnel WS) | 404
```

### Autenticación del handshake WebSocket (FEAT-0008)

Los WS viven en `alerting-service` y `query-api`, que no tienen auth propia; el navegador, además,
**no puede** mandar `Authorization` en el upgrade. Por eso el gateway valida el JWT HS256
(secreto compartido `AUTH_JWT_SECRET`, firma + `exp`, comparación constant-time) **antes** de
completar el handshake y antes de contactar al downstream:

```
SPA ──GET /ws/alertas?token=<jwt>──▶ gateway
                                     │ 1. AutenticadorWs: token del query o de Authorization: Bearer
                                     │ 2. VerificadorJwt: firma + exp + rol ∈ {ADMIN, VIEWER}
                                     │    ✗ ⇒ 401 UNAUTHENTICATED / 403 INSUFFICIENT_ROLE (sin sesión)
                                     │ 3. quita ?token= de la URL del downstream (no se propaga)
                                     ▼
                          clienteWs.execute("ws://alerting-service:8083/ws/alertas") ─▶ downstream
                                     │ 4. recién entonces HandshakeWebSocketService completa el upgrade
                                     ▼
                                  sesión puente en ambos sentidos
```

Consecuencias: un WS sin token nunca abre sesión ni llega al downstream (el downstream no
registra conexiones), el token no aparece en los logs de acceso, y el rechazo es un `401` HTTP
(nunca un cierre de sesión ya aceptada). Ver `DECISIONES.md` ADR-0019.


## 2. Contratos de mensajería (RabbitMQ)

| Exchange | Tipo | Routing key | Productor | Consumidor | Payload |
|---|---|---|---|---|---|
| `sensor.lecturas` | topic (durable) | `lectura.{sensorId}` | `data-simulator` (FEAT-0010) | `ingestion-service` (FEAT-0011), `query-api` (FEAT-0013) | **v1** (FIX-0006, ver abajo) |
| `sensor.alertas` | topic (durable) | `alerta.{severidad}` | `ingestion-service` (FEAT-0011) | `alerting-service` (FEAT-0012) | `{sensorId, timestamp, valorLectura, severidadAnterior, severidadNueva, cruceHisteresis}` |

### Payload v1 de `sensor.lecturas` (FIX-0006)

```json
{
  "schemaVersion": "1.0",
  "eventId": "3f2a1b8e-…",
  "sensorId": "a1b2…",
  "timestamp": "2026-09-13T16:32:06.123Z",
  "valor": 5.12,
  "unidadMedida": "METROS",
  "sequence": 42,
  "calidad": { "estado": "OK", "confianza": 0.95, "codigosAnomalias": [] }
}
```

- **Requeridos**: `sensorId`, `timestamp`, `valor`, `unidadMedida`. **Opcionales y tolerados**:
  todo lo demás, **incluidos campos de versiones futuras** (los consumers ignoran propiedades
  desconocidas).
- `schemaVersion` (`MAYOR.MENOR`) es **configuración del publisher**
  (`simulador.lecturas.schema-version`, default `1.0`), nunca una constante en los consumers.
- `eventId` (UUID v4 por publicación) = trazabilidad + clave de idempotencia explícita
  (`evt:<eventId>`, FIX-0003). `sequence` = contador creciente **por sensor** en el simulador,
  que se reinicia al detener/reiniciar la simulación.
- `calidad` es **informativa**: `estado ∈ {OK, ERROR_SENSOR}` (el enum vigente de FIX-0004),
  `confianza ∈ [0,1]` (baja durante la anomalía inyectada) y `codigosAnomalias`
  (`["ANOMALIA_INYECTADA"]` en esa ventana). Un evento con `estado = ERROR_SENSOR` se persiste
  con esa calidad y **sin** severidad ni alerta; `SOSPECHOSA` no existe.
- `timestamp` se mantiene con ese nombre y siempre es ISO-8601 **con offset** (`Instant`/`Z`);
  un timestamp naive se rechaza (`PAYLOAD_INVALID`).

### Política de evolución y versiones (FIX-0006)

| Caso | Comportamiento |
|---|---|
| Sin `schemaVersion` (payload plano previo a FIX-0006) | **legado `0.0`**: se procesa igual; se registra INFO de evento legado con contador (ventana de compatibilidad **indefinida**, medida para poder cerrarla con datos) |
| `schemaVersion` con mayor igual a la soportada (`1.x`) | se procesa; sin avisos |
| Mayor **desconocida** (`2.0`, `9.x`) | **se procesa** con WARN (una sola vez por versión): tolerancia hacia adelante — un publisher más nuevo no tumba la ingesta. Con `ingestion.schema.tolerar-versiones-mayores: false` se rechaza a la DLQ con `SCHEMA_UNSUPPORTED` |
| `schemaVersion` no interpretable (`"uno"`) o campos requeridos ausentes | rechazo `PAYLOAD_INVALID` (DLQ en ingestion; descarte logueado en query-api) |

Ambos consumers de `sensor.lecturas` (`ingestion-service` y `query-api`) parsean con **DTO +
Jackson** tolerante a propiedades desconocidas: el `schemaVersion` se resuelve de verdad y no por
casualidad de una expresión regular.

- **Colas**: `queue.sensor.alertas` (bind `alerta.#`), durable. Las lecturas **no** se
  consumen de una cola única: `ingestion-service` las consume **particionadas por
  `sensorId`** (FIX-0005).
- **Particionamiento de lecturas (FIX-0005)**: el exchange topic `sensor.lecturas` reenvía
  con un binding **exchange-to-exchange** (`lectura.#`) al exchange **`sensor.lecturas.part`**
  de tipo **`x-consistent-hash`**, que hashea la routing key (`lectura.{sensorId}`) y enruta
  cada mensaje a **una** de las `N` colas `queue.sensor.lecturas.p0 … p{N-1}` (peso de binding
  `"1"`). Consecuencia: **afinidad sensor → partición → instancia**, que es lo que permite
  escalar horizontalmente sin perder el orden por sensor ni el estado en memoria
  `ultimaSeveridad`. El publisher (`data-simulator`) **no** cambia.
  - `N` y la asignación por instancia son configurables (`ingestion.particiones.total`,
    `ingestion.particiones.asignadas`, default 4 y "todas"); variables de entorno
    `INGESTION_PARTICIONES_TOTAL` / `INGESTION_PARTICIONES_ASIGNADAS`.
  - Cada instancia declara la topología completa (idempotente) y **consume solo** sus
    particiones asignadas: un consumer por partición, `qos = 1`, procesamiento secuencial.
  - Requiere el exchange type del plugin `rabbitmq_consistent_hash_exchange`, habilitado en
    el broker (`infra/rabbitmq/enabled_plugins`, montado por `docker-compose.yml`) y en los
    tests de integración. Sin él la instancia registra ERROR y **no** consume (fail-fast).
  - Las colas de partición conservan `x-dead-letter-exchange` y la DLQ compartida
    `queue.sensor.lecturas.dlq` con header `x-rechazo`.
- **DLQ/DLX por consumidor**: exchange fanout `sensor.lecturas.dlx` /
  `sensor.alertas.dlx` con cola DLQ y header `x-rechazo` (motivo). Retry y DLX
  configurables en `application.yml` (`messaging.retry.*`,
  `messaging.dead-letter.exchange`), nunca hardcodeados (spec §9.3).
- **Clientes**: Reactor RabbitMQ (`Sender`/`Receiver`), nunca `RabbitTemplate`.

## 3. Contratos de datos

### sensor-registry — tabla `sensor` (Postgres, metadata)
`id UUID PK · codigo UNIQUE · nombre · tipo (RIO…) · latitud/longitud · unidad_medida ·
estado (ACTIVO|INACTIVO|MANTENIMIENTO) · histeresis · frecuencia_reporte_segundos ·
fecha_instalacion · rango_normal/warning/critical (min,max)` — TIMESTAMP naive-UTC.

### sensor-registry — tabla `usuario` (auth)
`id UUID PK · email UNIQUE (minúsculas) · password_hash (BCrypt) · rol (ADMIN|VIEWER)`.

### ingestion-service — hypertable `lectura` (TimescaleDB)
`sensor_id UUID · ts TIMESTAMPTZ (columna de partición) · valor NUMERIC(12,2) ·
unidad_medida · severidad (NORMAL|WARNING|CRITICAL)`. Retención raw 90 días +
compresión día 8 + continuous aggregates = política TimescaleDB futura (spec §9.2).

## 4. Decisiones de arquitectura por servicio (resumen; detalle en DECISIONES.md)

- **sensor-registry** (FEAT-0001..0006, FIX-0001): hexagonal; guards de acceso
  resuelven el rol **una vez por request como atributo del exchange**
  (`RolGuard.ATTR_ROL`) — fix de una carrera de Reactor Context detectada en el Loop
  de FEAT-0005; timestamps persistidos en **naive-UTC** (`LocalDateTime.ofInstant`)
  — fix del tie-break keyset de FEAT-0002; JWT HS256 con JDK estándar (sin libs
  nuevas; ver DECISIONES).
- **data-simulator** (FEAT-0010): stateless; un `Flux.interval` por sensor; payload
  JSON manual; control `iniciar/detener/{id}/anomalia/estado` sin auth (dev).
- **ingestion-service** (FEAT-0011): consumer con ack tras persistir; config del
  sensor vía REST a registry (cache); severidad por bandas inclusivas; último estado
  en memoria; eventos solo en cambio de severidad.
- **ingestion-service — resiliencia del lookup** (FIX-0007): timeouts de
  respuesta/conexión explícitos; **circuit breaker propio** en el dominio
  (CERRADO/ABIERTO/SEMIABIERTO, reloj inyectado); **cache de config con TTL +
  last-known-good** (copia vencida se usa antes que perder lecturas); refresco del
  token del registry ante `401`; motivo de DLQ `REGISTRY_UNAVAILABLE` y endpoint
  interno `GET /api/ingestion/resiliencia` (sin `actuator`). Umbrales por default:
  5 fallos, 30 s abierto, 2 éxitos para cerrar, TTL 300 s (configurables).
- **alerting-service** (FEAT-0012): histéresis por **debounce temporal** (subida
  inmediata; bajada confirmada tras ventana `alerting.histeresis-segundos`;
  re-subida cancela); broadcast reactivo `Sinks` → WS `/ws/alertas`.
- **api-gateway** (FEAT-0007 + FEAT-0008): proxy declarativo (tabla de rutas en
  `application.yml`, patrón más específico primero) con rate limiting por clase|IP,
  correlación, higiene de headers hop-by-hop y túnel WS; **CORS configurable** resuelto
  en un `WebFilter` de máxima precedencia que responde el preflight él mismo (204, sin
  cupo, sin downstream) y **autenticación del handshake WS** con un verificador HS256
  propio (JDK crypto, sin dependencias nuevas) que valida firma + `exp` + rol antes de
  abrir sesión y sin propagar el token.
- **query-api — resumen del mapa** (FEAT-0008): `GET /api/sensores/resumen` compone
  metadata del registry (REST paginado keyset, timeout explícito, credenciales VIEWER)
  con la última lectura de **todos** los sensores en una sola consulta
  (`DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC`); el registry caído devuelve
  `502 REGISTRY_UNAVAILABLE` sin datos parciales.

## 5. Códigos de error (convención)

Toda respuesta de error: `{"code":"...","message":"..."}`. Familia
`sensor-registry`: `SENSOR_INVALID_*`, `SENSOR_CODE_DUPLICATED`, `SENSOR_NOT_FOUND`,
`UNAUTHENTICATED`, `INSUFFICIENT_ROLE`, `INVALID_CREDENTIALS`.
`data-simulator`: `SIMULATOR_ALREADY_RUNNING`, `SIMULATOR_NOT_RUNNING`,
`SENSOR_NOT_FOUND`. Rechazos de consumo (DLQ): `PAYLOAD_INVALID`, `SENSOR_UNKNOWN`,
`SENSOR_INACTIVE`, `TIMESTAMP_OUT_OF_WINDOW`, `INFRA_ERROR` (más
`SCHEMA_UNSUPPORTED` y `REGISTRY_UNAVAILABLE` de FIX-0006/FIX-0007).
`api-gateway`: `ROUTE_NOT_FOUND`, `RATE_LIMIT_EXCEEDED`, `UPSTREAM_UNAVAILABLE`,
`UPSTREAM_TIMEOUT`, `WS_UPGRADE_REQUIRED`, `INTERNAL_ERROR` y, desde FEAT-0008,
`UNAUTHENTICATED`, `INSUFFICIENT_ROLE`, `ORIGIN_NOT_ALLOWED`.
`query-api`: `SENSOR_INVALID_*`, `INVALID_RANGE`, `SENSOR_NOT_FOUND`, `UNAUTHENTICATED`,
`INSUFFICIENT_ROLE` y `REGISTRY_UNAVAILABLE` (FEAT-0008, `502`).

## 6. Frontend / infra futura

- **Frontend React + Leaflet** (`FEAT-0009`, siguiente): Fase A = login + mapa con el
  resumen (`GET /api/sensores/resumen`) + detalle en vivo por WS + feed de alertas +
  serie de 24 h. El SPA lo sirve el **gateway** (mismo origen; decisión de FEAT-0008
  BR-009/ADR-0019), con lo que la lista de CORS puede quedar vacía en producción.
- `docker-compose.yml` con los 4+2 servicios, Postgres+TimescaleDB, Redis, RabbitMQ.
- Redis para "última lectura" de query-api (FEAT-0013; pendiente) y caché del resumen
  (fuera de alcance de FEAT-0008).
- Manifiestos K8s (sólo documentación hasta que haya infra destino).

