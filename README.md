# WR-Sensor — Water Level Monitoring Platform

Plataforma de monitoreo de altura de agua (ríos Paraná y Salado, Santa Fe) desarrollada
con **Spec-Driven Development Gate/Loop (SDD-GL)**. Cada funcionalidad nace como un
*Contract* (`contracts/FEAT-*.md` / `FIX-*.md`) que pasa por un Gate de aprobación
humana (HO-Gate) y luego un Loop autónomo de implementación + tests hasta
`RESOLVED`.

> Estado al **2026-09-14**: 18 Work Items cerrados (~99% del roadmap v1).

---

## 1. Qué es

WR-Sensor simula y procesa un pipeline completo de telemetría fluvial:

```
data-simulator  ──RabbitMQ (sensor.lecturas)──▶  ingestion-service  ──TimescaleDB──▶  histórico
      │                                                   │
      │                                                   └─RabbitMQ (sensor.alertas)──▶ alerting-service ──WebSocket──▶ clientes
      ▼
sensor-registry  (CRUD + auth JWT de sensores, fuente de verdad de config)
```

| Servicio | Módulo | Rol | Stack |
|---|---|---|---|
| `sensor-registry` | `services/sensor-registry` | CRUD de sensores + auth JWT | Java 25 · Spring Boot 4.1 · WebFlux · R2DBC · Postgres · Reactor RabbitMQ |
| `data-simulator` | `services/data-simulator` | Genera lecturas sintéticas | WebFlux · Reactor RabbitMQ (publisher) |
| `ingestion-service` | `services/ingestion-service` | Consume lecturas, calcula severidad, persiste | WebFlux · Reactor RabbitMQ (consumer) · R2DBC · TimescaleDB |
| `alerting-service` | `services/alerting-service` | Histéresis + notificación WS | WebFlux · Reactor RabbitMQ (consumer) · WebSocket |
| `query-api` | `services/query-api` | Histórico/última lectura + WS tiempo real | WebFlux · R2DBC/TimescaleDB · Reactor RabbitMQ · WebSocket |

Orquestación local: **`docker compose up -d --build`** (requiere `mvn -o install -DskipTests` previo en `services/`; ver `docs/RUNBOOK.md`).\n\nReglas de stack fijas en `stack.md` y `docs/SKILL.md` (hexagonal estricto, cero
bloqueante, sin JPA, paginación keyset, DLQ/retry configurables).

---

## 2. Arquitectura (resumen)

Event-driven microservices sobre **Java 25 + Spring WebFlux** con arquitectura
**hexagonal por servicio**: pipeline reactivo de telemetría fluvial con
RabbitMQ como columna vertebral, TimescaleDB para series temporales, auth JWT y
realtime por WebSocket — orquestado con **Spec-Driven Development Gate/Loop
(SDD-GL)**.

```
clientes ─HTTP/WS→ api-gateway (rate limiting + correlación) ─→ registry · query-api · alerting
data-simulator ─sensor.lecturas→ ingestion-service ─sensor.alertas→ alerting-service ─WS→ clientes
sensor-registry: CRUD + auth JWT (fuente de config) · query-api: histórico keyset + última lectura + WS por sensor
```

| Servicio | Rol | Notas de arquitectura |
|---|---|---|
| `api-gateway` | punto de entrada único | hex · WebFlux `WebClient`/`RouterFunction` · rate limiting token bucket por clase+IP · `X-Correlation-Id` · túnel WS |
| `sensor-registry` | CRUD + auth | hex · R2DBC Postgres · guards por atributo de exchange (ADR-0006) · naive-UTC |
| `data-simulator` | lecturas sintéticas | hex · Reactor RabbitMQ · stateless |
| `ingestion-service` | consume + severidad + persistencia | hex · R2DBC **TimescaleDB** hypertable · DLQ/DLX configurable · consumo particionado |
| `alerting-service` | histéresis + notificación | hex · debounce temporal · WebSocket `/ws/alertas` |
| `query-api` | consulta histórica/última/tiempo real | hex · R2DBC lectura · consumer `sensor.lecturas` → WS por sensor |

Características: **cero bloqueante** (sin JPA ni `.block()` en producción),
paginación **keyset** (nunca OFFSET), reintentos/DLQ configurables en
`application.yml`, tests trazables a los contracts (BR/AC/AF) — **335 verdes**.
Detalle completo: `docs/ARQUITECTURA.md` y decisiones en `docs/DECISIONES.md`.

## 3. Documentación (índice)

| Documento | Contenido |
|---|---|
| `docs/ARQUITECTURA.md` | Visión de sistema, contratos de mensajería y datos, decisiones por servicio |
| `docs/API.md` | Endpoints por servicio: auth, códigos de error, ejemplos |
| `docs/RUNBOOK.md` | Requisitos, build/test, credenciales dev, cómo correr e integrar |
| `docs/DECISIONES.md` | Registro de decisiones (ADR) del ciclo SDD-GL |
| `docs/REGISTRO-SDD.md` | Work items: contracts, estado, criterios y suites de tests |
| `docs/ESTADO-SDD.md` | **Tablero `/sdd-status`** (contratos, criterios, suites, backlog y cómo verificarlo) |
| `docs/CHANGELOG.md` | **Changelog del proyecto**: fixes y features con impacto, causa y evidencia |
| `docs/ESTADO-PROYECTO.md` | Bitácora de avance operativa |
| `docs/water-monitoring-spec.md` | Spec base del dominio |
| `contracts/` | Contracts SDD-GL (la fuente de verdad por feature) |
| `.sdd/runs/` | Audits Glass Box de cada Loop |

---

## 4. Estado de los Work Items

| Contract | Funcionalidad | Estado | Suite asociada |
|---|---|---|---|
| `FEAT-0001` | `POST /api/sensores` (alta, ADMIN) | ✅ RESOLVED | BR 12 · AC 9 · AF 5 · IT 1 |
| `FIX-0001` | Handler emite `code` de dominio en bind | ✅ RESOLVED | 12 |
| `FEAT-0002` | `GET /api/sensores` (listado keyset) | ✅ RESOLVED | 15+6+3 · IT 1 |
| `FEAT-0003` | `GET /api/sensores/{id}` (detalle) | ✅ RESOLVED | 3+2 · IT 7 |
| `FEAT-0004` | `PUT /api/sensores/{id}` (config) | ✅ RESOLVED | 7 · IT 9 |
| `FEAT-0005` | `DELETE /api/sensores/{id}` (baja lógica) | ✅ RESOLVED | 3 · IT 8 |
| `FEAT-0006` | Auth JWT `POST /api/auth/login` | ✅ RESOLVED | 7+4+1 · IT 8 |
| `FEAT-0010` | `data-simulator` | ✅ RESOLVED | 13 · IT 1 |
| `FEAT-0011` | `ingestion-service` | ✅ RESOLVED | 10 · IT 1 |
| `FEAT-0012` | `alerting-service` | ✅ RESOLVED | 7 · IT 1 |
| `FEAT-0013` | `query-api` | ✅ RESOLVED | 7 · IT 1 |
| `FIX-0002` | Parser `sensor.alertas` (valorLectura/cruceHisteresis) | ✅ RESOLVED | 3 · IT 1 |
| `FIX-0003` | Outbox + idempotencia en ingestion | ✅ RESOLVED | 13 · IT 8 |
| `FIX-0004` | Rango físico + calidad del dato | ✅ RESOLVED | 22 · IT 6 |
| `FIX-0005` | Particionamiento del consumo por `sensorId` | ✅ RESOLVED | 20 · IT 7 |
| `FEAT-0007` | `api-gateway`: entrada única + rate limiting | ✅ RESOLVED | 31 · IT 16 |
| `FIX-0006` | Versionado del schema de `sensor.lecturas` (v1) | ✅ RESOLVED | 27 · IT 7 |
| `FIX-0007` | Resiliencia del lookup de config (breaker + cache TTL) | ✅ RESOLVED | 20 · IT 5 |

**Suites verdes:** `sensor-registry` 123 · `data-simulator` 20 · `ingestion-service` 121 ·
`alerting-service` 12 · `query-api` 12 · `api-gateway` 47 → **335 tests** (JUnit 5; ITs con
Testcontainers; detalle por módulo en `docs/RUNBOOK.md` §2).

---

## 5. Quickstart

Requisitos: JDK 25, Maven (ver ruta en `docs/RUNBOOK.md`), Docker Desktop.

```bash
# 1) Tests de un servicio (offline)
cd services/sensor-registry
JAVA_HOME="C:/Program Files/Amazon Corretto/jdk25.0.3_9" \
  "C:/Users/Gerardo/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn.cmd" -o test

# 2) Correr un IT (requiere Docker)
... mvn.cmd -o test -Dtest='FEAT0001MainFlowIT'
```

Credenciales dev (auth FEAT-0006): `admin@wrsensor.local` / `Admin123!` ·
`viewer@wrsensor.local` / `Viewer123!`.

---

## 6. Nota sobre el frame SDD-GL

Frame **SDD-GL v0.3.0** instalado en el repo (protocolos EXPRESS/STRICT, Glass Box
en `.sdd/runs/`, orquestadores `CLAUDE.md`/`AGENTS.md`, skills en `.agents/skills/`,
presets y spec MCP en `mcp/`). Autor: [CharlyZeta/SDD-GL](https://github.com/CharlyZeta/SDD-GL).






