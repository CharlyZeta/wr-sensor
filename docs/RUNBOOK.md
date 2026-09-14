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

### Resumen de suites (verde al 2026-09-13)

| Módulo | Unit/assert | ITs | Total |
|---|---|---|---|
| `sensor-registry` | 89 | 34 | 123 |
| `data-simulator` | 19 | 1 | 20 |
| `ingestion-service` | 88 | 33 | 121 |
| `alerting-service` | 10 | 2 | 12 |
| `query-api` | 11 | 1 | 12 |
| `api-gateway` | 31 | 16 | 47 |
| **Total** | **248** | **87** | **335** |

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
3. `docker compose ps` — **sólo `api-gateway` publica puerto** (:8084, FEAT-0007): REST y WS
   entran por ahí. Los demás servicios (registry :8080, simulator :8081, query-api :8082,
   alerting :8083, ingestion) quedan en la red interna de Compose.
4. `docker compose down -v` para reinicio limpio (borra volúmenes).

### Acceso directo para desarrollo (FEAT-0007)

El default cierra los puertos de los servicios de aplicación (punto de entrada único +
rate limiting). Para debug directo — curl a cada servicio, probar el simulador sin pasar por
el gateway, inspeccionar un WS — usar el override:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Ese override (`docker-compose.dev.yml`) republica :8080/:8081/:8082/:8083 y **anula** la
protección del punto de entrada único: no usarlo fuera de la máquina de desarrollo.

### Ajustar el gateway (límites, rutas, timeouts)

Todo es configuración, sin recompilar:

```powershell
# límites más estrictos para login y ventana de lectura larga, vía entorno
$env:GATEWAY_RL_EXPIRACION = "600"          # segundos que sobrevive una clave inactiva
$env:GATEWAY_CONFIAR_XFF   = "false"        # true = usar X-Forwarded-For como clave (sólo tras un proxy confiable)
docker compose up -d api-gateway
```

Los valores por clase (`default` 300/60 s burst 100, `login` 10/60 s burst 10,
`lectura` 120/60 s burst 60, `ws` 30/60 s burst 30), las rutas y los `timeout-ms` viven en
`services/api-gateway/src/main/resources/application.yml`. Verificación rápida del límite:

```powershell
1..12 | % { (Invoke-WebRequest -Method POST http://localhost:8084/api/auth/login -SkipHttpErrorCheck).StatusCode }
# → diez 200 y luego 429 con Retry-After
```

También se puede ejecutar un servicio suelto con Spring Boot para debug:

```powershell
cd D:\ProyectosDual\WR-Sensor\services\api-gateway
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25.0.3_9"
& …\mvn.cmd -o spring-boot:run
```

Flujo de prueba end-to-end manual:

```powershell
# 1) login (registry) → token   (POST http://localhost:8084/api/auth/login)
# 2) alta de sensor (POST /api/sensores) o usar seeds
# 3) simulator: POST /api/simulador/iniciar  → publica sensor.lecturas (vía override de dev)
# 4) ingestion consume y persiste; alerting notifica por ws://localhost:8084/ws/alertas
```

## 5. Schema del evento `sensor.lecturas` (FIX-0006)

El evento lleva `schemaVersion` (`MAYOR.MENOR`), `eventId`, `sequence` y `calidad` informativa
(payload completo en `docs/ARQUITECTURA.md` §2). Convivencia:

- El **publisher** publica la versión configurada: `SIMULADOR_SCHEMA_VERSION` (default `1.0`).
- El **consumer** de ingestion declara qué soporta y cómo reaccionar:
  `INGESTION_SCHEMA_VERSION` (default `1.0`) e `INGESTION_SCHEMA_TOLERAR_MAYORES` (default
  `true`). Con `true`, una versión mayor desconocida se procesa con WARN; con `false` se rechaza
  a la DLQ con motivo `SCHEMA_UNSUPPORTED`.
- El **payload legado** (sin `schemaVersion`) sigue siendo válido: se procesa y se registra un
  INFO de "evento legado" con contador, para poder decidir el cierre de la ventana con datos.

Inspeccionar el evento que está circulando:

```powershell
docker compose exec rabbitmq rabbitmqadmin get queue=queue.sensor.lecturas.p0 count=1
# o, sin ack y con detector de huecos:
docker compose logs ingestion-service | Select-String "hueco de secuencia|evento legado|mayor desconocida"
```

Para verificar el rechazo por versión inválida (queda en la DLQ con `x-rechazo`):

```powershell
docker compose exec rabbitmq rabbitmqadmin publish exchange=sensor.lecturas routing_key=lectura.<id> \
  payload='{"schemaVersion":"uno","sensorId":"<id>","timestamp":"2026-09-13T10:00:00Z","valor":1,"unidadMedida":"METROS"}'
docker compose exec rabbitmq rabbitmqadmin get queue=queue.sensor.lecturas.dlq count=1
```

## 6. Escalado horizontal de `ingestion-service` (FIX-0005)

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

## 7. Resiliencia del lookup de config (FIX-0007)

`ingestion-service` resuelve la config de cada sensor contra `sensor-registry` por HTTP. Para que
una caída del registry no tumbe el consumo, el lookup tiene **timeouts explícitos**, un **circuit
breaker** y una **cache con TTL + last-known-good**:

| Pieza | Config (env) | Default |
|---|---|---|
| Timeout de respuesta | `INGESTION_REGISTRY_TIMEOUT` | 2000 ms |
| Timeout de conexión | `INGESTION_REGISTRY_CONEXION_TIMEOUT` | 1000 ms |
| TTL de cache de config | `INGESTION_REGISTRY_CACHE_TTL` | 300 s |
| Fallos consecutivos para abrir | `INGESTION_CIRCUITO_FALLOS` | 5 |
| Segundos abierto (→ semi-abierto) | `INGESTION_CIRCUITO_SEGUNDOS_ABIERTO` | 30 |
| Éxitos en semi-abierto para cerrar | `INGESTION_CIRCUITO_EXITOS_CERRAR` | 2 |

Comportamiento:

- Sensor **en cache fresca** → se resuelve sin red.
- Sensor **en cache vencida** → se refresca; si el registry falla o el circuito está abierto, se
  usa la copia vencida con WARN (config vieja antes que perder lecturas).
- Sensor **nunca visto** + registry caído → la lectura va a la DLQ con
  `x-rechazo: REGISTRY_UNAVAILABLE` (no se pierde silenciosamente).
- Token del registry **rechazado (401)** → se invalida y se revalida una vez; no deja el servicio
  en fallo permanente.
- `ingestion.messaging.retry-max-attempts` **no se usa** (reintentos fuera de alcance en FIX-0007):
  el camino de fallo es la DLQ. Queda como deuda explícita hasta un work item de reintentos.

Estado del circuito (endpoint interno, sin auth, no expone datos de sensores):

```powershell
Invoke-WebRequest http://localhost:8080/api/ingestion/resiliencia | Select-Object -Expand Content
# → {"circuito":"CERRADO","fallosConsecutivos":0,"llamadas":12,"cacheTamano":6, ...}
```

Los logs también marcan las transiciones: `WARN ... circuit breaker del registry ABIERTO` e
`INFO ... SEMIABIERTO`/`CERRADO`. Si el circuito queda abierto mucho tiempo, revisar
`sensor-registry` y su healthcheck (`docker compose ps sensor-registry`).

## 8. Orquestación SDD-GL

- Orquestador: `CLAUDE.md` (Claude Code) / `AGENTS.md` (Antigravity). Arranque: leer
  `contracts/[ID].md` → DRAFT/GATE → `sdd-gate`; APPROVED/LOOP → `sdd-loop`;
  RESOLVED → work item completo.
- Auditoría de cada Loop: `.sdd/runs/FEAT-XXXX-<timestamp>.md` (Glass Box).





