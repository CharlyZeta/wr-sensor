# WR-Sensor — Runbook

Cómo construir, testear y correr los servicios localmente (desarrollo Windows).

## 1. Requisitos

- **JDK 25** (Amazon Corretto): `C:\Program Files\Amazon Corretto\jdk25.0.3_9`
- **Maven 3.9.9** (wrapper local — no está en PATH):
  `C:\Users\Gerardo\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd`
- **Docker Desktop** corriendo (ITs con Testcontainers: postgres/rabbitmq/timescaledb).
- Repositorio local Maven `~/.m2` ya poblado → todo build con `-o` (offline).

## 2. Build + tests

Por cada módulo (`sensor-registry`, `data-simulator`, `ingestion-service`,
`alerting-service`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25.0.3_9"
& "C:\Users\Gerardo\.m2\wrapper\dists\apache-maven-3.9.9-bin\33b4b2b4\apache-maven-3.9.9\bin\mvn.cmd" -o test
```

- Unit/assertion (`*Test`) corren sin Docker.
- ITs (`*IT`) corren con contenedores; invocarlos explícitamente:

```powershell
& …\mvn.cmd -o test -Dtest='FEAT0001MainFlowIT'
& …\mvn.cmd -o test -Dtest='FEAT0001MainFlowIT,FEAT0002MainFlowIT,FEAT0003MainFlowIT,FEAT0004MainFlowIT,FEAT0005MainFlowIT,FEAT0006MainFlowIT'
```

- `ingestion-service` IT usa TimescaleDB real cuando está disponible:

```powershell
$env:IT_TIMESCALE_IMAGE = "timescale/timescaledb:latest-pg16"   # docker pull previo
& …\mvn.cmd -o test -Dtest='FEAT0011MainFlowIT'
```

### Resumen de suites (verde al 2026-09-09)

| Módulo | Unit/assert | ITs | Total |
|---|---|---|---|
| `sensor-registry` | 89 | 34 | 123 |
| `data-simulator` | 13 | 1 | 14 |
| `ingestion-service` | 10 | 1 | 11 |
| `alerting-service` | 10 | 2 | 12 |
| `query-api` | 7 | 1 | 8 |
| **Total** | **129** | **39** | **168** |

## 3. Credenciales y config dev

Auth (sensor-registry): `admin@wrsensor.local` / `Admin123!` · `viewer@wrsensor.local` /
`Viewer123!` (seed automático `DevUserSeeder`; email-único, upsert). En entornos
reales setear `AUTH_JWT_SECRET`.

Secretos/env relevantes: `AUTH_JWT_SECRET`, `R2DBC_*`, `RABBIT_*`, `REGISTRY_URL`
(consumida por ingestion), `INGESTION_R2DBC_URL`. RabbitMQ por defecto
`localhost:5672 guest/guest`.

## 4. Correr los servicios localmente

Orquestación local (docker-compose.yml raíz):

1. Empaquetar los servicios (primera vez puede requerir red para cachear
   `maven-jar-plugin`): `cd services` → `mvn -o install -DskipTests` (o sin `-o`
   la primera vez).
2. `cd ..` → `docker compose up -d --build`
3. `docker compose ps` — servicios: registry :8080, simulator :8081, query-api
   :8082 (REST + WS), alerting :8083 (WS), más postgres/timescale/redis/rabbitmq.
4. `docker compose down -v` para reinicio limpio (borra volúmenes).

También se puede ejecutar un servicio suelto con Spring Boot para debug:

```powershell
cd D:\ProyectosDual\WR-Sensor\services\sensor-registry
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25.0.3_9"
& …\mvn.cmd -o spring-boot:run
```

Flujo de prueba end-to-end manual:

```powershell
# 1) login (registry) → token
# 2) alta de sensor (POST /api/sensores) o usar seeds
# 3) simulator: POST /api/simulador/iniciar  → publica sensor.lecturas
# 4) ingestion consume y persiste; alerting notifica por ws://localhost:<puerto>/ws/alertas
```

## 5. Orquestación SDD-GL

- Orquestador: `CLAUDE.md` (Claude Code) / `AGENTS.md` (Antigravity). Arranque: leer
  `contracts/[ID].md` → DRAFT/GATE → `sdd-gate`; APPROVED/LOOP → `sdd-loop`;
  RESOLVED → work item completo.
- Auditoría de cada Loop: `.sdd/runs/FEAT-XXXX-<timestamp>.md` (Glass Box).



