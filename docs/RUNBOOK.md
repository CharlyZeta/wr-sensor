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

### Resumen de suites (verde al 2026-09-11)

| Módulo | Unit/assert | ITs | Total |
|---|---|---|---|
| `sensor-registry` | 89 | 34 | 123 |
| `data-simulator` | 13 | 1 | 14 |
| `ingestion-service` | 45 | 21 | 66 |
| `alerting-service` | 10 | 2 | 12 |
| `query-api` | 7 | 1 | 8 |
| **Total** | **164** | **59** | **223** |

> Los `*IT` no corren en `mvn test` (surefire los excluye): se ejecutan con
> `mvn -o test -Dtest='*IT'` y requieren Docker Desktop.

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

## 5. Escalado horizontal de `ingestion-service` (FIX-0005)

El consumo está **particionado por `sensorId`**: el exchange `x-consistent-hash`
`sensor.lecturas.part` reparte las lecturas entre `N` colas
`queue.sensor.lecturas.p0 … p{N-1}` y cada instancia consume un subconjunto **disjunto**.
Eso garantiza afinidad sensor → partición → instancia (orden por sensor y estado en memoria
`ultimaSeveridad` coherente).

1. Habilitar el exchange type en el broker: `docker-compose.yml` monta
   `infra/rabbitmq/enabled_plugins` con `rabbitmq_consistent_hash_exchange` (viene incluido en
   la distribución de RabbitMQ, pero hay que habilitarlo). Verificación:
   `docker compose exec rabbitmq rabbitmq-plugins list -e | grep consistent_hash`.
2. Definir particiones: `INGESTION_PARTICIONES_TOTAL=4` (default). Escalar requiere asignar a
   cada instancia su subconjunto (no se puede con un único `--scale` porque todas las réplicas
   comparten entorno); usar un servicio por instancia o un override:

   ```yaml
   # docker-compose.override.yml
   services:
     ingestion-service:
       environment:
         INGESTION_PARTICIONES_TOTAL: 4
         INGESTION_PARTICIONES_ASIGNADAS: "0,1"     # instancia A
   ```
3. Verificar el reparto: en la consola de RabbitMQ (`http://localhost:15672`) cada cola
   `queue.sensor.lecturas.p{i}` debe tener **1** consumer como máximo, y las particiones sin
   consumer deben acumular mensajes (la instancia emite un **WARN** al arrancar listándolas).
   Nada se pierde: los mensajes quedan en su cola hasta que aparezca un consumer.
4. **Cambiar `total`** (rebalanceo): el anillo del hash se recalcula, así que hay que hacer
   **reinicio coordinado** de todas las instancias. Procedimiento: detener instancias →
   cambiar `INGESTION_PARTICIONES_TOTAL` en todas → arrancar de nuevo (cada una declara la
   topología completa). No hay rebalanceo en caliente.
5. **Migración desde la cola única** (`queue.sensor.lecturas`, previa a FIX-0005): drenar
   antes de retirarla, o aceptar el reproceso (la clave de idempotencia de FIX-0003 evita
   duplicados). Drenaje:

   ```powershell
   docker compose exec rabbitmq rabbitmqctl purge_queue queue.sensor.lecturas
   # o consumirla sin ack para inspeccionar:
   docker compose exec rabbitmq rabbitmqadmin get queue=queue.sensor.lecturas count=100
   ```

6. Si el broker no soporta el exchange type (plugin sin habilitar) la instancia registra
   **ERROR** y **no** arranca el consumo: nunca degrada a consumir sin particionar.

## 6. Orquestación SDD-GL

- Orquestador: `CLAUDE.md` (Claude Code) / `AGENTS.md` (Antigravity). Arranque: leer
  `contracts/[ID].md` → DRAFT/GATE → `sdd-gate`; APPROVED/LOOP → `sdd-loop`;
  RESOLVED → work item completo.
- Auditoría de cada Loop: `.sdd/runs/FEAT-XXXX-<timestamp>.md` (Glass Box).





