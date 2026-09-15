# Continuidad — cómo retomar WR-Sensor si se pierde la sesión

> **Para quién es esto:** para un agente o desarrollador que llega **sin contexto previo** y tiene
> que seguir el proyecto. Está escrito para leerse de arriba abajo en ~10 minutos y poder retomar
> sin adivinar nada. Última actualización: **2026-09-14**.

## 0. Resumen en 30 segundos

- **Qué es**: plataforma showcase de telemetría fluvial (6 sensores sintéticos de Paraná/Salado),
  microservicios reactivos Java 25 / Spring Boot 4.1 WebFlux, hexagonal, RabbitMQ + TimescaleDB,
  orquestada con Docker Compose, con un **punto de entrada único** (`api-gateway`).
- **Cómo se trabajó**: con el frame **SDD-GL** (spec-driven, Gate → aprobación humana → Loop
  autónomo → RESOLVED) instalado en este repo. Cada work item tiene su **Contract** en `contracts/`.
- **Estado**: **19 work items RESOLVED** (12 FEAT + 7 FIX), **408/408 criterios ✅**, **413 tests**
  (306 unit + 107 IT). No queda trabajo abierto: `FEAT-0008` (CORS, auth de WebSocket y resumen de
  sensores) cerró su Loop el 2026-09-14 y sólo espera la **validación humana del resultado**.
- **Siguiente paso natural**: abrir `FEAT-0009` — SPA React Fase A (login + mapa Leaflet + detalle en
  vivo por WS autenticado + feed de alertas + serie de 24 h), servido por el gateway.

## 1. Qué leer, en este orden

| # | Documento | Para qué |
|---|---|---|
| 1 | `docs/ESTADO-SDD.md` | Tablero: estado de cada contract, criterios, suites, backlog, cómo verificar |
| 2 | `docs/CONTINUIDAD.md` (este) | Cómo retomar: proceso, comandos, entorno, trampas, trabajo en curso |
| 3 | `contracts/FEAT-0008.md` | El último work item cerrado (31/31), con decisiones y ADR-0019 asociado |
| 4 | `CLAUDE.md` / `AGENTS.md` | Orquestador SDD-GL (qué skill cargar según el estado del contract) |
| 5 | `docs/CHANGELOG.md` | Qué cambió y por qué, con causa/impacto/evidencia por fix |
| 6 | `docs/ARQUITECTURA.md`, `docs/API.md`, `docs/RUNBOOK.md` | Cómo funciona y cómo se corre |
| 7 | `docs/DECISIONES.md` | ADR-0001..ADR-0019 (todas las decisiones de diseño) |
| 8 | `stack.md`, `docs/water-monitoring-spec.md` | Stack locked y spec de origen |

## 2. El proceso (SDD-GL) tal como se usa acá

1. **Se elige un work item** (del roadmap o del backlog). El ID sale de la **serie autoritativa
   `contracts/`**: FEAT para funcionalidad nueva, FIX para bugs. Libres hoy: **`FEAT-0009`**,
   **`FIX-0008`**; después de esos, FEAT sigue en `FEAT-0014`.
2. **Gate** (`protocol/gate.md`): se completa el Contract (`Intent`, `Use Case`/`Reproduction
   Steps`, `Business Rules`, `Acceptance Criteria`, `Ambiguity Log`, `Completion Map`) y se revisa
   consistencia. **El humano es el único que aprueba** (`Status: APPROVED` + `Mode: LOOP`).
3. **Loop** (`protocol/loop.md`): se implementa y testea criterio por criterio, escribiendo el
   estado en el `Completion Map` (`❌ → ⏳ → ✅`) y una auditoría **Glass Box** en
   `.sdd/runs/<ID>-<timestamp>.md` con archivos leídos, diff, comando de test, salida del runner y
   justificación. Al terminar: `Status: RESOLVED`.
4. **Bookkeeping obligatorio al cerrar** (es lo que mantiene el repo legible para terceros):
   `README.md` (tabla de work items + totales), `docs/RUNBOOK.md` §2 (suites), `docs/REGISTRO-SDD.md`
   (tabla de suites + lista de fixes), `docs/ESTADO-SDD.md` (tablero), `docs/CHANGELOG.md`
   (entrada del work item), `docs/ESTADO-PROYECTO.md` (§2x con la bitácora) y `docs/DECISIONES.md`
   (ADR nuevo si hubo decisión de diseño).
5. **Commit + push por work item**: mensaje desde archivo (`git commit -F <archivo>`) porque los
   mensajes largos inline a veces rompen `pwsh` en este entorno; al final `git push origin main`.

> **ADR-0012 (importante):** en este entorno los subagentes del frame **no producen artefactos en
> disco**, así que el **orquestador ejecuta los Loops directamente** manteniendo la contabilidad
> del protocolo (mapa + auditoría + estado). Si en el futuro los subagentes funcionan, se puede
> volver a delegar sin cambiar los contracts.

## 3. Mapa del repositorio

```
WR-Sensor/
├── CLAUDE.md / AGENTS.md        # orquestador (Claude Code / Antigravity)
├── stack.md                     # stack locked (regla dura: no proponer alternativas)
├── docker-compose.yml           # stack completo (sólo api-gateway publica puerto)
├── docker-compose.dev.yml       # override de dev: republica puertos (8080..8083 + 8090 ingestion)
├── infra/rabbitmq/enabled_plugins   # FIX-0005: habilita x-consistent-hash
├── contracts/                   # 19 Contracts (fuente de verdad por work item)
├── protocol/{contract,gate,loop}.md # proceso
├── .sdd/runs/                   # auditorías Glass Box de cada Loop
├── .agents/skills/, .claude/    # skills y comandos del frame (sdd-gate, sdd-loop, sdd-status…)
├── docs/                        # ARQUITECTURA, API, RUNBOOK, DECISIONES, REGISTRO-SDD,
│                                # ESTADO-SDD, CHANGELOG, ESTADO-PROYECTO, CONTINUIDAD, spec
└── services/                    # un módulo Maven por servicio (pom propio, parent Boot 4.1)
    ├── sensor-registry/         # CRUD + auth JWT            (:8080)
    ├── data-simulator/          # lecturas sintéticas         (:8081)
    ├── ingestion-service/       # consume + severidad + outbox (interno; dev :8090)
    ├── alerting-service/        # histéresis + WS /ws/alertas (:8083)
    ├── query-api/               # histórico/última/resumen/WS   (:8082)
    └── api-gateway/             # entrada única + CORS + auth WS (:8084)
```

## 4. Comandos exactos

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25.0.3_9"
$mvn = "C:\Users\Gerardo\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd"

# 1) Unit tests de un módulo (OFFLINE, sin Docker, ~10-40 s)
& $mvn -o -f services\ingestion-service\pom.xml test

# 2) ITs (REQUIEREN Docker Desktop; surefire NO los corre con `test` a secas)
& $mvn -o -f services\ingestion-service\pom.xml test "-Dtest=*IT"

# 3) Suite completa de un módulo (unit + IT)
& $mvn -o -f services\ingestion-service\pom.xml test; & $mvn -o -f services\ingestion-service\pom.xml test "-Dtest=*IT"

# 4) Empaquetar todo (lo que consumen los Dockerfiles)
& $mvn -o -f services\pom.xml install -DskipTests

# 5) Stack completo (sólo el gateway publica :8084)
docker compose up -d --build
# ... o con puertos directos para debug (registry :8080, simulator :8081, query :8082,
# alerting :8083, ingestion :8090)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Credenciales de desarrollo: `admin@wrsensor.local` / `Admin123!` (ADMIN) y
`viewer@wrsensor.local` / `Viewer123!` (VIEWER). Secreto JWT dev:
`wrsensor-dev-secret-2026-no-usar-en-prod` (`AUTH_JWT_SECRET`).

Endpoints útiles: login `POST /api/auth/login` · gateway `http://localhost:8084` ·
estado del circuit breaker `http://localhost:8090/api/ingestion/resiliencia` (con override de dev).

## 5. Entorno: trampas conocidas (todas resueltas, pero volverán)

| Trampa | Síntoma | Solución |
|---|---|---|
| **Docker Desktop apagado** | `Could not find a valid Docker environment` o `permission denied … dockerDesktopLinuxEngine` | Levantar Docker Desktop; los ITs no corren sin él |
| **Sandbox y named pipes** | `docker`/`git push` fallan con `permission denied` aunque Docker esté arriba | El sandbox debe estar en modo amplio (danger-full-access) para que el proceso pueda abrir el pipe |
| **Mockito + JDK 25** | `Could not initialize plugin: MockMaker` / `Could not self-attach … external process` | Ya resuelto: `services/ingestion-service/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` = `mock-maker-subclass`. No usar el inline mock maker (necesita agente) |
| **`mvn test` no corre ITs** | Parece que faltan tests | Surefire excluye `*IT`: usar `-Dtest='*IT'` |
| **Build offline** | `mvn -o` falla si falta un artefacto | Todo está en `~/.m2`; **no se agregaron dependencias nuevas** en FEAT-0007/FEAT-0008/FIX-0006/FIX-0007. Si hiciera falta una, hay que cachearla una vez sin `-o` |
| **Jackson 3 (Boot 4.1)** | `package com.fasterxml.jackson.databind does not exist` al compilar | Spring Boot 4.1 trae **Jackson 3** (`tools.jackson.databind`, groupId `tools.jackson.core`): usar `JsonMapper`/`JsonNode` de `tools.jackson.*`. Las anotaciones siguen en `com.fasterxml.jackson.annotation` (vía testcontainers). Patrón del repo: `JsonMapper.builder().disable(FAIL_ON_UNKNOWN_PROPERTIES).build()` |
| **ITs del gateway sin Docker** | `*-Dtest='*IT'` no necesita contenedores | Los ITs de `api-gateway` levantan downstreams stub en proceso (HttpServer del JDK + Reactor Netty); el de `query-api` sí usa Testcontainers |
| **Heredoc en pwsh** | `ParserError: Falta la especificación de archivo…` | Escribir el mensaje de commit a un archivo y usar `git commit -F archivo` |
| **Mensajes largos inline** | `Fatal error. Internal CLR error (0x80131506)` | Idem: mensaje a archivo |
| **Avisos LF→CRLF** | `warning: LF will be replaced by CRLF` en `git add` | Inofensivo, ignorar |
| **Fechas del proyecto** | Los commits/auditorías dicen 2026-09 | Es la línea de tiempo del proyecto, no un error |

## 6. Último trabajo cerrado: `FEAT-0008` (Loop 31/31 ✅, pendiente validación humana)

**Qué es:** los tres habilitadores que el frontend necesita del backend. **Los tres están
implementados y con tests verdes** (`api-gateway` 67+29 · `query-api` 33+8).

| # | Alcance | Estado / decisión aprobada |
|---|---|---|
| 1 | **CORS** en el gateway, configurable (`gateway.cors.origenes`, default vacío; dev `http://localhost:5173`), preflight respondido por el gateway sin consumir cupo | ✅ Implementado (`FiltroCors`). **Ojo:** same-origin **no** es CORS — el `Origin` propio (POST y handshake WS) pasa sin headers y sin bloqueo |
| 2 | **Autenticación del handshake WebSocket** (`/ws/alertas`, `/ws/sensores/**`) validando JWT (HS256 + `exp`) en el gateway; token por `?token=` o `Authorization: Bearer`; sin token → `401 UNAUTHENTICATED` antes del upgrade | ✅ Implementado (`VerificadorJwt` + `AutenticadorWs`); el token no se propaga ni se loguea. **Deja de ser brecha de ADR-0016** |
| 3 | **`GET /api/sensores/resumen`** en `query-api`: todos los sensores con metadata (registry) + última lectura (hypertable, **una** consulta), roles `{ADMIN, VIEWER}`, enrutado por el gateway con clase `lectura` | ✅ Implementado; registry caído → `502 REGISTRY_UNAVAILABLE` sin datos parciales |

**Decidido y fuera de alcance de FEAT-0008**: el SPA lo **sirve el gateway** (implementación en
FEAT-0009), el **simulador sigue sin ruta** (la demo usa el override de dev), el mapa usa
**Leaflet**, y el frontend arranca por la **Fase A**.

**Antes de tocar nada de este work item, leer ADR-0019** (`docs/DECISIONES.md`): documenta la
decisión de diseño y el bug que encontró el Loop (CORS rechazando el mismo origen, que rompía login
y WS del SPA servido por el gateway).

## 7. Qué sigue después

1. **`FEAT-0009` — frontend React (Fase A)**: login + **mapa Leaflet** con los 6 sensores
   coloreados por severidad + detalle con última lectura **en vivo por WS** + feed de alertas
   confirmadas + serie de 24 h. El SPA se sirve **desde el gateway** (build de Vite copiado a los
   recursos estáticos del gateway) — eso hace que en producción no haga falta CORS, pero se
   mantiene para desarrollo y consumidores externos.
   - Toolchain: **Node 26.3.0 / npm 11.16.0 ya instalados** (no hay pnpm/yarn). El build del
     frontend **necesita red** (npm registry) — es la primera pieza que rompe la propiedad
     "build offline" del backend; conviene commitear el `package-lock.json` y construir el SPA en
     su propio stage de Docker.
   - `node_modules/` y `dist/` ya están en el `.gitignore` (agregados al preparar FEAT-0009).
   - Tests del frontend: **Vitest + React Testing Library + MSW** (y Playwright para E2E). El
     Contract del frontend debe declarar ese runner como criterio verificable.
   - El WS se autentica con `?token=<jwt>` (FEAT-0008): el SPA tiene que abrir
     `ws(s)://<mismo-origen>/ws/alertas?token=…` y re-loguearse cuando el token expire (60 min).
   - El mapa se alimenta de **`GET /api/sensores/resumen`** (una llamada), no de N+1.
2. **Fase B**: CRUD de sensores (ADMIN) con validación y errores de dominio finos; manejo explícito
   de `calidad=ERROR_SENSOR`; control del simulador para la demo.
3. **Fase C**: rangos largos con **continuous aggregates** (spec §9.2), historial de alertas
   consultable, export CSV.
4. **Follow-ups técnicos abiertos** (sin contract todavía): persistir `ultimaSeveridad` de ingestion
   (hoy en memoria por instancia), afinidad de `alerting-service` si se escala, **reintentos con
   backoff** (`messaging.retry-max-attempts` está **sin uso** — deuda declarada en FIX-0007),
   historial de alertas, decisión de tiles del mapa (OSM remoto vs mapa esquemático offline),
   **caché del resumen** en Redis y **autenticación de los REST en el gateway** (FEAT-0008 sólo
   autenticó el upgrade WS).

## 8. Cómo retomar (checklist)

1. `git log --oneline -5` y `git status` → confirmar que `main` está al día con `origin/main`.
2. Leer `docs/ESTADO-SDD.md` (tablero) y este documento.
3. **No hay contracts en vuelo**: todos están RESOLVED. Para el próximo work item (`FEAT-0009`)
   hay que crear el Contract con `/sdd-feature` y **el humano** aprueba
   (`Status: APPROVED` + `Mode: LOOP`) antes de que arranque el Loop.
4. Verificar el entorno antes de tocar código:
   `docker version` (para ITs), `node --version` (para el frontend), `$env:JAVA_HOME` y `$mvn`.
5. Correr la línea base para asegurarse de que el árbol está sano:
   `& $mvn -o -f services\api-gateway\pom.xml test` (67 unit) y
   `& $mvn -o -f services\query-api\pom.xml test` (33 unit) — son los módulos que tocó el último
   work item. Los ITs del gateway **no necesitan Docker**; los de query-api sí.
6. Continuar con el work item abierto siguiendo `protocol/gate.md` o `protocol/loop.md`.

## 9. Convenciones que no hay que romper

- **IDs**: la serie de `contracts/` es la autoritativa. Los documentos de `docs/FIX-*.md` son
  **backlog histórico**: al promoverse se renumeran y se marcan como PROMOCIONADO (ver el mapeo en
  `docs/ESTADO-PROYECTO.md` §3d).
- **Un criterio = un test**: cada `BR-XXX`/`AC-XXX`/`AF-XX` tiene su test con el ID en el nombre o
  `@DisplayName`; el `Completion Map` registra el `test-id`.
- **Nada hardcodeado**: límites, timeouts, TTL, umbrales, rangos y versiones de schema viven en
  `application.yml` con overrides por entorno.
- **Cero bloqueante**: WebFlux + R2DBC + Reactor RabbitMQ; nada de JPA, `RabbitTemplate` ni
  `.block()` en producción (sí en arranque/tests cuando está justificado).
- **Hexagonal estricto**: `domain` y `application` no importan infraestructura.
- **No hay librería común** entre servicios: duplicar lógica chica (como el parser de versión de
  schema o el verificador JWT) es deliberado.
- **Documentar el porqué**: cada fix/feature deja ADR si tomó una decisión de diseño, y entrada en
  el CHANGELOG con causa e impacto (no sólo "se agregó X").
