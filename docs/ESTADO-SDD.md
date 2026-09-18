# Estado SDD-GL — WR-Sensor

> Dashboard generado con el comando `/sdd-status` del frame SDD-GL, **persistido para análisis
> externo**. Fuente de verdad: los headers y Completion Maps de `contracts/*.md`.
> Última actualización: **2026-09-14** · changelog del proyecto: [`docs/CHANGELOG.md`](CHANGELOG.md) ·
> registro detallado: [`docs/REGISTRO-SDD.md`](REGISTRO-SDD.md).

```
📊 SDD-GL Status

🔴 DRAFT (Gate — awaiting review)



   └── FEAT-0016: administración y demo — CRUD (ADMIN) y panel del simulador [0/31]

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
   └── FEAT-0009: SPA núcleo — sesión, shell, mapa y hosting desde el gateway [32/32 ✅]
   └── FEAT-0014: detalle en vivo — lecturas por WebSocket y serie de 24 h [29/29 ✅]
   └── FEAT-0015: alertas en vivo — feed y refresco del mapa [29/29 ✅]
   └── FIX-0008: seguridad del punto de entrada (secreto WS, headers/CSP, hosting seguro) [24/24 ✅]
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

Total: 25 contracts | 1 in Gate (serie del frontend) | 0 in Loop | 23 resolved
Criterios de completitud: 522/522 ✅ en los 23 resueltos (343 en FEATs + 148 en FIXes + 31 de FEAT-0008)
                         + 31 criterios en Gate: FEAT-0016
```

> **HO-Gate pendiente (humano):**
> 1. **Cierre del resultado de FEAT-0008** (Loop completo, 31/31): el hallazgo que amerita revisión está
>    en ADR-0019 (el primer CORS rechazaba el mismo origen y rompía login + WS del SPA servido por el
>    gateway) y en `docs/REGISTRO-SDD.md` §Fixes ítem 11.
> 2. **Revisión del resultado de la serie del frontend**: `FEAT-0009` (32/32) y `FIX-0008` (24/24)
>    están cerrados por orden de ejecución del humano (2026-09-15) y esperan su validación; el
>    hallazgo que amerita revisión es el **S1** de la revisión de seguridad (el gateway aceptaba
>    tokens forjables con el secreto de desarrollo) — ADR-0020.
> 3. **Aprobación del Gate de la última parte** (`FEAT-0016` administración y demo, en DRAFT con
>    recomendaciones marcadas "sujeto a HO-Gate"): pasar `Status: APPROVED` + `Mode: LOOP`. Es
>    independiente y depende sólo de `FEAT-0009` (cerrado).

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
| FIX-0008 | `api-gateway` — secreto del WS, headers/CSP y hosting seguro del SPA | 24/24 ✅ | ADR-0020 · `.sdd/runs/FIX-0008-*.md` |
| FEAT-0009 | **SPA (`web/`)** — sesión, shell, mapa y hosting desde el gateway | 32/32 ✅ | ADR-0021 · `web/` 22 tests + `FEAT0009HostingIT` 5 + `FEAT0009DocsTest` 5 |
| FEAT-0014 | **SPA (`web/`)** — detalle en vivo (WS lecturas + serie 24 h) | 29/29 ✅ | ADR-0021 · `web/` 15 tests + `FEAT0014DocsTest` 5 |
| FEAT-0015 | **SPA (`web/`)** — alertas en vivo (feed + refresco del mapa) | 29/29 ✅ | ADR-0021 · `web/` 14 tests + `FEAT0015DocsTest` 5 |
| FEAT-0016 | **SPA (`web/`)** — administración y demo (CRUD + simulador) | 0/31 🔴 Gate | contract `contracts/FEAT-0016.md` (parte 4 de 4) |

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
| `api-gateway` | 99 | 42 | 141 |
| **Total** | **338** | **120** | **458** |
| `web/` (SPA) | 51 | — | 51 |

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

- **En Gate (esperando HO-Gate)**: la **última parte de la serie del frontend** (1–3 ya cerradas).
  División por capacidad funcional aprobada por el humano 2026-09-14:

  | Parte | Contract | Alcance | Criterios | Estado |
  |---|---|---|---|---|
  | 1 | `FEAT-0009` | SPA núcleo: sesión, shell, cliente API, **mapa** (`GET /api/sensores/resumen`) y **hosting desde el gateway** | 32 | ✅ RESOLVED (2026-09-15) |
  | 2 | `FEAT-0014` | detalle en vivo: `WS /ws/sensores/{id}?token=` + serie de 24 h (histórico keyset) | 29 | ✅ RESOLVED (2026-09-15) |
  | 3 | `FEAT-0015` | alertas en vivo: `WS /ws/alertas?token=`, feed, contador y refresco del mapa con debounce | 29 | ✅ RESOLVED (2026-09-15) |
  | 4 | `FEAT-0016` | administración (CRUD ADMIN, errores por `code`) y panel de demo del simulador (Fase B) | 31 | 🔴 Gate |

  `FEAT-0016` depende sólo de `FEAT-0009` (cerrada). Queda pendiente la Fase C (agregados, export, historial de alertas), sin contract.
- **Contracts de fix pendientes: ninguno** (`FIX-0001..FIX-0008` RESOLVED, 8/8).
- **Documentos de backlog promovidos**: `docs/FIX-0002-schema-versionado-lecturas.md` → `FIX-0006`,
  `docs/FIX-0003..0004` → `FIX-0003`/`FIX-0004`, `docs/FIX-0006-particionamiento-consumers.md` →
  `FIX-0005`, `docs/FIX-0005-gateway-rate-limiting.md` → `FEAT-0007`, `docs/FIX-0007-circuit-breaker.md`
  → `FIX-0007` (ver mapeo en `docs/ESTADO-PROYECTO.md` §3d).
- **Fuera de alcance de la serie del frontend (registrado, sin contract)**: Fase C = agregados
  (continuous aggregates §9.2), export CSV, comparación de sensores e historial de alertas
  consultable (requiere backend: `alerting-service` no expone REST); gestión de usuarios/roles;
  severidad por lectura en el payload del WS de `query-api` (sería un `FIX` propio); i18n
  multi-idioma y PWA/offline.
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
- **Roadmap v1 restante**: la **serie del frontend** (`FEAT-0009` → `FEAT-0014` → `FEAT-0015` →
  `FEAT-0016`, hoy en Gate), después Fase C (agregados + historial de alertas), Redis para `/actual`
  y caché del resumen, y manifiestos K8s (documentación).

### Notas de entorno (relevantes para reproducir)

- Los **ITs requieren Docker Desktop**; `mvn test` a secas **no** los ejecuta (surefire excluye `*IT`).
- JDK 25 bloquea el auto-attach del agente de Mockito: el proyecto usa el **subclass mock maker**
  (`services/ingestion-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`).
- Los tests corren **offline** (`mvn -o`): todas las dependencias están en el `~/.m2` local. No se
  agregaron dependencias nuevas en FEAT-0007, FEAT-0008, FIX-0006 ni FIX-0007.
- Los ITs de `api-gateway` **no requieren Docker**: levantan downstreams stub en proceso (servidor
  HTTP del JDK + Reactor Netty para el WS) y cuentan exactamente qué recibe cada uno. El IT de
  `query-api` sí usa Testcontainers (Postgres real para el `DISTINCT ON`).
