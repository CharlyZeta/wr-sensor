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

> FEAT-0001: completado en sesiones previas (bitácora). FEAT-0002 quedó
> interrumpido en Main Flow ⏳ y se reanudó/cerró el 2026-09-09.

## Suites de tests verdes por módulo

| Módulo | Clases unit/assert | ITs | Detalle |
|---|---|---|---|
| `sensor-registry` | 89 | 34 (6 ITs) | BR/AC/AF por contract + e2e FEAT-0001..0006 |
| `data-simulator` | 13 | 1 | `ValorSinteticoTest` 3 · `LecturaJsonTest` 1 · `SimuladorServiceTest` 9 |
| `ingestion-service` | 10 | 1 | `SeveridadEvaluadorTest` 3 · `IngestorLecturasTest` 7 |
| `alerting-service` | 10 | 2 | `GestorAlertasTest` 6 · `EventoParseTest` 1 · `FIX0002AcTest` 3 |
| `query-api` | 7 | 1 | `CursorLecturasTest` 2 · `QueryServiceTest` 5 |

**Total: 129 unit/assert + 39 ITs = 168 verdes** (JUnit 5, AssertJ, StepVerifier,
WebTestClient, Testcontainers — Maven offline).

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

## Pendientes (roadmap v1)

`FEAT-0013` query-api (histórico §9.2/keyset, última lectura Redis, WS por sensor) →
Frontend React → `docker-compose.yml` + multi-módulo Maven → datos semilla/dashboard.
Servicios cerrados a la fecha: 4 (sensor-registry, data-simulator, ingestion,
alerting).


