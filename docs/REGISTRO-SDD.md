# WR-Sensor — Registro SDD-GL de Work Items

Registro consolidado del ciclo SDD-GL (Gate/Loop) al **2026-09-14**. Fuente: headers
de `contracts/*.md` y audits `.sdd/runs/`.

## Frame

- SDD-GL **v0.3.0** instalado: `protocol/` (contract/gate/loop), `.claude/agents`
  (`model: sonnet`), `.claude/commands` (`/sdd-feature|fix|status`), `AGENTS.md`,
  `.agents/skills/*`, `presets/`, `mcp/sdd-gl-mcp-spec.md`, `CHANGELOG.md`.
- Auditoría Glass Box por Loop en `.sdd/runs/FEAT-XXXX-<timestamp>.md`.

## Tabla de Work Items

| ID | Título | HO-Gate | RESOLVED | Completion Map | Cobertura principal |
|---|---|---|---|---|---|
| FEAT-0001 | Alta de sensor `POST /api/sensores` | (previo) | 2026-07-28 | ✅ | BR-001..009 (12) · AC (9) · AF-04 (5) · IT e2e |
| FIX-0001 | Handler emite domain code en bind | 2026-08-12 | 2026-08-12 | ✅ | `FIX0001Ac001Test` (12) |
| FEAT-0002 | Listado keyset `GET /api/sensores` | 2026-08-13 | **2026-09-09** | ✅ 21/21 | Paginación (15) · Auth (6) · AC handler (3) · IT |
| FEAT-0003 | Detalle `GET /api/sensores/{id}` | 2026-09-09 | 2026-09-09 | ✅ 19/19 | Service (3) · AC (2) · IT e2e (7) |
| FEAT-0004 | Edición `PUT /api/sensores/{id}` | 2026-09-09 | 2026-09-09 | ✅ 24/24 | Service (7) · IT e2e (9) |
| FEAT-0005 | Baja lógica `DELETE /api/sensores/{id}` | 2026-09-09 | 2026-09-09 | ✅ 22/22 | Service (3) · IT e2e (8) |
| FEAT-0006 | Auth JWT `POST /api/auth/login` | 2026-09-09 | 2026-09-09 | ✅ 21/21 | JWT (7) · Login (4) · BCrypt (1) · IT e2e (8) |
| FEAT-0010 | `data-simulator` | 2026-09-09 | 2026-09-09 | ✅ 23/23 | Valor (3) · JSON (1) · Service (9) · IT (1) |
| FEAT-0011 | `ingestion-service` | 2026-09-09 | 2026-09-09 | ✅ 22/22 | Evaluador (3) · Ingestor (7) · IT TimescaleDB (1) |
| FEAT-0012 | `alerting-service` | 2026-09-09 | 2026-09-09 | ✅ 22/22 | Gestor (6) · Parse (1) · IT WS (1) |
| FEAT-0013 | `query-api` | 2026-09-09 | 2026-09-09 | ✅ 23/23 | Cursor (2) · Service (5) · IT e2e (1) |
| FIX-0002 | Parser de `sensor.alertas` perdía `valorLectura`/`cruceHisteresis` | 2026-09-09 | 2026-09-10 | ✅ 4/4 | AC (3) · IT reproducción (1) |
| FIX-0003 | Outbox + idempotencia en `ingestion-service` | 2026-09-10 | 2026-09-11 | ✅ 19/19 | Unit (10+3) · IT e2e (7) |
| FIX-0004 | Rango físico y calidad del dato (`ERROR_SENSOR`) | 2026-09-11 | 2026-09-11 | ✅ 16/16 | Unit (5+17) · IT e2e (6) |
| FIX-0005 | Particionamiento del consumo por `sensorId` | 2026-09-11 | 2026-09-11 | ✅ 23/23 | Unit (20) · IT e2e (7) |
| FEAT-0007 | `api-gateway`: punto de entrada único, rate limiting y correlación | 2026-09-13 | 2026-09-13 | ✅ 33/33 | Unit (31) · IT e2e (16) |
| FIX-0006 | Versionado del schema de `sensor.lecturas` (payload v1) | 2026-09-13 | 2026-09-13 | ✅ 31/31 | Unit (27) · IT e2e (7) |
| FIX-0007 | Resiliencia del lookup de config (breaker + cache TTL) | 2026-09-14 | 2026-09-14 | ✅ 30/30 | Unit (20) · IT e2e (5) |
| FEAT-0008 | Habilitadores del frontend: CORS, WS autenticado y resumen | 2026-09-14 | 2026-09-14 | ✅ 31/31 | Gateway unit (36 + docs 11) · query-api unit (22) · IT (20) |
| FIX-0008 | Seguridad del punto de entrada: secreto WS, headers/CSP y hosting seguro | 2026-09-15 | 2026-09-15 | ✅ 24/24 | Unit (16) · IT (8) · gateway 84+37 |`n| FEAT-0009 | SPA núcleo: sesión, shell, mapa y hosting desde el gateway | 2026-09-15 | 2026-09-15 | ✅ 32/32 | `web/` 22 (Vitest+RTL+MSW) · gateway +10 (hosting + docs) |
| FEAT-0014 | Detalle en vivo: WS de lecturas + serie de 24 h | 2026-09-15 | 2026-09-15 | ✅ 29/29 | `web/` +15 (Vitest+RTL+MSW) · gateway +5 (docs) |
| FEAT-0015 | Alertas en vivo: feed + refresco del mapa | *(Gate)* | — | 🔴 0/29 (Gate EXPRESS) | Pendiente de HO-Gate: parte 3 de 4 del frontend |
| FEAT-0016 | Administración y demo: CRUD (ADMIN) + panel del simulador | *(Gate)* | — | 🔴 0/31 (Gate STRICT) | Pendiente de HO-Gate: parte 4 de 4 del frontend |

> FEAT-0001: completado en sesiones previas (bitácora). FEAT-0002 quedó
> interrumpido en Main Flow ⏳ y se reanudó/cerró el 2026-09-09.
> FEAT-0008 es el único contract con `Gate-Mode: STRICT` cerrado hasta ahora (los demás,
> EXPRESS): el Gate humano aprobó alcance, decisiones y Ambiguity Log antes del Loop.
> **FEAT-0009/0014/0015/0016** son la **serie del frontend**, abierta el 2026-09-14 y dividida por
> capacidad funcional (aprobado por el humano): núcleo+mapa, detalle en vivo, alertas y
> administración/demo. Están en **Gate** (DRAFT) esperando el HO-Gate que habilita su Loop.

## Suites de tests verdes por módulo

| Módulo | Clases unit/assert | ITs | Detalle |
|---|---|---|---|
| `sensor-registry` | 89 | 34 (6 ITs) | BR/AC/AF por contract + e2e FEAT-0001..0006 |
| `data-simulator` | 19 | 1 | `ValorSinteticoTest` 3 · `LecturaJsonTest` 4 · `SimuladorServiceTest` 9 · `FIX0006SimuladorTest` 3 |
| `ingestion-service` | 88 | 33 | `RangoFisicoEvaluadorTest` 5 · `IngestorLecturasTest` 17 · `SeveridadEvaluadorTest` 3 · `ParticionesPlanTest` 7 · `FIX0005ConsumerTest` 9 · `FIX0005DocsTest` 4 · `FIX0006EsquemaTest` 6 · `FIX0006ParseoTest` 8 · `FIX0006SecuenciaTest` 5 · `FIX0006DocsTest` 4 · `CircuitoResilienciaTest` 5 · `FIX0007ResilienciaTest` 10 · `FIX0007ObservabilidadTest` 2 · `FIX0007DocsTest` 3 · ITs (FEAT-0011 + FIX-0003 + FIX-0004 + FIX-0005 + FIX-0006 + FIX-0007) |
| `alerting-service` | 10 | 2 | `GestorAlertasTest` 6 · `EventoParseTest` 1 · `FIX0002AcTest` 3 |
| `query-api` | 33 | 8 | `CursorLecturasTest` 2 · `QueryServiceTest` 5 · `FIX0006RealtimeTest` 4 · `FEAT0008ResumenTest` 7 · `FEAT0008RegistryAdapterTest` 15 · ITs (`FEAT0013MainFlowIT` 1 + `FEAT0008ResumenIT` 7) |
| `api-gateway` | 94 | 42 | `TablaRutasTest` 9 · `RateLimiterTest` 7 · `FiltroRateLimitTest` 6 · `ConfiguracionGatewayTest` 5 · `CorrelacionTest` 4 · `VerificadorJwtTest` 7 · `AutenticadorWsTest` 8 · `FiltroCorsTest` 11 · `FEAT0008DocsTest` 11 · ITs (`FEAT0007MainFlowIT` 16 + `FEAT0008MainFlowIT` 13) |

**Total: 333 unit/assert + 120 ITs = 453 verdes** (backend) **+ 37 tests del SPA** (`web/`, Vitest+RTL+MSW) (JUnit 5, AssertJ, StepVerifier,
WebTestClient, Testcontainers — Maven offline). Los `*IT` se corren aparte:
`mvn -o test -Dtest='*IT'` (los de `api-gateway` no necesitan Docker: usan downstreams
stub en proceso).

## Fixes de infraestructura (bug reales, documentados en audits)

1. **Keyset tie-break** (FEAT-0002): naive-UTC para timestamps → `FEAT0002MainFlowIT` verde.
2. **Guards/contexto Reactor** (FEAT-0005, transversal): rol resuelto una vez como
   atributo del exchange → fin de los 401/500 intermitentes en DELETE.
3. **Jackson estricto** (FEAT-0004): `fail-on-unknown-properties` + handlers de
   `ServerWebInputException`/`HttpMessageNotReadable` → `SENSOR_INVALID_REQUEST`.
4. **NPE de null en Mono** (FEAT-0006 RolFilter): ausencia de rol representada con
   `Mono.empty()`, nunca `Mono.just(null)`.
5. **Parser incompleto de `sensor.alertas`** (FIX-0002): `valorLectura` y
   `cruceHisteresis` hardcodeados → parseo fiel del payload.
6. **Duplicados y publicación no atómica en ingestion** (FIX-0003): dedupe por clave
   (eventId o natural), transacción única lectura+dedupe+outbox y poller reactivo
   (claim `FOR UPDATE SKIP LOCKED`, orden explícito por `id`, backoff, FALLIDO con
   evidencia, purga por retención).
7. **Lecturas físicamente imposibles** (FIX-0004): rango físico por unidad con override
   por sensor, `calidad` (`OK`/`ERROR_SENSOR`) y `severidad` nullable; las lecturas
   erróneas se persisten pero no evalúan severidad ni alertan. Bug derivado corregido:
   `row.get(col, Tipo.class)` con NULL en query-api.
8. **Pérdida de alertas al escalar ingestion** (FIX-0005): con una cola única, escalar
   convertía a las instancias en competing consumers y `ultimaSeveridad` (memoria por
   instancia) se repartía entre procesos → transiciones de severidad reales que nunca se
   emitían. Corregido con **particionamiento por `sensorId`** (exchange `x-consistent-hash`
   + binding e2e, afinidad sensor → partición → instancia, `N` configurable, un consumer por
   partición con `qos=1` y orden intra-partición) y fail-fast si la topología no se puede
   declarar. Bug de test corregido: `queuePurge` sobre una cola inexistente cierra el canal
   AMQP; y la management API de RabbitMQ **omite los campos en cero** (0 ≠ ausente).

9. **Contrato de `sensor.lecturas` sin versión y parseado por regex** (FIX-0006): los dos
   consumers leían el evento con `Pattern.compile`, así que el versionado del schema era
   imposible y hasta un payload válido con espacios (`"valor" : 5.0`) se rechazaba. Se publicó el
   **payload v1** (`schemaVersion`, `eventId`, `sequence`, `calidad` informativa), se migró el
   parseo a **DTO + Jackson 3** (ya en el classpath, sin dependencias nuevas), se adoptó
   **tolerancia hacia adelante** (versión mayor desconocida = se procesa con WARN; rechazo sólo
   por versión malformada o campos faltantes), se persiste la `secuencia` y se detectan huecos con
   WARN. Bug de diseño corregido respecto del doc de backlog: metía metadata de dispositivo
   inexistente y `SOSPECHOSA` sin semántica, y hacía *fail-closed* ante versiones desconocidas
   (un publisher nuevo habría tumbado la ingesta).

10. **Lookup de config sin resiliencia y con cache que nunca expiraba** (FIX-0007): el adapter de
    `sensor-registry` no tenía timeouts, no había circuit breaker y la cache era un
    `ConcurrentHashMap` sin TTL. Consecuencia real: un sensor desactivado (o con bandas nuevas)
    seguía ingiriéndose con la config vieja para siempre, y el token JWT cacheado nunca se
    refrescaba (al expirar, 1 h, todo sensor no cacheado fallaba de forma permanente). Se agregó un
    **circuit breaker propio en el dominio** (cero dependencias), **cache con TTL +
    last-known-good**, timeouts de respuesta/conexión, refresco del token ante `401`, motivo de DLQ
    `REGISTRY_UNAVAILABLE` y endpoint interno `GET /api/ingestion/resiliencia` (sin actuator).

11. **CORS que rompía el mismo origen, detectado por el IT de FEAT-0007** (FEAT-0008): la primera
    implementación del filtro CORS rechazaba con `403` cualquier request con `Origin` no incluido
    en `gateway.cors.origenes`, incluidos los del propio gateway. El navegador manda `Origin` en los
    POST y en el **handshake WebSocket**, así que con la lista vacía (default de producción, SPA
    servido por el gateway) el SPA no habría podido ni loguearse ni abrir un WS. Lo destapó el IT
    del túnel WS de FEAT-0007, que pasaba antes del cambio y falló después. Corrección:
    **same-origin no es CORS** — si el `Origin` coincide con el host del gateway (`Host`, o
    `X-Forwarded-Host`/`-Proto` detrás de un terminador TLS) el request pasa sin headers CORS y sin
    bloqueo; el `403` queda para orígenes cruzados ajenos.

12. **Regresión asumida y documentada** (FEAT-0008): el túnel WS de `api-gateway` ahora exige token,
    así que `FEAT0007MainFlowIT` manda un JWT válido (`?token=`) en sus conexiones. AC-006 de
    FEAT-0008 lo contempla explícitamente ("regresión del túnel de FEAT-0007, ahora autenticado").

## Pendientes (roadmap v1)

**En Gate (4 contracts, serie del frontend)** — orden recomendado de ejecución:
`FEAT-0009` (núcleo: sesión + shell + mapa + hosting en el gateway, 32 criterios) → `FEAT-0014`
(detalle en vivo: WS de lecturas + serie de 24 h, 29) y `FEAT-0015` (alertas en vivo: feed + refresco
del mapa, 29) — independientes entre sí → `FEAT-0016` (administración y demo: CRUD + panel del
simulador, 31). Los cuatro son **sólo frontend**: no cambian el backend (el contrato de los servicios
cerró en FEAT-0008).

**Después (Fase C, sin contract)**: agregados para rangos largos (continuous aggregates §9.2), export
CSV, comparación de sensores e historial de alertas consultable (requiere backend), Redis para
`/actual` y caché del resumen, manifiestos K8s (documentación). Gestión de usuarios/roles desde el SPA
tampoco tiene backend hoy.

Backlog de fixes sin contract (en `docs/FIX-*.md`) y follow-ups registrados por FEAT-0008:
persistir `ultimaSeveridad`, afinidad de `alerting-service` al escalar, reintentos/backoff
(`messaging.retry-max-attempts` declarado sin uso) y severidad por lectura en el payload del WS de
`query-api` (si la UX de `FEAT-0014` lo exigiera). Servicios cerrados a la fecha: 6
(sensor-registry, data-simulator, ingestion, alerting, query-api, api-gateway).




