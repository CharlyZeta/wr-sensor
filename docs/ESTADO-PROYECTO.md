# Estado del Proyecto — WR-Sensor (Water Level Monitoring Platform)

> Bitácora de avance del proyecto bajo SDD-GL. Orquestador la actualiza al cerrar
> cada Work Item. Última actualización: 2026-09-09.
> Spec base: `docs/water-monitoring-spec.md`. Stack: `stack.md`.
> Documentación consolidada: `README.md`, `docs/ARQUITECTURA.md`, `docs/API.md`,
> `docs/RUNBOOK.md`, `docs/DECISIONES.md`, `docs/REGISTRO-SDD.md`.

---

## 1. Avance global

| Microservicio (según spec §3) | Estado | Implementación | Contracts cerrados |
|---|---|---|---|
| `sensor-registry` | 🟡 parcial | CRUD de sensores completo — `POST` (FEAT-0001), `GET` listado keyset (FEAT-0002), `GET /{id}` (FEAT-0003), `PUT /{id}` (FEAT-0004), **`DELETE /{id}` baja lógica** (FEAT-0005) + auth JWT real (FEAT-0006) + fix handler (FIX-0001) | `FEAT-0001`..`FEAT-0006`, `FIX-0001` (RESOLVED) |
| `data-simulator` | 🟢 parcial | `services/data-simulator/` — generador de lecturas sintéticas (6 sensores seed §9.5) que publica a `sensor.lecturas`; control `iniciar/detener/{id}/anomalia/estado` | `FEAT-0010` (RESOLVED) |
| `ingestion-service` | 🟢 parcial | `services/ingestion-service/` — consume `sensor.lecturas`, persiste en TimescaleDB (hypertable `lectura`) y publica `AlertaEvento` simple a `sensor.alertas` (histéresis → FEAT-0012); DLQ por rechazos | `FEAT-0011` (RESOLVED) |
| `alerting-service` | 🟢 parcial | `services/alerting-service/` — consume `sensor.alertas`, histéresis (subida inmediata, bajada confirmada por ventana), push WebSocket `/ws/alertas` | `FEAT-0012` (RESOLVED) |
| `query-api` | 🟢 parcial | `services/query-api/` — histórico keyset, `/actual` (última lectura), `WS /ws/sensores/{id}` tiempo real (consume `sensor.lecturas`) | `FEAT-0013` (RESOLVED) |
| Frontend (React + mapa Leaflet/MapLibre + dashboards) | ⬜ sin iniciar | — | — |
| Infra local (Docker Compose de los 5 servicios) | ⬜ sin iniciar | — | — |

**Conclusión:** el proyecto sigue en ~80%. **Pipeline completo + capa de consulta**: histórico/última/tiempo real vía `query-api` (FEAT-0013). Falta frontend e infra (docker-compose). (Histórico previo: pipeline de datos completo en
funcionamiento**: `data-simulator` (FEAT-0010) publica lecturas → `ingestion-service`
(FEAT-0011) persiste en TimescaleDB y emite severidad a `sensor.alertas` →
`alerting-service` (FEAT-0012) aplica histéresis y notifica por WebSocket. Falta la
capa de consulta (`FEAT-0013` query-api), frontend e infra (docker-compose).
CRUD + auth (FEAT-0001..0006, FIX-0001) cerrados.

---

## 2. Lo realizado

### 2a. `FEAT-0001` (RESOLVED) — alta de sensor
`POST /api/sensores` con rol `ADMIN`. Detalle completo en versiones previas de esta
bitácora: dominio hexagonal (`Sensor`, rangos, 8 `SensorException` con `code`),
`CreateSensorUseCase/Service` (valida BR-001..BR-009), controller WebFlux + `RolGuard`
(401/403) + `GlobalErrorHandler`, persistencia R2DBC, evento RabbitMQ `sensor.created`.

### 2b. `FIX-0001` (RESOLVED) — handler emitía class name en vez de domain code
`GlobalErrorHandler.deriveCodeFromBinding` (path de Bean Validation) ahora mapea a los
`code` estables del dominio (`SENSOR_INVALID_*`) en vez de `*.class.getSimpleName()`.

### 2c. `FEAT-0002` (RESOLVED 2026-09-09) — listado keyset
`GET /api/sensores` con paginación **keyset/cursor** (nunca OFFSET), roles `ADMIN` y
`VIEWER` (lectura), `limit` default 100 máx 1000, orden DESC por `fechaInstalacion`
con desempate ASC por `id`, `nextCursor` opaco solo si hay más.

**Fix técnico aplicado durante el Loop** (bug real, reproducido por
`FEAT0002MainFlowIT`): el desempate del cursor se rompía cuando dos sensores
compartían `fechaInstalacion` (se perdía la fila empardada en el corte de página).
Causa raíz: desajuste naive/timezone — `java.sql.Timestamp` era convertido por Spring
R2DBC a wall-clock local (UTC-3), desplazando la comparación en cada cruce
escritura/lectura/predicado. Solución: **naive-UTC consistente** con
`LocalDateTime.ofInstant(ts, UTC)` en `SensorPersistenceAdapter.save`,
`SensorListPersistenceAdapter.listAfter` y el seeding espejo de `FEAT0002MainFlowIT`.

**Tests (suite `sensor-registry` al cierre de FEAT-0003, 2026-09-09: 67 unit/assert + 9 ITs = 76 verdes):**
| Suite | Tests | Cubre |
|---|---|---|
| `ListSensorsServicePaginationTest` | 15 ✅ | BR-001..005, AC-001/005/008/009/011/012 (servicio + fakes keyset) |
| `ListSensorsAuthTest` | 6 ✅ | BR-006/007, AF-02, AC-002/003/004 (guard `requireReader`) |
| `ListSensorsACTest` | 3 ✅ | AC-006/007/010 (mapeo `SENSOR_INVALID_LIMIT/CURSOR`) |
| `FEAT0002MainFlowIT` | 1 ✅ | Main Flow e2e (Postgres+RabbitMQ reales, Testcontainers) |
| Regresión FEAT-0001/FIX | 39 ✅ | `CreateSensorServiceBRTest`, `CreateSensorACTest`, `AF04RolGuardTest`, `FIX0001Ac001Test`, `FEAT0001MainFlowIT` |

### 2d. `FEAT-0003` (RESOLVED 2026-09-09) — detalle de sensor
`GET /api/sensores/{id}`: lectura `{ADMIN, VIEWER}` (mismo `RolGuard.requireReader`),
devuelve el `SensorResponse` completo; UUID inexistente → `404 SENSOR_NOT_FOUND`;
id malformado → `400 SENSOR_INVALID_ID` (code nuevo aprobado en HO-Gate); sensores
`INACTIVO`/`MANTENIMIENTO` se devuelven sin filtrar (decisión HO-Gate). Implementación:
`GetSensorDetailUseCase/Service`, `FindSensorByIdPort` (método `findById` en
`SensorListPersistenceAdapter`), endpoint en `SensorController`, mapeos nuevos en
`GlobalErrorHandler`.

| Suite | Tests | Cubre |
|---|---|---|
| `FEAT0003MainFlowIT` | 7 ✅ | Main Flow e2e + AC-001..007 (200/VIEWER/401/403/404/400/INACTIVO) |
| `GetSensorDetailServiceTest` | 3 ✅ | BR-002 (404), BR-005 (shape), BR-006 (INACTIVO visible) |
| `GetSensorDetailACTest` | 2 ✅ | AC-005 (404) y AC-006 (400) mapeo dominio→HTTP |

### 2e. `FEAT-0006` (RESOLVED 2026-09-09) — auth JWT real
`POST /api/auth/login` emite **JWT HS256** (`{token, rol, expiraEnSegundos}`) validando
`Usuario` (tabla `usuario`: email único + `passwordHash` **BCrypt**). El `RolFilter`
ahora **verifica firma + exp** de cada `Authorization: Bearer <jwt>` (reemplaza el
header literal `Bearer ROLE` de v1-fake — AC-008: el literal ya no es aceptado).
Decisiones HO-Gate: seed dev de 2 usuarios (`admin@wrsensor.local`/`Admin123!`,
`viewer@wrsensor.local`/`Viewer123!` vía `DevUserSeeder`, upsert por email),
expiración 1 h configurable (`auth.jwt.expiration-seconds`), secret configurable
(`auth.jwt.secret` / env `AUTH_JWT_SECRET`), sin refresh/blacklist en v1. Los ITs de
FEAT-0001/0002/0003 se migraron a tokens reales vía login (decisión humana).

> Nota de implementación: JWT HS256 implementado con JDK estándar
> (`infrastructure/adapter/out/security/JwtAdapter`, sin dependencias nuevas —
> jjwt/nimbus incompletos en el `.m2` offline). Documentado en el audit FEAT-0006.

| Suite | Tests | Cubre |
|---|---|---|
| `FEAT0006MainFlowIT` | 8 ✅ | login admin/viewer, 401 credenciales (email inexistente = password incorrecta), 400 body inválido, endpoint protegido con JWT real, token expirado/firma inválida → 401, literal legacy → 401 |
| `JwtAdapterTest` | 7 ✅ | BR-003 claims/exp, BR-004 rechazo (manipulado/expirado/firma), rol ajeno → OTHER, BR-005 stateless, AF-06 |
| `LoginServiceTest` | 4 ✅ | AC-001..004 + BR-002 (normalización email, error indistinguible) |
| `BCryptPasswordVerifierTest` | 1 ✅ | BR-001 (hash ≠ plaintext, BCrypt) |

**Suite `sensor-registry` actual (2026-09-09): 79 unit/assert + 17 ITs = 96 verdes.**

### 2f. `FEAT-0004` (RESOLVED 2026-09-09) — edición de configuración
`PUT /api/sensores/{id}` (ADMIN-only) edita el subset de medición/alerta
(`estado` ACTIVO/MANTENIMIENTO, `histeresis`, `frecuenciaReporteSegundos`, 3
rangos) validando las mismas invariantes que el alta; identidad inmutable
(`id/codigo/nombre/tipo/ubicación/unidad/fecha`); `INACTIVO` rechazado (400 —
baja lógica es FEAT-0005); aplica solo a lecturas futuras (sin reproceso); sin
evento en v1 (decisión HO-Gate). Jackson estricto (`fail-on-unknown-properties`)
+ handlers `HttpMessageNotReadable`/`ServerWebInputException` →
`SENSOR_INVALID_REQUEST` para campos no editables.

| Suite | Tests | Cubre |
|---|---|---|
| `FEAT0004MainFlowIT` | 9 ✅ | Main Flow + AC-001..009 e2e (200/403/401/404/400s, body incompleto/inmutable) |
| `UpdateSensorServiceTest` | 7 ✅ | BR-002/006/007/008, AC-001/004/006/007/008 (fakes) |

**Suite `sensor-registry` actual (2026-09-09): 86 unit/assert + 26 ITs = 112 verdes.**

### 2g. `FEAT-0005` (RESOLVED 2026-09-09) — baja lógica
`DELETE /api/sensores/{id}` (ADMIN): estado → `INACTIVO` vía UPDATE (nunca DELETE
físico; BR-004); re-baja idempotente 204 (BR-005); el sensor sigue visible en
listado/detalle (BR-006, decisiones FEAT-0002/0003); reactivación vía PUT FEAT-0004
(BR-007/AC-008). Decisiones HO-Gate: verbo DELETE, 204 idempotente, visibilidad
preservada.

> Fix infra durante el Loop (audit FEAT-0005): los guards por Reactor Context eran
> inestables (re-suscripciones del handler Mono sin contexto). Ahora `RolFilter`
> resuelve el rol una vez y lo guarda como atributo del exchange; los guards de
> controller leen el atributo (`RolGuard.requireAdmin/requireReader(exchange,…)`).
> Overloads por Context se conservan para unit tests.

| Suite | Tests | Cubre |
|---|---|---|
| `FEAT0005MainFlowIT` | 8 ✅ | Main Flow + AC-001..008 e2e (204+INACTIVO, 403/401/404/400, re-baja, visible en listado, reactivación) |
| `DeactivateSensorServiceTest` | 3 ✅ | BR-002/004/005 (404, UPDATE INACTIVO, no-op idempotente) |

**Suite `sensor-registry` actual (2026-09-09): 89 unit/assert + 34 ITs = 123 verdes.**
### 2j. `FEAT-0007` (RESOLVED 2026-09-13) — `api-gateway`: entrada única, rate limiting y correlación

Origen: `docs/FIX-0005-gateway-rate-limiting.md` (backlog), revisado en Gate y promocionado a
`contracts/FEAT-0007.md`: agrega un **componente nuevo**, así que va por la serie FEAT y no por
FIX (además `contracts/FIX-0005` ya estaba usado por el particionamiento).

- **Problema**: los servicios publicaban puertos directos al host, sin capa intermedia: sin
  protección de fuerza bruta en `POST /api/auth/login`, sin límite en los endpoints de lectura y
  sin un lugar donde generar correlación.
- **Solución**: gateway propio en **WebFlux** (sin dependencias nuevas) que enruta REST y
  WebSocket por **patrón más específico** (`/api/sensores/{id}/lecturas` → query-api, el resto de
  `/api/sensores/**` → registry), aplica **rate limiting token bucket** por clase + IP del peer
  (`login` 10/60 s · `lectura` 120/60 s · `default` 300/60 s · `ws` 30/60 s, todos configurables),
  devuelve `429` con `Retry-After` y body `{"code","message"}`, garantiza `X-Correlation-Id`
  (propagado o generado + log de acceso) y mapea fallas de upstream a `502`/`504` (nunca `500`).
- **Operación**: sólo el gateway publica puerto (:8084); el debug directo usa
  `docker-compose.dev.yml`. Rutas, límites y `timeout-ms` en `application.yml` con overrides por
  entorno (`GATEWAY_PORT`, `GATEWAY_RL_EXPIRACION`, `GATEWAY_CONFIAR_XFF`), documentados en
  `docs/RUNBOOK.md` §4.
- **Fuera de alcance (decidido en HO-Gate)**: autenticación de WebSocket (los WS de
  `alerting`/`query-api` no validan token: brecha documentada en ADR-0016), auth en el gateway
  (sigue en `sensor-registry`), rate limiting distribuido con Redis, TLS/CORS y circuit breaker
  (es el ítem `docs/FIX-0007-circuit-breaker.md`).
- **Bugs reales corregidos durante el Loop**: el `RouterFunction` resuelve por primer match (había
  que ordenar por especificidad); `exchangeToMono` libera la respuesta al completar (el body
  proxeado salía vacío); y la estrategia de upgrade WS auto-suscribe el handler (el túnel se
  cerraba tras el handshake).
- **Evidencia**: `contracts/FEAT-0007.md` (33/33 ✅), auditoría
  `.sdd/runs/FEAT-0007-20260913-134500.md`, ADR-0016, suites **31 unit + 16 IT = 47**.

### 2i. `FIX-0005` (RESOLVED 2026-09-11) — particionamiento del consumo por `sensorId`

Origen: `docs/FIX-0006-particionamiento-consumers.md` (backlog), refinado en Gate y
promocionado a `contracts/FIX-0005.md` (la serie de `contracts/` es la autoritativa).

- **Problema real detectado en Gate**: el doc original pedía "orden relativo por sensor" y
  usaba un campo `sequence` que **no existe** en el payload. Peor: escalar
  `ingestion-service` con una cola única reparte el estado en memoria `ultimaSeveridad`
  entre procesos, con lo que **se pierden transiciones de severidad reales** (alertas que
  nunca se emiten).
- **Solución**: particionamiento en el broker con exchange `x-consistent-hash`
  (`sensor.lecturas.part`), binding exchange-to-exchange (`lectura.#`) desde el topic
  `sensor.lecturas` y `N` colas `queue.sensor.lecturas.p{i}` con peso `"1"`; el publisher
  **no** cambia. Resultado: **afinidad sensor → partición → instancia**, un consumer por
  partición con `qos=1` y procesamiento secuencial (`concatMap`).
- **Configuración**: `ingestion.particiones.total` (default **4**) / `asignadas` /
  `exchange` / `patron`, por YAML o entorno (`INGESTION_PARTICIONES_*`). Cada instancia
  declara la topología completa y consume solo sus particiones.
- **Operación**: requiere habilitar `rabbitmq_consistent_hash_exchange` en el broker
  (`infra/rabbitmq/enabled_plugins`, montado por compose) y en los ITs; si la topología no
  se puede declarar la instancia registra ERROR y **no** consume (fail-fast); WARN si hay
  particiones sin consumer (sus mensajes quedan en cola, no se pierden); cambiar `total`
  exige reinicio coordinado y la cola anterior `queue.sensor.lecturas` se retira con drenaje
  documentado en `docs/RUNBOOK.md` §5.
- **Fuera de alcance (decidido en HO-Gate)**: persistir `ultimaSeveridad` fuera del proceso
  (la afinidad lo hace correcto) y el mismo problema de afinidad al escalar
  `alerting-service` (histéresis en memoria) → contratos futuros.
- **Evidencia**: `contracts/FIX-0005.md` (23/23 ✅), auditoría
  `.sdd/runs/FIX-0005-20260911-201500.md`, ADR-0015, suites `45 unit + 21 IT` en
  `ingestion-service` (incluye los 3 ITs previos revalidados con el plugin habilitado).

### 2h. `FIX-0002` (RESOLVED 2026-09-10) — parser de `sensor.alertas` perdía datos
`AlertasRabbitConsumer.parseEvento` construía el `EventoAlerta` con
`valorLectura = BigDecimal.ONE` y `cruceHisteresis = false` **hardcodeados**
(parser incompleto de FEAT-0012). Fix: parseo fiel de ambos campos del payload
FEAT-0011 (escala preservada; `cruceHisteresis` ausente → `false` por
compatibilidad) manteniendo la validación (payload inválido → DLQ).

| Suite | Tests | Cubre |
|---|---|---|
| `FIX0002AcTest` | 3 ✅ | AC-001 (valor 7.77 real), AC-002 (cruce fiel/ausente), AC-003 (rechazo → DLQ) |
| `FIX0002MainFlowIT` | 1 ✅ | Reproducción e2e con RabbitMQ real (payload real → notificación; inválido → DLQ) |

Regresión `alerting-service`: 10 unit + 2 ITs = 12 verdes. Audit:
`.sdd/runs/FIX-0002-20260910-164923.md`.

---

## 3. Pendiente — tabla detallada para continuar

Cada fila es candidato a un nuevo Contract (`/sdd-feature` salvo las marcadas `/sdd-fix`).

### 3a. `sensor-registry` — completar el servicio
✅ CRUD de sensores completo (POST/GET listado/GET detalle/PUT/DELETE baja lógica)
+ auth JWT real (FEAT-0001..0006, FIX-0001). Sin pendientes en este servicio.

### 3b. Servicios no iniciados
| ID sugerido | Servicio | Alcance (qué cubriría el Contract) | Fuente spec |
|---|---|---|---|
| `FEAT-0013` | `query-api` | `GET /api/sensores/{id}/lecturas?desde&hasta` (histórico keyset), `GET .../actual` (desde Redis última lectura), `WS /ws/sensores/{id}` tiempo real | §3, §7, §9.2/§9.4 |

### 3c. Transversal / infra
| Ítem | Qué falta |
|---|---|
| `docker-compose.yml` | ✅ CREADO (2026-09-09): 5 servicios + Postgres + TimescaleDB + Redis + RabbitMQ; `docker compose up` |
| `application.yml` de cada servicio | ✅ reintentos/DLQ configurables (`messaging.retry.*`, `messaging.dead-letter.exchange`) — stack exige "nunca hardcodeados" |
| Datos semilla | 6 sensores reales Paraná+Salado/Santa Fe (spec §9.5) |
| Manifiestos K8s | solo documentación futura (v1 no se implementan) |
| Parent Maven multi-módulo | ✅ CREADO (`services/pom.xml`, 5 módulos) + Dockerfiles por servicio |

---

## 4. Cómo continuar (siguiente sesión)

1. Arrancar el orquestador (`CLAUDE.md` rige). Independiente del Work Item,
   leer `contracts/[ID].md`; si no existe → `/sdd-feature` (o `/sdd-fix`) para crearlo.
2. **Recomendación de orden lógico de dependencias:**
   `FEAT-0011` (ingestion: consume `sensor.lecturas`, persiste TimescaleDB, evalúa
   severidad) → `FEAT-0012` (alerting) → `FEAT-0013` (query-api) → frontend →
   docker-compose. Productor (`FEAT-0010`) y CRUD/auth (`FEAT-0001..0006`) ya están
   cerrados.
3. Frame SDD-GL: actualizado a v0.3.0 (protocol EXPRESS/STRICT, Glass Box en
   `.sdd/runs/`, AGENTS.md + `.agents/skills`, presets, mcp, CHANGELOG). Los agentes
   `.claude/agents/*` usan `model: sonnet` (v0.3.0 del repo).
4. Build del `sensor-registry` (mvn no está en PATH, no hay `mvnw`):

```bash
cd "D:/ProyectosDual/WR-Sensor/services/sensor-registry" && \
JAVA_HOME="/c/Program Files/Amazon Corretto/jdk25.0.3_9" \
  "/c/Users/Gerardo/.m2/wrapper/dists/apache-maven-3.9.9-bin/33b4b2b4/apache-maven-3.9.9/bin/mvn" \
  -o test
```

> Los ITs necesitan Docker Desktop corriendo (Testcontainers: postgres + rabbitmq).

### 3d. Backlog de mejoras EN ESPERA (sin ejecutar)
Documentos `docs/FIX-0002-schema-versionado-lecturas.md`, `FIX-0003-outbox-idempotencia-ingestion.md`,
`FIX-0004-validacion-rango-fisico.md`, `FIX-0005-gateway-rate-limiting.md`,
`FIX-0006-particionamiento-consumers.md`, `FIX-0007-circuit-breaker.md` (todos DRAFT/GATE).
**Estado: aguardando la orden de ejecución del humano** (no se procesan todavía).

> **Mapeo de IDs (serie autoritativa = `contracts/`):**
> - `docs/FIX-0006-particionamiento-consumers.md` → **PROMOCIONADO y RESUELTO como
>   `contracts/FIX-0005.md`** (Gate EXPRESS 2026-09-11, Loop completado 2026-09-11, 23/23 ✅;
>   Ambiguity Log resuelto: consistent-hash exchange + binding e2e, 4 particiones, estado de
>   severidad fuera de alcance).
> - `docs/FIX-0005-gateway-rate-limiting.md` → **PROMOCIONADO y RESUELTO como
>   `contracts/FEAT-0007.md`** (Gate EXPRESS 2026-09-11, Loop completado 2026-09-13, 33/33 ✅).
>   Cambia de serie porque agrega un componente nuevo (`api-gateway`); decisiones humanas:
>   gateway propio en WebFlux, cerrar los puertos de servicios detrás del gateway con
>   `docker-compose.dev.yml` para debug, límites moderados (login 10/60 s, lectura 120/60 s,
>   default 300/60 s, WS 30/60 s) y auth de WebSocket fuera de alcance (brecha documentada).
> - Próximo ID libre tras `FIX-0005` / `FEAT-0007`: `FIX-0006` / `FEAT-0008`.

> Nota de gobernanza: existe **colisión de ID** entre `contracts/FIX-0002.md` (RESOLVED) y
> `docs/FIX-0002-schema-versionado-lecturas.md`, y entre los docs de backlog `FIX-0005..0007`
> y los IDs que irá tomando la serie de `contracts/`. Al recibir la orden de ejecución, el
> primer paso del Gate será renumerar el documento de backlog (p. ej. FIX-0007+) antes de
> crear su contract en `contracts/`.
