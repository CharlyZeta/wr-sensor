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
| `query-api` | 🟢 parcial | `services/query-api/` — histórico keyset, `/actual` (última lectura), `WS /ws/sensores/{id}` tiempo real (consume `sensor.lecturas`) + **`GET /api/sensores/resumen`** para el mapa (FEAT-0008) | `FEAT-0013`, `FEAT-0008` (RESOLVED) |
| `api-gateway` | 🟢 parcial | `services/api-gateway/` — punto de entrada único: tabla de rutas declarativa, rate limiting por clase\|IP, correlación, túnel WS + **CORS configurable y WS autenticado** (FEAT-0008) | `FEAT-0007`, `FEAT-0008` (RESOLVED) |
| Frontend (`web/`: React + TypeScript + Vite + mapa Leaflet) | 🟢 **completo** | `web/` — SPA del operador: sesión con JWT, mapa con el resumen, detalle en vivo por WS + serie de 24 h, feed de alertas, CRUD (ADMIN) y panel de demo. Servido por el gateway (mismo origen). 63 tests unit + 9 e2e | `FEAT-0009`, `FEAT-0014`, `FEAT-0015`, `FEAT-0016` (RESOLVED) |
| Infra local (Docker Compose de los 6 servicios) | 🟢 parcial | `docker-compose.yml` (+ `docker-compose.dev.yml`) con los 6 servicios, Postgres+TimescaleDB, RabbitMQ; sólo el gateway publica puerto | — |

**Conclusión:** el proyecto está en ~95%. **Backend completo y con entrada única**: pipeline de
datos (`data-simulator` → `ingestion-service` → `alerting-service`), capa de consulta (`query-api`) y
`api-gateway` con rate limiting, CORS, WebSocket autenticado y headers de seguridad. **La serie del
frontend está cerrada completa** (`FEAT-0009` núcleo+mapa+hosting → `FEAT-0014` detalle en vivo →
`FEAT-0015` alertas → `FEAT-0016` administración/demo), más `FIX-0008` de seguridad. Lo que queda del
alcance v1 son los agregados de TimescaleDB y el historial de alertas (Fase C).

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

### 2m. `FEAT-0008` (RESOLVED 2026-09-14) — habilitadores del frontend (CORS, WS autenticado, resumen)

Origen: análisis de requerimientos/factibilidad/alcance del frontend (2026-09-14). Único contract
con **`Gate-Mode: STRICT`**: el humano aprobó alcance, decisiones y Ambiguity Log antes del Loop
(SPA servido por el gateway, CORS acotado y configurable, auth de WS ahora, endpoint de resumen,
simulador fuera del gateway, Leaflet + Fase A para el SPA).

- **Problema**: el backend ya tenía login, CRUD, histórico, `/actual` y WS, pero tres huecos
  bloqueaban el dashboard: (a) **cero configuraciones de CORS** en el repo → un SPA en otro origen
  no podía ni mandar `Authorization`; (b) los **WS no validaban token** (brecha de ADR-0016) y el
  navegador no puede mandar headers en el upgrade; (c) el mapa necesitaba metadata + última lectura
  y sólo se podía con **N+1** requests bajo un cupo de 120/min.
- **Solución**: CORS configurable en el gateway con preflight resuelto por el gateway (`204`, sin
  cupo, sin downstream); **auth del handshake WS** con verificador HS256 propio (`?token=` o
  `Bearer`, firma + `exp` + rol, sin propagar ni loguear el token) y `401` antes del upgrade;
  **`GET /api/sensores/resumen`** en `query-api` con metadata del registry por REST (keyset, timeout
  explícito) y la última lectura de **todos** los sensores en **una** consulta
  (`DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC`), con `502 REGISTRY_UNAVAILABLE` sin
  datos parciales; ruta `query-resumen` en la tabla del gateway (clase `lectura`, patrón más
  específico).
- **Bug real encontrado por el Loop**: el primer filtro CORS rechazaba con `403` cualquier request
  con `Origin` no listado — incluidos los del **propio origen**, que el navegador manda en POST y en
  el **handshake WS**. Con la lista vacía (default de producción) el SPA servido por el gateway no
  habría podido ni loguearse ni abrir un WS. Lo destapó el IT del túnel WS de FEAT-0007 (verde antes,
  rojo después). Corregido con la regla **same-origin no es CORS** (`Host` o
  `X-Forwarded-Host`/`-Proto` detrás de un terminador TLS).
- **Regresión asumida**: el túnel WS de FEAT-0007 ahora exige token (AC-006 lo contempla); su IT
  manda un JWT válido.
- **Evidencia**: `contracts/FEAT-0008.md` (31/31 ✅), ADR-0019, suites `api-gateway` 68+29 y
  `query-api` 33+8 (414 tests verdes en total).

### 2n. `FIX-0008` (RESOLVED 2026-09-15) — endurecimiento del punto de entrada

Origen: **revisión de seguridad** previa a servir el SPA desde el gateway (delegada a un subagente con
acceso de sólo lectura). Encontró 7 hallazgos; este fix cerró los tres del gateway.

- **S1 (alto)**: `docker-compose.yml` no le pasaba `AUTH_JWT_SECRET` al `api-gateway`, así que caía al
  default de desarrollo —**público en el repo**— mientras el registry usaba el real. Como el gateway
  valida el JWT del handshake WS, en un despliegue con el secreto rotado **cualquiera podía forjar un
  token `ADMIN`/`VIEWER`** y abrir la telemetría y las alertas.
- **S3**: **cero headers de seguridad** en todo el repositorio, justo cuando el token vive en
  `sessionStorage` (sin segunda línea de defensa frente a XSS/clickjacking).
- **S2/A6**: el catch-all del router impedía servir el SPA, y el fallback ingenuo habría roto el
  contrato de la API (devolviendo HTML a un asset inexistente).
- **Solución**: secreto propagado + **fail-fast** fuera de perfiles de desarrollo; `FiltroSeguridad`
  con CSP (`script-src 'self'`, sin `unsafe-inline`/`unsafe-eval`), `nosniff`, `Referrer-Policy`,
  `X-Frame-Options`, `Permissions-Policy`, CORP y HSTS condicional, **sin permitir que el downstream
  los pise**; `ServidorSpa` con resolución contenida (rechaza `..`/`%2e%2e`/rutas absolutas), lista
  explícita de rutas de cliente, `404` para assets inexistentes, prefijos reservados `/api` y `/ws`, y
  caché `no-store`/inmutable.
- **Evidencia**: `contracts/FIX-0008.md` (24/24 ✅), ADR-0020, informe completo en
  `.sdd/runs/FIX-0008-20260915-120000.md`, suites `api-gateway` 104 unit + 42 IT.

### 2o. `FEAT-0009..FEAT-0016` (RESOLVED 2026-09-15) — serie del frontend (SPA `web/`)

Origen: el humano pidió dividir el frontend en **partes lógicas funcionales** (aprobado 2026-09-14) y
luego ejecutarlas ("termina las tareas pendientes… código, test, seguridad, documentación"). Los
cuatro contracts son **sólo frontend**: no cambiaron el backend (el contrato de los servicios cerró en
`FEAT-0008`).

| Parte | Contract | Alcance | Criterios | Evidencia |
|---|---|---|---|---|
| 1 | `FEAT-0009` | sesión (`sessionStorage` + expiración + cierre atómico), shell/guard, cliente API (errores por `code`, `429`/`Retry-After`, correlación), **mapa Leaflet** con `GET /api/sensores/resumen`, y **hosting del SPA desde el gateway** | 32/32 ✅ | ADR-0021 · `web/` 22 tests · `FEAT0009HostingIT` 5 + `FEAT0009DocsTest` 5 |
| 2 | `FEAT-0014` | detalle en vivo: `WS /ws/sensores/{id}?token=` (una conexión por vista, backoff con jitter, dedupe) + serie de 24 h con histórico keyset + tabla accesible | 29/29 ✅ | `web/` 15 tests · `FEAT0014DocsTest` 5 |
| 3 | `FEAT-0015` | alertas en vivo: `WS /ws/alertas?token=` (una conexión por pestaña), feed con dedupe y tope, contador de críticas no leídas y **refresco del mapa por ráfaga** | 29/29 ✅ | `web/` 14 tests · `FEAT0015DocsTest` 5 |
| 4 | `FEAT-0016` | administración (CRUD ADMIN con errores por `code`, baja lógica confirmada) y panel de demo configurable | 31/31 ✅ | `web/` 12 tests · `FEAT0016DocsTest` 5 |

- **Stack**: Vite 7 + React 19 + TypeScript estricto en `web/`; CSS propio con tokens (una única
  fuente de verdad de la severidad); react-router + hooks sobre `fetch`; Vitest + RTL + MSW + **9 e2e
  de Playwright** contra el build real; el dev usa proxy de Vite (sin CORS, mismo origen que
  producción).
- **Seguridad del SPA**: token sólo en `sessionStorage` (nunca `localStorage`, cookies ni URL), datos
  del backend tratados como no confiables (UUID, coordenadas y severidad validados; todo como texto;
  popups de Leaflet con **nodos del DOM**, no HTML), ESLint **prohíbe** `dangerouslySetInnerHTML`/
  `innerHTML`/`console.*`, y una sola conexión por pestaña en el feed de alertas.
- **Bugs reales encontrados por los Loops**: `/ws/**` sin declarar devolvía el índice del SPA (200
  HTML); un fallo del histórico borraba la metadata del sensor en el detalle (corregido con
  `Promise.allSettled`); dos tests intermitentes por temporizadores de jsdom compartidos (doble de
  WebSocket unificado + suites serializadas) y una aserción de `401 → logout` que esperaba el mapa
  (que con un 401 nunca aparece).
- **Evidencia**: los 4 contracts RESOLVED, ADR-0021, auditorías en
  `.sdd/runs/FEAT-0009-*.md` … `FEAT-0016-*.md`, y `web/` 63 unit + 9 e2e verdes.

### 2l. `FIX-0007` (RESOLVED 2026-09-14) — resiliencia del lookup de config de sensores

Origen: `docs/FIX-0007-circuit-breaker.md` (backlog), promocionado a `contracts/FIX-0007.md` con
el alcance ampliado de "circuit breaker" a **resiliencia del lookup de config**.

- **Problema**: el adapter a `sensor-registry` no tenía timeouts, no había circuit breaker y la
  cache era un `ConcurrentHashMap` **sin TTL**. Eso escondía dos bugs de negocio: (a) un sensor
  desactivado o con bandas nuevas seguía ingiriéndose con la config vieja para siempre, y (b) el
  token JWT cacheado **nunca se refrescaba** (al expirar, 1 h, todo sensor no cacheado fallaba de
  forma permanente hasta reiniciar).
- **Solución**: timeouts de respuesta/conexión explícitos, **circuit breaker propio en el dominio**
  (CERRADO/ABIERTO/SEMIABIERTO, reloj inyectado, cero dependencias), **cache con TTL +
  last-known-good** (copia vencida se usa antes que perder lecturas), refresco del token ante
  `401` (invalidar + login + un reintento), motivo de DLQ **`REGISTRY_UNAVAILABLE`** y endpoint
  interno `GET /api/ingestion/resiliencia` (sin `actuator`).
- **Fuera de alcance (decidido en HO-Gate)**: reintentos con backoff (la config
  `retry-max-attempts` queda documentada como sin uso), cache en Redis, actuator/Micrometer,
  invalidación evento-driven y circuit breaker en otras integraciones.
- **Evidencia**: `contracts/FIX-0007.md` (30/30 ✅), auditoría
  `.sdd/runs/FIX-0007-20260913-153000.md`, ADR-0018, suites `ingestion-service` 88+33 (los 5 ITs
  previos revalidados con el adapter reescrito).

### 2k. `FIX-0006` (RESOLVED 2026-09-13) — versionado del schema de `sensor.lecturas`

Origen: `docs/FIX-0002-schema-versionado-lecturas.md` (backlog), promocionado y **renumerado a
`contracts/FIX-0006.md`** (`contracts/FIX-0002` es el parser de `sensor.alertas`).

- **Problema**: el evento no tenía versión de schema, el publisher no emitía `eventId` ni marca de
  calidad y **los dos consumers lo parseaban con expresiones regulares** → contrato de mensajería
  imposible de evolucionar con seguridad (un payload válido con espacios se rechazaba).
- **Solución**: **payload v1** (`schemaVersion` configurable, `eventId`, `sequence` por sensor,
  `calidad` informativa `{estado, confianza, codigosAnomalias}`), parseo en ambos consumers con
  **DTO + Jackson 3** tolerante a campos desconocidos, `sequence` **persistida** en la hypertable
  con detección de huecos por WARN (el reinicio del publisher se registra como INFO) y `query-api`
  exponiendo la `calidad` del evento en el WS.
- **Política de versiones**: **tolerancia hacia adelante** (una mayor desconocida se procesa con
  WARN una vez por versión); `ingestion.schema.tolerar-versiones-mayores: false` la endurece a DLQ
  `SCHEMA_UNSUPPORTED`. El payload plano legado sigue válido con ventana **indefinida y medida**
  (INFO con contador), para cerrarla con datos y no con una fecha arbitraria.
- **Fuera de alcance (decidido en HO-Gate)**: metadata de dispositivo (hardware inexistente),
  `calidad.estado = SOSPECHOSA`, renombrar `timestamp` a `timestampUtc`, versionar
  `sensor.alertas` y retirar el payload legado.
- **Evidencia**: `contracts/FIX-0006.md` (31/31 ✅), auditoría
  `.sdd/runs/FIX-0006-20260913-150000.md`, ADR-0017, suites `data-simulator` 19+1,
  `ingestion-service` 68+28, `query-api` 11+1 y regresión de `alerting-service` 10+2 (el contrato
  de alertas no cambió).

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

### 3b. Servicios / partes no iniciados
| ID | Servicio | Alcance del Contract | Estado |
|---|---|---|---|
| `FEAT-0013` | `query-api` | histórico keyset, `/actual`, `WS /ws/sensores/{id}` | ✅ RESOLVED (2026-09-09) |
| `FEAT-0009` | `web/` (SPA) | núcleo: sesión, shell, cliente API, **mapa** (`GET /api/sensores/resumen`) y **hosting desde el gateway** (32 criterios) | ✅ RESOLVED (2026-09-15) |
| `FEAT-0014` | `web/` (SPA) | detalle en vivo: `WS /ws/sensores/{id}?token=` + serie de 24 h (histórico keyset, 29 criterios) | ✅ RESOLVED (2026-09-15) |
| `FEAT-0015` | `web/` (SPA) | alertas en vivo: `WS /ws/alertas?token=`, feed, contador y refresco del mapa con debounce (29 criterios) | ✅ RESOLVED (2026-09-15) |
| `FEAT-0016` | `web/` (SPA) | administración (CRUD ADMIN con errores por `code`) y panel de demo del simulador (31 criterios) | ✅ RESOLVED (2026-09-15) |
| — | `web/` (SPA) | Fase C: agregados/continuous aggregates, export CSV, historial de alertas consultable | sin contract (roadmap) |

### 3c. Transversal / infra
| Ítem | Qué falta |
|---|---|
| `docker-compose.yml` | ✅ 6 servicios (incluye `api-gateway`) + Postgres + TimescaleDB + Redis + RabbitMQ; `docker compose up`. Sólo el gateway publica puerto; `docker-compose.dev.yml` para debug. ✅ La imagen del gateway **incluye el SPA construido** (stage de Node que corre `npm ci` + `npm run build` y lo monta en `/app/static/`, FEAT-0009 BR-010) |
| `application.yml` de cada servicio | ✅ reintentos/DLQ configurables (`messaging.retry.*`, `messaging.dead-letter.exchange`) — stack exige "nunca hardcodeados"; el SPA tiene sus `VITE_*` documentadas en `web/.env.example` |
| Datos semilla | 6 sensores reales Paraná+Salado/Santa Fe (spec §9.5) |
| Manifiestos K8s | solo documentación futura (v1 no se implementan) |
| Parent Maven multi-módulo | ✅ CREADO (`services/pom.xml`, 6 módulos) + Dockerfiles por servicio. El SPA **no** es módulo Maven (`web/` con su propio `package.json`, build por npm; ver `FEAT-0009` BR-010) |
| Suite del SPA | ✅ `web/`: 63 tests (Vitest + RTL + MSW) + 9 e2e (Playwright contra el build real, sin Docker). Se corren con `npm test` / `npm run e2e` |

---

## 4. Cómo continuar (siguiente sesión)

1. Arrancar el orquestador (`CLAUDE.md` rige). **No hay ningún contract abierto**: para trabajo nuevo
   se crea con `/sdd-feature` (o `/sdd-fix`) y **el humano** aprueba el Gate antes del Loop.
2. **Serie del frontend: cerrada completa** (2026-09-15) — `FEAT-0009` (núcleo + mapa + hosting) →
   `FEAT-0014` (detalle en vivo) → `FEAT-0015` (alertas) → `FEAT-0016` (administración y demo), más
   `FIX-0008` (seguridad del punto de entrada). Los candidatos siguientes son la **Fase C**
   (agregados/continuous aggregates, export CSV, historial de alertas) y los escenarios e2e ampliados.
   Para ejecutarlos: crearlos con `/sdd-feature` y aprobar su Gate (`Status: APPROVED` + `Mode: LOOP`).
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
Documentos `docs/FIX-0002-schema-versionado-lecturas.md`,
`FIX-0003-outbox-idempotencia-ingestion.md`, `FIX-0004-validacion-rango-fisico.md`,
`FIX-0005-gateway-rate-limiting.md`, `FIX-0006-particionamiento-consumers.md`,
`FIX-0007-circuit-breaker.md`: **todos promocionados y resueltos** como contracts
(FIX-0003..FIX-0007, FEAT-0007). No queda backlog de fixes en espera.

> **Mapeo de IDs (serie autoritativa = `contracts/`):**
> - `docs/FIX-0003-outbox-idempotencia-ingestion.md` → **PROMOCIONADO Y RESUELTO como
>   `contracts/FIX-0003.md`** (19/19 ✅) — mismo tema y mismo número (coincidencia).
> - `docs/FIX-0004-validacion-rango-fisico.md` → **PROMOCIONADO Y RESUELTO como
>   `contracts/FIX-0004.md`** (16/16 ✅) — mismo tema y mismo número (coincidencia).
> - `docs/FIX-0006-particionamiento-consumers.md` → **PROMOCIONADO Y RESUELTO como
>   `contracts/FIX-0005.md`** (Gate EXPRESS 2026-09-11, Loop completado 2026-09-11, 23/23 ✅;
>   Ambiguity Log resuelto: consistent-hash exchange + binding e2e, 4 particiones, estado de
>   severidad fuera de alcance).
> - `docs/FIX-0005-gateway-rate-limiting.md` → **PROMOCIONADO Y RESUELTO como
>   `contracts/FEAT-0007.md`** (Gate EXPRESS 2026-09-11, Loop completado 2026-09-13, 33/33 ✅).
>   Cambia de serie porque agrega un componente nuevo (`api-gateway`); decisiones humanas:
>   gateway propio en WebFlux, cerrar los puertos de servicios detrás del gateway con
>   `docker-compose.dev.yml` para debug, límites moderados (login 10/60 s, lectura 120/60 s,
>   default 300/60 s, WS 30/60 s) y auth de WebSocket fuera de alcance (brecha documentada).
> - **Pendientes reales del backlog**: ninguno. `docs/FIX-0007-circuit-breaker.md` →
>   **PROMOCIONADO como `contracts/FIX-0007.md`** (RESOLVED 2026-09-14).
> - **Serie del frontend: CERRADA (2026-09-15)**, abierta el 2026-09-14 y dividida en 4 partes por
>   capacidad funcional (división aprobada por el humano): `contracts/FEAT-0009.md` (SPA núcleo:
>   sesión, shell, mapa y hosting desde el gateway, STRICT, 32 criterios ✅), `contracts/FEAT-0014.md`
>   (detalle en vivo: WS de lecturas + serie de 24 h, EXPRESS, 29 ✅), `contracts/FEAT-0015.md` (alertas
>   en vivo: feed + refresco del mapa, EXPRESS, 29 ✅) y `contracts/FEAT-0016.md` (administración y demo:
>   CRUD ADMIN + panel del simulador, STRICT, 31 ✅). **Ninguna cambió el backend.** El HO-Gate se dio
>   por la orden de ejecución del humano; los resultados esperan su validación (ver `docs/ESTADO-SDD.md`).
>   **No queda ningún work item abierto.**
> - **Handoff**: `docs/CONTINUIDAD.md` resume estado, proceso, comandos, entorno y trampas para
>   retomar el proyecto sin contexto previo.
> - `docs/FIX-0002-schema-versionado-lecturas.md` → **PROMOCIONADO como `contracts/FIX-0006.md`**
>   (Gate EXPRESS 2026-09-13, pendiente HO-Gate): renumerado porque `contracts/FIX-0002` es el
>   parser de alertas. Decisiones humanas: consumers migrados a DTO + Jackson, tolerancia hacia
>   adelante ante versión desconocida (configurable), `sequence` persistida con detección de
>   huecos, `calidad` informativa sin `SOSPECHOSA` y sin metadata de dispositivo.

> Nota de gobernanza: existe **colisión de ID** entre `contracts/FIX-0002.md` (RESOLVED) y
> `docs/FIX-0002-schema-versionado-lecturas.md`, y entre los docs de backlog `FIX-0005..0007`
> y los IDs que irá tomando la serie de `contracts/`. Al recibir la orden de ejecución, el
> primer paso del Gate será renumerar el documento de backlog (p. ej. FIX-0007+) antes de
> crear su contract en `contracts/`.
