# WR-Sensor — Registro SDD-GL de Work Items

Registro consolidado del ciclo SDD-GL (Gate/Loop) al **2026-09-09**. Fuente: headers
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

> FEAT-0001: completado en sesiones previas (bitácora). FEAT-0002 quedó
> interrumpido en Main Flow ⏳ y se reanudó/cerró el 2026-09-09.

## Suites de tests verdes por módulo

| Módulo | Clases unit/assert | ITs | Detalle |
|---|---|---|---|
| `sensor-registry` | 89 | 34 (6 ITs) | BR/AC/AF por contract + e2e FEAT-0001..0006 |
| `data-simulator` | 19 | 1 | `ValorSinteticoTest` 3 · `LecturaJsonTest` 4 · `SimuladorServiceTest` 9 · `FIX0006SimuladorTest` 3 |
| `ingestion-service` | 68 | 28 | `RangoFisicoEvaluadorTest` 5 · `IngestorLecturasTest` 17 · `SeveridadEvaluadorTest` 3 · `ParticionesPlanTest` 7 · `FIX0005ConsumerTest` 9 · `FIX0005DocsTest` 4 · `FIX0006EsquemaTest` 6 · `FIX0006ParseoTest` 8 · `FIX0006SecuenciaTest` 5 · `FIX0006DocsTest` 4 · ITs (FEAT-0011 + FIX-0003 + FIX-0004 + FIX-0005 + FIX-0006) |
| `alerting-service` | 10 | 2 | `GestorAlertasTest` 6 · `EventoParseTest` 1 · `FIX0002AcTest` 3 |
| `query-api` | 11 | 1 | `CursorLecturasTest` 2 · `QueryServiceTest` 5 · `FIX0006RealtimeTest` 4 |
| `api-gateway` | 31 | 16 | `TablaRutasTest` 9 · `RateLimiterTest` 7 · `FiltroRateLimitTest` 6 · `ConfiguracionGatewayTest` 5 · `CorrelacionTest` 4 · IT `FEAT0007MainFlowIT` 16 |

**Total: 228 unit/assert + 82 ITs = 310 verdes** (JUnit 5, AssertJ, StepVerifier,
WebTestClient, Testcontainers — Maven offline). Los `*IT` se corren aparte:
`mvn -o test -Dtest='*IT'`.

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

## Pendientes (roadmap v1)

Frontend React (dashboards + mapa) → Redis para `/actual` → continuous aggregates de
TimescaleDB (§9.2 raw/aggregate) → manifiestos K8s (sólo documentación).
Backlog de fixes en espera: `docs/FIX-0005-gateway-rate-limiting.md`,
`docs/FIX-0007-circuit-breaker.md` y `docs/FIX-0002-schema-versionado-lecturas.md`
(renumerar antes de promocionar), más los dos ítems que dejó abiertos FIX-0005:
persistir la última severidad y el mismo problema de afinidad al escalar
`alerting-service`. Servicios cerrados a la fecha: 5 (sensor-registry, data-simulator,
ingestion, alerting, query-api).




