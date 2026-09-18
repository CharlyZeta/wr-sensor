# Changelog — WR-Sensor (proyecto)

> Registro de cambios **del proyecto** WR-Sensor, pensado para lectura externa.
> El `CHANGELOG.md` de la raíz pertenece al **frame SDD-GL** (0.1.0 → 0.3.0, las herramientas de
> Gate/Loop), no a este proyecto: acá se registran los work items resueltos con su Contract, su
> auditoría Glass Box y las suites que los respaldan.
> Formato inspirado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/), fechas ISO-8601.
> Índice de estado vigente: [`docs/ESTADO-SDD.md`](ESTADO-SDD.md) · decisiones: [`docs/DECISIONES.md`](DECISIONES.md).

## [FIX-0008] — 2026-09-15 — Endurecimiento del punto de entrada (revisión de seguridad)

`contracts/FIX-0008.md` (24/24 ✅) · ADR-0020 · informe de seguridad en `.sdd/runs/FIX-0008-20260915-*.md` ·
suites `api-gateway` 78 unit + 37 IT (nuevos `FIX0008SeguridadTest` 10, `FIX0008MainFlowIT` 8,
`FIX0008DocsTest` 6).

**Corregido — hallazgo S1 (alto): el gateway aceptaba tokens forjables.**
`docker-compose.yml` no le pasaba `AUTH_JWT_SECRET` al servicio `api-gateway`, así que caía al
default de desarrollo —**público en el repositorio**— mientras el registry usaba el secreto real.
Como el gateway es quien valida el JWT del handshake WebSocket (`FEAT-0008`), en un despliegue con el
secreto rotado cualquiera podía acuñar un token `ADMIN`/`VIEWER` con el valor del repo y **abrir la
telemetría y las alertas sin credenciales**. Ahora el secreto se propaga con el mismo default por
entorno que el registry, y fuera de los perfiles de desarrollo el gateway **no arranca** si sigue
siendo el de desarrollo (en dev arranca con WARN explícito).

**Agregado — headers de seguridad y CSP (S3, requisito A1).**
Antes había **cero** headers de seguridad en todo el repositorio. El gateway aplica ahora, a toda
respuesta y **una sola vez** (descarta los que manda el downstream, para que un servicio comprometido
no pueda pisarlos): `Content-Security-Policy` (con `script-src 'self'`, sin `unsafe-inline` ni
`unsafe-eval` —el vector que roba el token—, `img-src` acotado a lo propio y a los tiles
configurados, `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`),
`X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
`Permissions-Policy`, `Cross-Origin-Resource-Policy: same-origin` y HSTS **sólo** cuando la request
llegó por HTTPS. Todo configurable en `gateway.seguridad.*`.

**Agregado — hosting del SPA con resolución segura (S2/A6).**
El catch-all del router impedía servir el SPA. Ahora el gateway sirve los estáticos de
`classpath:/static/` con **resolución contenida** (rechaza `..`, `%2e%2e`, backslash, byte nulo y
rutas absolutas; nunca sale de la raíz), **lista explícita de rutas de cliente** para el fallback del
router del SPA (`/`, `/login`, `/mapa`, `/sensores/**`), `404` para cualquier asset inexistente
(nunca `200` con HTML para un `<script>` roto), **prefijos reservados** `/api/**` y `/ws/**` que jamás
reciben el índice, y caché explícita (`no-store` para el índice, inmutable por hash para los assets).
El `404 ROUTE_NOT_FOUND` de la API y el `426`/`401` del WS siguen verificados por sus ITs.

**Fuera de alcance (declarado):** los requisitos del SPA que surgieron de la misma revisión
(sinks de XSS en Leaflet, logout atómico, validación de payloads de WS, expiración) se cubren en
`FEAT-0009`/`FEAT-0014`/`FEAT-0015`/`FEAT-0016`; autenticación de los REST en el gateway, refresh y
revocación de tokens, cookies/CSRF (no aplican: el backend no emite cookies), WAF, CAPTCHA, mTLS y
`X-XSS-Protection` (obsoleto). Se documenta que `docker-compose.dev.yml` publica los puertos de los
servicios y no debe usarse fuera de una máquina de desarrollo.

## [No publicado] — serie del frontend (`FEAT-0009`, `FEAT-0014`, `FEAT-0015`, `FEAT-0016`) · en Gate

Los habilitadores del backend (`FEAT-0008`) están cerrados, así que el SPA ya se puede especificar
sin tocar ningún servicio. El alcance se dividió en **4 contracts por capacidad funcional**
(división aprobada por el humano 2026-09-14), todos en **DRAFT/GATE** esperando el HO-Gate:

| Parte | Contract | Alcance | Criterios | Gate |
|---|---|---|---|---|
| 1 | `FEAT-0009` | SPA núcleo: sesión (`sessionStorage`), shell/rutas, cliente API (errores por `code`, `429`/`Retry-After`), **mapa Leaflet** con `GET /api/sensores/resumen` y **hosting del SPA desde el gateway** | 32 | STRICT |
| 2 | `FEAT-0014` | detalle en vivo: `WS /ws/sensores/{id}?token=` (backoff + estados) y serie de 24 h con histórico keyset | 29 | EXPRESS |
| 3 | `FEAT-0015` | alertas en vivo: `WS /ws/alertas?token=`, feed con dedupe, contador de críticas y refresco del mapa con debounce | 29 | EXPRESS |
| 4 | `FEAT-0016` | administración y demo (Fase B): CRUD de sensores (ADMIN) con errores de dominio por `code` y panel del simulador configurable | 31 | STRICT |

Stack propuesto para el SPA (decisiones del Gate marcadas "sujeto a HO-Gate"): **Vite + React +
TypeScript** en `web/`, **Vitest + RTL + MSW** (+ Playwright para e2e), CSS Modules con tokens
propios, react-router + hooks sobre `fetch`, gráfico de la serie en SVG propio, build por npm copiado
al gateway en un stage de Node de la imagen Docker (el `mvn -o` del backend queda intacto).
Nada de esto cambia el contrato de los servicios; si aparece una necesidad de backend (p. ej.
severidad en el payload del WS de lecturas) se abre un `FIX` propio.

## [FEAT-0008] — 2026-09-14 — Habilitadores del frontend: CORS, WS autenticado y resumen de sensores

`contracts/FEAT-0008.md` (31/31 ✅, único contract con `Gate-Mode: STRICT`) · ADR-0019 ·
suites `api-gateway` 68 unit + 29 IT · `query-api` 33 unit + 8 IT.

**Agregado — `api-gateway`:**

- **CORS configurable** (`gateway.cors.*`, lista de orígenes **vacía por default** = same-origin
  only, `allow-credentials: false`). El **preflight lo responde el gateway él mismo**: `204` con
  `Access-Control-Allow-Origin/Methods/Headers/Max-Age`, **sin consumir cupo de rate limit** (el
  filtro corre antes) y **sin llegar al downstream**. Se exponen `X-Correlation-Id`,
  `X-RateLimit-Limit/Remaining` y `Retry-After` para que el SPA pueda mostrar correlación y cupo.
- **`403 ORIGIN_NOT_ALLOWED` sin headers `Access-Control-*`** para orígenes cruzados ajenos.
- **Autenticación del handshake WebSocket** (`?token=<jwt>` o `Authorization: Bearer`): verificador
  HS256 propio (`VerificadorJwt`, JDK crypto, sin dependencias nuevas) que valida **firma + `exp` +
  rol** antes de completar el upgrade; sin token válido → `401 UNAUTHENTICATED` (rol no autorizado →
  `403 INSUFFICIENT_ROLE`) **sin abrir sesión y sin contactar al downstream**. El token **no se
  propaga** (se quita del query antes de reenviar) **ni se loguea** (el log de acceso registra sólo
  el path).
- Ruta declarada **`query-resumen`**: `GET /api/sensores/resumen` → `query-api` con clase de límite
  `lectura`, ganando por patrón más específico sobre `/api/sensores/**`.

**Agregado — `query-api`:**

- **`GET /api/sensores/resumen`** (roles `{ADMIN, VIEWER}`): array con **todos** los sensores
  (`id, codigo, nombre, tipo, latitud, longitud, estado, unidadMedida`) y su
  `ultimaLectura {valor, timestamp, severidad, calidad}`; los sensores sin lecturas aparecen con
  `ultimaLectura: null` (nunca se omiten). Fuente única del mapa y del listado del SPA.
- **Una sola consulta** a la hypertable para las últimas lecturas de todos los sensores
  (`DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC`, port propio `UltimasLecturasPort`) —
  nunca N+1, verificado con un port fake que cuenta invocaciones.
- **Cliente REST al `sensor-registry`** con paginación keyset hasta agotar, tope de seguridad
  configurable, **timeouts explícitos** de conexión/respuesta, token cacheado con vencimiento y
  **relogin único ante `401`**. Si el registry no responde o responde con error → **`502
  REGISTRY_UNAVAILABLE` sin datos parciales** (nunca un mapa a medias que parezca completo).

**Corregido (bug real encontrado por el Loop, no estaba en el backlog):**

- La primera versión del filtro CORS **rechazaba con `403` todo request con `Origin` no listado**,
  incluidos los del **propio origen**. El navegador manda `Origin` en los POST y en el **handshake
  WebSocket**, así que con la lista vacía (default de producción, SPA servido por el gateway) el SPA
  no habría podido **ni loguearse ni abrir un WS**. Lo destapó el IT del túnel WS de FEAT-0007 (verde
  antes del cambio, rojo después). Corrección: **same-origin no es CORS** — si el `Origin` coincide
  con el host del gateway (vía `Host`, o `X-Forwarded-Host`/`-Proto` detrás de un terminador TLS) el
  request pasa sin headers y sin bloqueo.

**Notas de compatibilidad:**

- El túnel WS de FEAT-0007 ahora exige token: su IT manda un JWT válido (AC-006 lo contempla como
  "regresión del túnel, ahora autenticado"). Cualquier cliente WS existente debe empezar a mandar
  `?token=` o `Authorization: Bearer`.
- `query-api` gana una dependencia de runtime con `sensor-registry` para el resumen (credenciales
  VIEWER configurables y modo de fallo `502` explícito). Los endpoints existentes no cambian.

**Fuera de alcance (declarado):** el SPA React en sí (`FEAT-0009`, incluido su hosting estático
desde el gateway), ruta del simulador por el gateway (sigue interno), historial de alertas, caché del
resumen en Redis, continuous aggregates, autenticación de los REST en el gateway, refresh token y
CORS con cookies.

## [FIX-0007] — 2026-09-14 — Resiliencia del lookup de config de sensores

`contracts/FIX-0007.md` (30/30 ✅) · auditoría `.sdd/runs/FIX-0007-20260913-153000.md` · ADR-0018 ·
suites `ingestion-service` 88 unit + 33 IT.

**Corregido — dos bugs de negocio que el backlog no había detectado:**

- **Un sensor desactivado (o con bandas nuevas) se seguía ingiriendo con la config vieja para
  siempre.** `RegistrySensorConfigPort` cacheaba `SensorInfo` en un `ConcurrentHashMap` **sin TTL
  ni invalidación**, así que el chequeo `INACTIVO` de `IngestorLecturas` nunca veía el cambio ni
  las bandas actualizadas.
- **Un token del registry vencido dejaba el servicio en fallo permanente.** El token JWT se
  cacheaba sin mirar `exp`; como `sensor-registry` **sí** valida `exp`, pasada 1 h de uptime todo
  sensor no cacheado fallaba mensaje tras mensaje hasta reiniciar el proceso.

**Agregado:**

- Timeouts explícitos de respuesta (2000 ms) y conexión (1000 ms) configurables — antes el lookup
  dependía del default de Netty.
- **Circuit breaker propio en el dominio** (`CircuitoResiliencia`: CERRADO/ABIERTO/SEMIABIERTO,
  reloj inyectado, observador de transiciones): 5 fallos consecutivos abren, 30 s abierto →
  semi-abierto, 2 éxitos cierran. Cero dependencias nuevas (Resilience4j sólo tenía el BOM en el
  `.m2`).
- **Cache de config con TTL (300 s) + last-known-good**: dentro del TTL se responde sin red;
  vencida se refresca; si el refresh falla o el circuito está abierto, se usa la copia vencida con
  WARN (config vieja antes que perder lecturas).
- Refresco del token ante `401`: invalidar + login + **un** reintento.
- Motivo de DLQ específico **`REGISTRY_UNAVAILABLE`** (antes `INFRA_ERROR` genérico,
  indistinguible de un fallo de base de datos).
- Endpoint interno **`GET /api/ingestion/resiliencia`** (estado del circuito, fallos, llamadas,
  tamaño/aciertos de cache) sin `actuator`; logs WARN al abrir e INFO al semi-abrir/cerrar.

**Infra de tests:** JDK 25 bloquea el auto-attach del agente de Mockito en este entorno; se migró
al **subclass mock maker** (`mockito-extensions/org.mockito.plugins.MockMaker`), sin flags de JVM
ni agentes.

**Fuera de alcance (declarado):** reintentos con backoff (`messaging.retry-max-attempts` queda
como config **sin uso**, deuda explícita), cache en Redis, actuator/Micrometer, invalidación
evento-driven de la cache y circuit breaker en otras integraciones.

## [FIX-0006] — 2026-09-13 — Versionado del schema de `sensor.lecturas` (payload v1)

`contracts/FIX-0006.md` (31/31 ✅) · auditoría `.sdd/runs/FIX-0006-20260913-150000.md` · ADR-0017 ·
suites `data-simulator` 19+1 · `ingestion-service` 68+28 · `query-api` 11+1 · regresión
`alerting-service` 10+2.

**Corregido:**

- **El contrato de mensajería era imposible de evolucionar**: los dos consumers de
  `sensor.lecturas` parseaban con **expresiones regulares**; un payload válido con espacios
  (`"valor" : 5.0`) se rechazaba con `PAYLOAD_INVALID`.
- El publisher no emitía `eventId` (la idempotencia dependía del timestamp) ni marca de calidad.

**Agregado:**

- **Payload v1**: `schemaVersion` (configurable), `eventId` (UUID v4 por publicación),
  `sequence` (contador por sensor), `calidad` informativa (`estado` `OK|ERROR_SENSOR`,
  `confianza`, `codigosAnomalias` con `ANOMALIA_INYECTADA` en la ventana de anomalía).
  `timestamp` se mantiene (ya es ISO-8601 con `Z`).
- Parseo en ambos consumers con **DTO + Jackson 3** tolerante a propiedades desconocidas (Jackson ya
  estaba en el classpath transitivo: cero dependencias nuevas).
- **Política de versiones tolerante hacia adelante**: legado (`0.0` implícito) y `1.x` se procesan;
  una **mayor desconocida se procesa con WARN** (un publisher más nuevo no tumba la ingesta);
  rechazo sólo por versión malformada o campos requeridos ausentes. Configurable a fail-closed con
  `ingestion.schema.tolerar-versiones-mayores=false` (→ DLQ `SCHEMA_UNSUPPORTED`).
- **`sequence` persistida** (columna `secuencia BIGINT`, `NULL` en eventos legados) con detección de
  huecos por WARN sin descartar lecturas; el reinicio del publisher se registra como INFO.
- `query-api` expone la `calidad` del evento en el WS (payload legado idéntico al anterior).
- Ventana de compatibilidad del payload plano **indefinida y medida** (INFO con contador).

**Fuera de alcance:** metadata de dispositivo (firmware/batería/RSSI, hardware inexistente),
`calidad.estado = SOSPECHOSA`, renombrar `timestamp` a `timestampUtc`, versionar `sensor.alertas`
y retirar el payload legado.

## [FEAT-0007] — 2026-09-13 — `api-gateway`: punto de entrada único, rate limiting y correlación

`contracts/FEAT-0007.md` (33/33 ✅) · auditoría `.sdd/runs/FEAT-0007-20260913-134500.md` · ADR-0016 ·
suite `api-gateway` 31 unit + 16 IT.

**Agregado:**

- Servicio nuevo **`api-gateway`** (WebFlux, hexagonal, **cero dependencias nuevas**) que enruta
  REST y WebSocket a `sensor-registry`, `query-api` y `alerting-service` por **patrón más
  específico** (el prefijo `/api/sensores` está compartido entre registry y query-api).
- **Rate limiting** token bucket por clase + IP del peer (`login` 10/60 s · `lectura` 120/60 s ·
  `default` 300/60 s · `ws` 30/60 s, configurables) con `429` + `Retry-After` +
  `X-RateLimit-*` + body `{"code","message"}`, **sin reenviar** al downstream.
- `X-Correlation-Id` propagado o generado, reflejado en la respuesta y en el log de acceso.
- Errores de dominio `404 ROUTE_NOT_FOUND`, `502 UPSTREAM_UNAVAILABLE`, `504 UPSTREAM_TIMEOUT`,
  `426 WS_UPGRADE_REQUIRED`; nunca `500` crudo ni stacktrace.
- Túnel WebSocket con path preservado; **sólo el gateway publica puerto** (`:8084`) y el debug
  directo pasa a `docker-compose.dev.yml`.

**Corregido durante el Loop (bugs reales):** el `RouterFunction` resuelve por **primer match** (hubo
que ordenar por especificidad), `exchangeToMono` libera la respuesta al completar (el body proxeado
salía vacío) y la estrategia de upgrade WS auto-suscribe el handler (el túnel se cerraba tras el
handshake).

**Brecha conocida (aceptada):** los WS de `alerting`/`query-api` no validan token; el gateway tunela
sin agregar auth. CORS también fuera de alcance.

## [FIX-0005] — 2026-09-11 — Particionamiento del consumo por `sensorId`

`contracts/FIX-0005.md` (23/23 ✅) · auditoría `.sdd/runs/FIX-0005-20260911-201500.md` · ADR-0015 ·
suite `ingestion-service` 20 unit + 7 IT.

**Corregido:**

- **Escalar `ingestion-service` perdía transiciones de severidad reales**: con una cola única, las
  instancias pasaban a ser competing consumers y el estado en memoria `ultimaSeveridad` se repartía
  entre procesos, así que alertas legítimas nunca se emitían.
- El doc de backlog pedía "orden por sensor" citando un campo `sequence` **inexistente**; el
  problema real era el estado en memoria.

**Agregado:**

- **Particionamiento en el broker**: exchange `x-consistent-hash` (`sensor.lecturas.part`)
  alimentado por un binding exchange-to-exchange (`lectura.#`) desde el topic, con 4 colas
  `queue.sensor.lecturas.p{i}` (peso `"1"`) → **afinidad sensor → partición → instancia**.
  El publisher **no** cambia.
- Un consumer por partición con `qos=1` y procesamiento secuencial (`concatMap`); particiones en
  paralelo, orden garantizado dentro de cada una.
- `N` y las particiones asignadas por instancia configurables (`INGESTION_PARTICIONES_*`).
- Fail-fast: si la topología no se puede declarar, ERROR y cero consumers (nunca degrada a consumir
  sin particionar); WARN de particiones sin consumer.
- Infra: `infra/rabbitmq/enabled_plugins` (plugin `rabbitmq_consistent_hash_exchange`) en compose e
  ITs; RUNBOOK con escalado, cambio de `total` y drenaje de la cola anterior.

## [FIX-0004] — 2026-09-11 — Rango físico y calidad del dato (`ERROR_SENSOR`)

`contracts/FIX-0004.md` (16/16 ✅) · auditoría `.sdd/runs/FIX-0004-20260911-190632.md` · ADR-0013,
ADR-0014 · suite `ingestion-service` 22 unit + 6 IT.

**Corregido:**

- Se persistían y **alertaban lecturas físicamente imposibles** (altura negativa o fuera de rango),
  contaminando el histórico y el pipeline de alertas.
- **Bug derivado corregido**: `row.get(col, Tipo.class)` con `NULL` en `query-api`.

**Agregado:** rangos físicos configurables por unidad con override por sensor
(`ingestion.rango-fisico.*`); lecturas fuera de rango o marcadas `ERROR_SENSOR` por el emisor se
persisten con `calidad = ERROR_SENSOR` y **`severidad NULL`**, sin evaluar bandas ni encolar alerta,
sin alterar la última severidad conocida; log WARN con sensor/valor/unidad; `query-api` expone
`calidad` y tolera severidad nula.

## [FIX-0003] — 2026-09-11 — Outbox + idempotencia en `ingestion-service`

`contracts/FIX-0003.md` (19/19 ✅) · ADR-0013 · suite `ingestion-service` 13 unit + 8 IT.

**Corregido:** un redelivery de RabbitMQ podía **duplicar lecturas**, y la publicación de la alerta
no era atómica con la persistencia (evento perdido si el proceso caía entre ambas).

**Agregado:** clave de idempotencia (`evt:<eventId>` si viene; si no la natural
`nat:<sensorId>:<epochMilli>`) con tabla `lectura_procesada` (`ON CONFLICT DO NOTHING`), patrón
**outbox** con publicación en **una sola transacción R2DBC**, poller reactivo con claim
`FOR UPDATE SKIP LOCKED`, orden explícito por `id`, backoff configurable, `FALLIDO + ultimo_error`
al agotar intentos y purga por retención (90 días). Garantía declarada: **at-least-once**
(el dedupe en `alerting-service` queda como contrato futuro).

## [FIX-0002] — 2026-09-10 — `alerting-service` perdía datos del evento

`contracts/FIX-0002.md` (4/4 ✅) · suite `alerting-service` 3 unit + 1 IT.

**Corregido:** `AlertasRabbitConsumer` **hardcodeaba `valorLectura` y `cruceHisteresis`** al parsear
`sensor.alertas`, así que las alertas notificadas mentían sobre el valor y el cruce de histéresis.
Ahora el parseo es fiel al payload.

## [FIX-0001] — 2026-08-12 — Código de error de dominio en validación estructural

`contracts/FIX-0001.md` (1/1 ✅).

**Corregido:** `GlobalErrorHandler` emitía el **class name** de la excepción en lugar del `code` de
dominio estable en el camino de validación estructural de request.

## [Base] — 2026-07-28 → 2026-09-09 — Servicios y funcionalidad núcleo (FEAT-0001..0013)

Los 11 work items de funcionalidad, con sus Contracts (todos RESOLVED), auditorías en `.sdd/runs/`
y decisiones en `docs/DECISIONES.md` (ADR-0001..ADR-0012):

| Work Item | Servicio / alcance | Criterios | Suite |
|---|---|---|---|
| FEAT-0001 | `sensor-registry` — alta de sensor (`POST /api/sensores`) | 23/23 ✅ | 12 unit + ITs |
| FEAT-0002 | listado keyset (`GET /api/sensores`) | 21/21 ✅ | 15+6+3 unit + 1 IT |
| FEAT-0003 | detalle (`GET /api/sensores/{id}`) | 19/19 ✅ | 5 unit + 7 IT |
| FEAT-0004 | edición de configuración (`PUT`) | 24/24 ✅ | 7 unit + 9 IT |
| FEAT-0005 | baja lógica (`DELETE`) | 22/22 ✅ | 3 unit + 8 IT |
| FEAT-0006 | auth JWT (`POST /api/auth/login`) | 21/21 ✅ | 12 unit + 8 IT |
| FEAT-0010 | `data-simulator` — lecturas sintéticas | 23/23 ✅ | 16 unit + 1 IT |
| FEAT-0011 | `ingestion-service` — consumo + severidad | 22/22 ✅ | 17 unit + 1 IT |
| FEAT-0012 | `alerting-service` — histéresis + WS | 22/22 ✅ | 7 unit + 1 IT |
| FEAT-0013 | `query-api` — histórico/última/tiempo real | 23/23 ✅ | 7 unit + 1 IT |

## Evolución de las suites

| Hito | Unit | IT | Total |
|---|---|---|---|
| Base: FEAT-0001..0013 + FIX-0001..0004 | 144 | 52 | 196 |
| FIX-0005 (particionamiento) | 164 | 59 | 223 |
| FEAT-0007 (api-gateway) | 195 | 75 | 270 |
| FIX-0006 (payload v1) | 228 | 82 | 310 |
| FIX-0007 (resiliencia del lookup) | **248** | **87** | **335** |

> Suites por módulo y comandos exactos de reproducción en [`docs/RUNBOOK.md`](RUNBOOK.md) §2 y
> [`docs/ESTADO-SDD.md`](ESTADO-SDD.md). Los `*IT` requieren Docker (Testcontainers) y **no** corren
> con `mvn test` solo: se ejecutan con `mvn -o test -Dtest='*IT'`.
