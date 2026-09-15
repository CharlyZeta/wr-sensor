# Estado SDD-GL — WR-Sensor

> Dashboard generado con el comando `/sdd-status` del frame SDD-GL, **persistido para análisis
> externo**. Fuente de verdad: los headers y Completion Maps de `contracts/*.md`.
> Última actualización: **2026-09-14** · changelog del proyecto: [`docs/CHANGELOG.md`](CHANGELOG.md) ·
> registro detallado: [`docs/REGISTRO-SDD.md`](REGISTRO-SDD.md).

```
📊 SDD-GL Status

🔴 DRAFT (Gate — awaiting review)
   └── (ninguno)

🟡 APPROVED (Loop in progress)
   └── (ninguno)

🟢 RESOLVED
   └── FEAT-0001: Sensor Registry [23/23 ✅]
   └── FEAT-0002: Listado de sensores (GET /api/sensores) con paginación keyset [21/21 ✅]
   └── FEAT-0003: Detalle de sensor — GET /api/sensores/{id} [19/19 ✅]
   └── FEAT-0004: Edición de sensor — PUT /api/sensores/{id} [24/24 ✅]
   └── FEAT-0005: Baja lógica de sensor — DELETE /api/sensores/{id} [22/22 ✅]
   └── FEAT-0006: Autenticación con JWT — POST /api/auth/login [21/21 ✅]
   └── FEAT-0007: api-gateway — punto de entrada único, rate limiting y correlación [33/33 ✅]
   └── FEAT-0008: habilitadores del frontend (CORS, auth de WebSocket, resumen de sensores) [31/31 ✅]
   └── FEAT-0010: data-simulator — generador de lecturas sintéticas [23/23 ✅]
   └── FEAT-0011: ingestion-service — consumo y severidad de lecturas [22/22 ✅]
   └── FEAT-0012: alerting-service — histéresis y notificación de alertas [22/22 ✅]
   └── FEAT-0013: query-api — histórico, última lectura y tiempo real [23/23 ✅]
   └── FIX-0001: GlobalErrorHandler emite class name en vez de domain code estable [1/1 ✅]
   └── FIX-0002: alerting-service pierde datos del evento (valorLectura/cruceHisteresis) [4/4 ✅]
   └── FIX-0003: outbox + idempotencia en ingestion-service [19/19 ✅]
   └── FIX-0004: rango físico y calidad del dato (ERROR_SENSOR) [16/16 ✅]
   └── FIX-0005: particionamiento del consumo por sensorId [23/23 ✅]
   └── FIX-0006: versionado del schema de sensor.lecturas (payload v1) [31/31 ✅]
   └── FIX-0007: resiliencia del lookup de config (timeouts, breaker, cache TTL) [30/30 ✅]

Total: 19 contracts | 0 in Gate | 0 in Loop | 19 resolved
Criterios de completitud: 408/408 ✅ (253 en FEATs + 124 en FIXes + 31 de FEAT-0008)
                         pendientes: ninguno
```

> **HO-Gate pendiente (humano):** FEAT-0008 tiene el Loop completo y el mapa en 31/31, pero la
> validación final del work item es del humano (protocolo SDD-GL). El hallazgo del Loop que amerita
> su revisión está en ADR-0019 (el primer CORS rechazaba el mismo origen y rompía login + WS del SPA
> servido por el gateway) y en `docs/REGISTRO-SDD.md` §Fixes ítem 11.

## Detalle por contract

| ID | Servicio(s) / alcance | Criterios | ADR / evidencia |
|---|---|---|---|
| FEAT-0001 | `sensor-registry` — alta de sensor | 23/23 ✅ | auditoría en `.sdd/runs/` |
| FEAT-0002 | `sensor-registry` — listado keyset | 21/21 ✅ | ADR-0001/0003 · `.sdd/runs/` |
| FEAT-0003 | `sensor-registry` — detalle | 19/19 ✅ | `.sdd/runs/` |
| FEAT-0004 | `sensor-registry` — edición de configuración | 24/24 ✅ | `.sdd/runs/` |
| FEAT-0005 | `sensor-registry` — baja lógica | 22/22 ✅ | ADR-0006 · `.sdd/runs/` |
| FEAT-0006 | `sensor-registry` — auth JWT | 21/21 ✅ | ADR-0007/0008 · `.sdd/runs/` |
| FEAT-0007 | `api-gateway` — entrada única + rate limiting | 33/33 ✅ | ADR-0016 · `.sdd/runs/FEAT-0007-20260913-134500.md` |
| FEAT-0010 | `data-simulator` — lecturas sintéticas | 23/23 ✅ | ADR-0009 · `.sdd/runs/` |
| FEAT-0011 | `ingestion-service` — consumo + severidad | 22/22 ✅ | ADR-0010 · `.sdd/runs/` |
| FEAT-0012 | `alerting-service` — histéresis + WS | 22/22 ✅ | ADR-0011 · `.sdd/runs/` |
| FEAT-0013 | `query-api` — histórico/última/tiempo real | 23/23 ✅ | `.sdd/runs/` |
| FIX-0001 | `sensor-registry` — code de dominio en bind | 1/1 ✅ | `.sdd/runs/` |
| FIX-0002 | `alerting-service` — parser de `sensor.alertas` | 4/4 ✅ | `.sdd/runs/FIX-0002-20260910-164923.md` |
| FIX-0003 | `ingestion-service` — outbox + idempotencia | 19/19 ✅ | ADR-0013 · `.sdd/runs/FIX-0003-*.md` |
| FIX-0004 | `ingestion-service` — rango físico + calidad | 16/16 ✅ | ADR-0013/0014 · `.sdd/runs/FIX-0004-20260911-190632.md` |
| FIX-0005 | `ingestion-service` — particionamiento por `sensorId` | 23/23 ✅ | ADR-0015 · `.sdd/runs/FIX-0005-20260911-201500.md` |
| FIX-0006 | `data-simulator` + `ingestion-service` + `query-api` — payload v1 | 31/31 ✅ | ADR-0017 · `.sdd/runs/FIX-0006-20260913-150000.md` |
| FIX-0007 | `ingestion-service` — resiliencia del lookup de config | 30/30 ✅ | ADR-0018 · `.sdd/runs/FIX-0007-20260913-153000.md` |
| FEAT-0008 | `api-gateway` (CORS + auth WS + ruteo) y `query-api` (resumen del mapa) | 31/31 ✅ | ADR-0019 · `.sdd/runs/FEAT-0008-*.md` |

> Los conteos exactos de tests son **por módulo** (tabla siguiente): un mismo archivo de test puede
> cubrir criterios de más de un contract, así que atribuir tests a un contract individual sería
> impreciso. La trazabilidad criterio → test sí es exacta: el `Completion Map` de cada contract
> indica el `test-id` de cada criterio.

## Suites por módulo (verificado 2026-09-14)

| Módulo | Unit | IT | Total |
|---|---|---|---|
| `sensor-registry` | 89 | 34 | 123 |
| `data-simulator` | 19 | 1 | 20 |
| `ingestion-service` | 88 | 33 | 121 |
| `alerting-service` | 10 | 2 | 12 |
| `query-api` | 33 | 8 | 41 |
| `api-gateway` | 67 | 29 | 96 |
| **Total** | **306** | **107** | **413** |

Cómo reproducirlo:

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25.0.3_9"
$mvn = "C:\Users\Gerardo\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd"

# Unit por módulo (offline, sin Docker)
& $mvn -o -f services\<modulo>\pom.xml test

# ITs (requieren Docker Desktop: Testcontainers)
& $mvn -o -f services\ingestion-service\pom.xml test "-Dtest=*IT"

# Stack completo
docker compose up -d --build          # sólo api-gateway publica :8084
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d   # debug con puertos directos
```

## Cómo leer este tablero (para revisión externa)

- **Cada contract** vive en `contracts/<ID>.md` con `Intent`, `Use Case`/`Reproduction Steps`,
  `Business Rules`, `Acceptance Criteria`, `Ambiguity Log` (todas las decisiones resueltas y
  trazadas al humano) y un `Completion Map` con una línea por criterio y su test id.
- **Cada criterio** tiene un test asociado con el ID del criterio en su nombre/`@DisplayName`
  (ej. `AC-002`, `BR-006`, `AF-03`), de modo que la trazabilidad spec → test es mecánica.
- **Cada Loop** dejó una auditoría Glass Box en `.sdd/runs/<ID>-<timestamp>.md` con los archivos
  leídos, el diff, el comando de test, la salida del runner y la justificación técnica (incluidos
  los bugs reales encontrados durante la implementación).
- **Decisiones de arquitectura**: `docs/DECISIONES.md` (ADR-0001..ADR-0019).
- **Especificación de origen**: `docs/water-monitoring-spec.md` y `stack.md`.
- **Proceso**: `CLAUDE.md` / `AGENTS.md` (orquestador SDD-GL), `protocol/{contract,gate,loop}.md`.

### Estado del backlog

- **En curso (Gate)**: ninguno. `FEAT-0008` cerró su Loop (31/31) y espera la validación humana del
  resultado; `FEAT-0009` (SPA React Fase A) es el siguiente work item a abrir con `/sdd-feature`.
- **Contracts de fix pendientes: ninguno** (`FIX-0001..FIX-0007` RESOLVED, 7/7).
- **Documentos de backlog promovidos**: `docs/FIX-0002-schema-versionado-lecturas.md` → `FIX-0006`,
  `docs/FIX-0003..0004` → `FIX-0003`/`FIX-0004`, `docs/FIX-0006-particionamiento-consumers.md` →
  `FIX-0005`, `docs/FIX-0005-gateway-rate-limiting.md` → `FEAT-0007`, `docs/FIX-0007-circuit-breaker.md`
  → `FIX-0007` (ver mapeo en `docs/ESTADO-PROYECTO.md` §3d).
- **Follow-ups técnicos abiertos (sin contract todavía)**:
  1. Persistir `ultimaSeveridad` de `ingestion-service` (hoy en memoria por instancia).
  2. Afinidad de `alerting-service` si se escala (histéresis en memoria) — ADR-0015.
  3. Reintentos con backoff en el consumo (la config `messaging.retry-max-attempts` está **sin uso**)
     — ADR-0018.
  4. Historial de alertas consultable (hoy sólo el WS en vivo) — FEAT-0008 lo deja fuera de alcance.
  5. Decisión de tiles del mapa (OSM remoto vs mapa esquemático offline) — se resuelve en `FEAT-0009`.
  6. Caché del resumen (`GET /api/sensores/resumen`) en Redis — fuera de alcance de FEAT-0008.
  7. Autenticación de los REST **en el gateway** (hoy valida sólo el upgrade WS) — FEAT-0008 acota
     esa brecha a los WS.
- **Roadmap v1 restante**: **frontend React** (`FEAT-0009` y siguientes: Fase A mapa+dashboards,
  Fase B CRUD/simulador, Fase C agregados + historial de alertas), Redis para `/actual`,
  continuous aggregates de TimescaleDB (§9.2) y manifiestos K8s (documentación).

### Notas de entorno (relevantes para reproducir)

- Los **ITs requieren Docker Desktop**; `mvn test` a secas **no** los ejecuta (surefire excluye `*IT`).
- JDK 25 bloquea el auto-attach del agente de Mockito: el proyecto usa el **subclass mock maker**
  (`services/ingestion-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`).
- Los tests corren **offline** (`mvn -o`): todas las dependencias están en el `~/.m2` local. No se
  agregaron dependencias nuevas en FEAT-0007, FEAT-0008, FIX-0006 ni FIX-0007.
- Los ITs de `api-gateway` **no requieren Docker**: levantan downstreams stub en proceso (servidor
  HTTP del JDK + Reactor Netty para el WS) y cuentan exactamente qué recibe cada uno. El IT de
  `query-api` sí usa Testcontainers (Postgres real para el `DISTINCT ON`).
