# WR-Sensor — Registro de Decisiones (ADR-español)

Decisiones tomadas en el ciclo SDD-GL (Gate humano). Fuente primaria: Ambiguity Log
de cada Contract y audits `.sdd/runs/`.

## ADR-0001 · Lectura de sensores para roles {ADMIN, VIEWER} (FEAT-0002)
**Contexto:** spec §7 rotulaba GET como "(admin)". **Decisión (HO-Gate 2026-08-13/14):**
la lectura (listado y detalle) se permite a `ADMIN` y `VIEWER`; la escritura
(POST/PUT/DELETE) sigue siendo ADMIN-only. Se agregó el sentinel `Rol.OTHER` para
distinguir 401 (sin auth) de 403 (rol desconocido).

## ADR-0002 · Timestamps naive-UTC (fix keyset FEAT-0002)
**Contexto:** el desempate del cursor keyset fallaba entre filas con igual
`fecha_instalacion` (perdía la fila empatada). **Causa raíz:** `java.sql.Timestamp`
convertido por Spring R2DBC a wall-clock local (UTC-3) en cada cruce
escritura/lectura/predicado. **Decisión:** bindear siempre
`LocalDateTime.ofInstant(ts, UTC)` en write/read/predicado (columna `TIMESTAMP`
naive-UTC). Verificado por `FEAT0002MainFlowIT`.

## ADR-0003 · Error de id malformado → `SENSOR_INVALID_ID` (FEAT-0003)
**Decisión (HO-Gate 2026-09-09):** nuevo code `SENSOR_INVALID_ID` (400), consistente
con la familia `SENSOR_INVALID_LIMIT/CURSOR`, en vez de `SENSOR_INVALID_REQUEST`.

## ADR-0004 · Visibilidad de sensores INACTIVO (FEAT-0003/0005)
**Decisión:** el detalle y el listado **no filtran por estado** (INACTIVO visible);
la baja lógica (FEAT-0005) no oculta en v1. Reactivación vía `PUT` (FEAT-0004).

## ADR-0005 · Alcance del PUT y exclusión de INACTIVO (FEAT-0004)
**Decisión (HO-Gate 2026-09-09):** PUT edita solo la configuración
(estado/histéresis/frecuencia/rangos); identidad inmutable; `INACTIVO` rechazado en
PUT (400 `SENSOR_INVALID_ESTADO`) porque la baja es FEAT-0005; sin evento en v1;
body = subset completo (Jackson estricto `fail-on-unknown-properties` →
`SENSOR_INVALID_REQUEST`).

## ADR-0006 · Guards por atributo de exchange (fix FEAT-0005, transversal)
**Contexto:** los guards que leían el rol del Reactor Context fallaban de forma
intermitente en `DELETE` (el handler se re-suscribe sin contexto; rol=null → 401
espurio). **Decisión:** `RolFilter` resuelve el token **una vez por request** y lo
guarda como **atributo del exchange** (`RolGuard.ATTR_ROL`); los guards de controller
reciben el exchange (`requireAdmin/requireReader(exchange, …)`). Se conservan
overloads por Context para unit tests. Aplicado a los 5 endpoints protegidos.

## ADR-0007 · JWT HS256 con JDK estándar (FEAT-0006)
**Contexto:** el repo local Maven offline no tiene `jjwt` ni dependencias completas
de nimbus. **Decisión:** implementar emisión/verificación HS256 con `javax.crypto` +
Base64url + parseo JSON manual (claims `sub/rol/iat/exp`); sin dependencias nuevas.
Desviación documentada de las Notas del Contract (indicativas). Expiración 1 h
configurable; `AUTH_JWT_SECRET`; seed dev de usuarios (BCrypt).

## ADR-0008 · Migración de ITs a JWT real (FEAT-0006)
**Decisión (HO-Gate):** los ITs de FEAT-0001..0003 dejaron el header literal
`Bearer ADMIN` y obtienen tokens reales vía `/api/auth/login` (helper `TestTokens`);
roles ajenos se mintean con la firma dev.

## ADR-0009 · data-simulator (FEAT-0010)
**Decisiones (HO-Gate 2026-09-09):** sensores por **config local** (6 seeds §9.5);
control **sin auth** en v1; **pom standalone** (multi-módulo se arma en infra);
frecuencia default 30 s configurable; payload `Lectura` formal hacia
`sensor.lecturas` (BR-002) sin `severidad`.

## ADR-0010 · ingestion-service (FEAT-0011)
**Decisiones (HO-Gate 2026-09-09):** config del sensor vía **REST a sensor-registry**
(login VIEWER + cache en memoria); **AlertaEvento simple por cambio de severidad**
(histéresis de degradación = FEAT-0012); última severidad en memoria;
**TimescaleDB real** (hypertable `lectura`, `create_hypertable` al arranque);
DLQ/DLX con `x-rechazo`.

## ADR-0011 · Histéresis por debounce temporal (FEAT-0012)
**Contexto:** FEAT-0011 emite solo eventos de cambio, no cada lectura; la "permanencia
en banda" de la spec §5 no puede medirse con valores continuos acá. **Decisión
(HO-Gate):** bajada = candidata que se confirma si no llega una re-subida dentro de la
ventana `alerting.histeresis-segundos` (configurable, default 60); subida = inmediata.
Entrega por **WebSocket `/ws/alertas`** (push JSON confirmado). Documentada como
aproximación temporal sobre el stream de cambios.

## ADR-0012 · Proceso (notas de gobernanza)
- Los Loops de FEAT-0002..0012 se ejecutaron por el orquestador con bookkeeping del
  protocolo (Completion Map/audit) ante delegaciones de subagentes estancadas
  (verificado con sonda que el entorno sí las soporta). Documentado en los audits.
- Ajustes menores de spec durante Loops (AC-009 FEAT-0004) quedaron anotados en los
  Contracts y audits.

## ADR-0013 · Outbox + idempotencia en ingestion (FIX-0003)
**Contexto:** el consumo persistía y publicaba en línea (sin transacción): redelivery duplicaba
lecturas y una caída entre commit y publicación perdía la alerta. **Decisión (HO-Gate
2026-09-10):** clave de idempotencia `eventId`-o-natural `(sensorId, timestamp)` para no
depender del backlog de schema; transacción única lectura + dedupe + outbox
(`R2dbcTransactionManager`/`TransactionalOperator`); **poller reactivo** (sin CDC/Kafka) con
claim `FOR UPDATE SKIP LOCKED`, orden explícito por `id`, backoff configurable,
`FALLIDO + ultimo_error` al agotar y purga por retención (90 días). Garantía declarada:
**at-least-once**; el dedupe en `alerting-service` queda como contrato futuro.

## ADR-0014 · Rango físico y calidad del dato (FIX-0004)
**Contexto:** cualquier valor numérico se persistía y podía disparar alertas (alturas
negativas o imposibles). **Decisión (HO-Gate 2026-09-11):** rangos físicos **configurados en
ingestion-service** (global por unidad + override por sensor), lectura fuera de rango o
marcada como `ERROR_SENSOR` por el emisor → se persiste con `calidad = ERROR_SENSOR` y
`severidad NULL` (columna nullable), **sin** evaluar bandas ni encolar alerta, y sin alterar
la última severidad conocida; `query-api` expone `calidad` y tolera severidad nula.

## ADR-0015 · Particionamiento del consumo por `sensorId` con afinidad sensor→instancia (FIX-0005)
**Contexto:** el consumo de `sensor.lecturas` era una cola única con un consumer lógico.
Escalar `ingestion-service` convertía a las instancias en competing consumers de la misma
cola, lo que (a) rompía el orden relativo por sensor y (b) repartía entre procesos el estado
en memoria `ultimaSeveridad`, **perdiendo transiciones de severidad reales** (alertas que
nunca se emiten). El doc de backlog (`docs/FIX-0006-...`) pedía "orden por sensorId" pero
citaba un campo `sequence` inexistente en el payload y no contemplaba ese estado.
**Decisión (HO-Gate 2026-09-11):** el particionamiento se resuelve **en el broker** con un
exchange **`x-consistent-hash`** (`sensor.lecturas.part`) alimentado por un binding
**exchange-to-exchange** (`lectura.#`) desde el topic `sensor.lecturas`, y `N` colas
`queue.sensor.lecturas.p{i}` bindeadas con peso `"1"`. Consecuencia buscada: **afinidad
sensor → partición → instancia**, que hace correcto el estado en memoria sin moverlo a Redis.
El publisher (`data-simulator`) **no** cambia; `N` (default 4) y las particiones asignadas por
instancia son configuración (`ingestion.particiones.*` / `INGESTION_PARTICIONES_*`).
**Alternativas descartadas:** (B) sufijo de partición calculado por el publisher — viola el
"no tocar el publisher" y convierte cada rebalanceo en redeploy del simulador; (C)
`x-single-active-consumer` — da orden pero no escalado horizontal.
**Consecuencias:** requiere habilitar el plugin `rabbitmq_consistent_hash_exchange` (viene con
la distribución de RabbitMQ, no habilitado por defecto) en compose y en los ITs; si la
topología no se puede declarar, la instancia registra ERROR y **no** consume (nunca degrada a
consumir sin particionar); cambiar `total` exige reinicio coordinado (sin rebalanceo en
caliente) y la cola anterior `queue.sensor.lecturas` se retira con drenaje documentado. El
mismo problema de afinidad existe si se escala `alerting-service` (estado de histéresis en
memoria): queda como contrato futuro, junto con persistir la última severidad.

## ADR-0016 · api-gateway propio en WebFlux: punto de entrada único, rate limiting y correlación (FEAT-0007)
**Contexto:** los servicios expuestos publicaban puertos directos al host sin capa intermedia:
sin protección de fuerza bruta en `POST /api/auth/login`, sin límite en los endpoints de
lectura y sin un lugar donde generar correlación. El backlog lo había registrado como
`FIX-0005` (`docs/FIX-0005-gateway-rate-limiting.md`), pero agrega **un componente nuevo** →
se promocionó como `FEAT-0007` y el componente se llama `api-gateway` para no confundirlo con
el `ingestion-gateway` de dispositivos de la spec §9.3.
**Decisión (HO-Gate 2026-09-11):** gateway **propio en WebFlux** dentro del stack
(`WebClient` para el proxy, `RouterFunction` + `WebFilter` para rutas y límites,
`ReactorNettyWebSocketClient` + `HandshakeWebSocketService` para el túnel WS): cero
dependencias nuevas, control total del `429` + `Retry-After` + body de dominio y builds
offline intactos. Rate limiting **token bucket en memoria por clase + IP del peer**
(`confiar-forwarded-for: false` por default, para que no se evada por header), límites
moderados configurables (`login` 10/60 s, `lectura` 120/60 s, `default` 300/60 s, `ws`
30/60 s) y correlación `X-Correlation-Id` propagada/generada, reflejada y logueada. Sólo el
gateway publica puerto al host; el debug directo usa `docker-compose.dev.yml`.
**Alternativas descartadas:** Spring Cloud Gateway (el starter no estaba en el `.m2` y había que
resolver una release train compatible con Boot 4.1; igual exigía filtros propios para
`Retry-After`/body) y Traefik (su middleware `rateLimit` genera el `429` sin `Retry-After` ni
body configurable → incumplía el criterio y rompía la convención `{"code","message"}`).
**Consecuencias:** el prefijo `/api/sensores` está compartido entre registry y query-api, así que
el ruteo debe ser **por patrón más específico** y el `RouterFunction` ordenarse en consecuencia
(resuelve por primer match); `exchangeToMono` libera la respuesta al completar, por lo que el body
del downstream se materializa dentro del exchange (payloads JSON chicos; el streaming queda para
WS); el gateway es un SPOF con errores de dominio explícitos (`502`/`504`) y nunca `500` crudo.
**Brecha conocida y aceptada:** los WS de `alerting`/`query-api` no validan token
(`SimpleUrlHandlerMapping` sin auth); el gateway tunela el upgrade sin agregar autenticación →
work item propio junto con el frontend.

## ADR-0017 · Versionado del schema de `sensor.lecturas` y parseo con DTO + Jackson (FIX-0006)
**Contexto:** el evento no tenía versión de schema, el publisher no emitía `eventId` ni marca de
calidad y **los dos consumers que lo leen parseaban con expresiones regulares**
(`LecturasRabbitConsumer` y `LecturasRealtimeConsumer`): el contrato de mensajería no se podía
evolucionar con seguridad y hasta un payload válido con espacios (`"valor" : 5.0`) se rechazaba.
El backlog lo registraba como `FIX-0002` (colisionaba con el parser de alertas) y proponía
metadata de dispositivo inexistente, `SOSPECHOSA` sin semántica y *fail-closed* ante versiones
desconocidas.
**Decisión (HO-Gate 2026-09-13):** payload **v1** con `schemaVersion` (configuración del
publisher, default `1.0`), `eventId` (trazabilidad + clave de idempotencia explícita),
`sequence` (contador por sensor, reiniciado con la simulación) y `calidad` **informativa**
(`estado ∈ {OK, ERROR_SENSOR}`, `confianza`, `codigosAnomalias`); `timestamp` **no** se renombra
(ya es ISO-8601 con `Z`, o sea offset explícito). Los consumers migran a **DTO + Jackson**
tolerante a propiedades desconocidas (Jackson 3 ya estaba en el classpath transitivo, sin
dependencias nuevas). Política de versiones: **tolerancia hacia adelante** — legado (`0.0`
implícito) y `1.x` se procesan, una **mayor desconocida se procesa con WARN una vez por versión**
(un publisher más nuevo no debe tumbar la ingesta) y el rechazo queda para versiones
malformadas o campos requeridos ausentes; `ingestion.schema.version-soportada` y
`ingestion.schema.tolerar-versiones-mayores` permiten endurecer a DLQ `SCHEMA_UNSUPPORTED`.
La `secuencia` se **persiste** (columna `secuencia BIGINT`, NULL en eventos legados) y los huecos
se reportan con WARN sin descartar la lectura; el reinicio del publisher se registra como INFO.
La ventana de compatibilidad con el payload plano queda **indefinida y medida** (INFO con
contador de eventos legados).
**Alternativas descartadas:** mantener regex (el versionado habría sido decorativo), *fail-closed*
por default ante versión desconocida (rompe la evolución del publisher), incluir metadata de
dispositivo (hardware inexistente), agregar `SOSPECHOSA` (semántica nueva de calidad sin
definición) y renombrar `timestamp`.
**Consecuencias:** `query-api` entra en el alcance (también consume `sensor.lecturas`) y expone la
`calidad` del evento en el WS; la detección de huecos depende de la afinidad
sensor → partición → instancia de FIX-0005 para su estado en memoria; y el contrato de
`sensor.alertas` no cambia (verificado con su suite como regresión).

## ADR-0018 · Resiliencia del lookup de config de sensores: circuit breaker propio + cache TTL (FIX-0007)
**Contexto:** `ingestion-service` resolvía la config de cada sensor contra `sensor-registry` **sin
timeout explícito, sin circuit breaker y con una cache `ConcurrentHashMap` que nunca expiraba**.
Dos bugs reales derivados: (a) un sensor desactivado o con bandas nuevas seguía ingiriéndose con
la config vieja para siempre, y (b) el token JWT que se cacheaba al primer login **nunca se
refrescaba**, de modo que al expirar (1 h, el registry valida `exp`) todo sensor no cacheado
fallaba de forma permanente hasta reiniciar. El doc de backlog (`docs/FIX-0007-...`) no veía esos
dos y pedía un circuit breaker con `actuator` y reintentos que no existían.
**Decisión (HO-Gate 2026-09-13):** circuit breaker **propio en el dominio** (estados
CERRADO/ABIERTO/SEMIABIERTO, reloj inyectado, cero dependencias — Resilience4j sólo tenía el BOM
cacheado y habría roto el build offline); **cache local con TTL + last-known-good** (dentro del TTL
se responde sin red; vencida se refresca; si el refresh falla o el circuito está abierto, se usa la
copia vencida con WARN); timeouts de respuesta/conexión explícitos (2000/1000 ms); **refresco del
token ante 401** (invalidar + login + un reintento); DLQ con motivo **`REGISTRY_UNAVAILABLE`** (en
lugar de `INFRA_ERROR` genérico) y **endpoint interno** `GET /api/ingestion/resiliencia` sin
`actuator`. Umbrales moderados: 5 fallos consecutivos, 30 s abierto, 2 éxitos para cerrar, TTL 300 s.
**Alternativas descartadas:** Resilience4j (dependencia nueva sin artefactos en el `.m2`), Redis
para la cache (ningún servicio lo usa hoy y agrega un salto de red), `actuator`/Micrometer (no está
en ningún servicio), reintentos con backoff en este fix (ampliaba el camino de fallo; queda como
deuda: `retry-max-attempts` se declara sin uso).
**Consecuencias:** el lookup tolera la caída del registry sin perder lecturas de sensores conocidos,
la config se refresca (el estado `INACTIVO` y las bandas nuevas ahora se ven) y el token vencido ya
no deja el servicio en fallo permanente; `ingestion-service` pasa a exponer un endpoint HTTP interno
(ya levantaba Netty por `WebClient`).

## ADR-0019 · Habilitadores del frontend: CORS en el gateway, auth del handshake WS y resumen de sensores (FEAT-0008)
**Contexto:** el backend ya exponía login, CRUD, histórico, `/actual` y los WS, pero tres huecos
bloqueaban el dashboard: (a) **no había CORS en ningún servicio** — verificado, cero
configuraciones —, así que un SPA servido desde otro origen (Vite en dev) no podía consumir la API
ni mandar `Authorization`; (b) los **WebSocket no validaban token** (`alerting-service` y
`query-api` los exponen sin auth, brecha registrada en ADR-0016) y el navegador **no puede** enviar
`Authorization` en el upgrade; (c) el mapa necesitaba la metadata de todos los sensores **y** su
última lectura, lo que obligaba a **N+1** requests (`GET /api/sensores` + `/{id}/actual` por sensor)
bajo un rate limit de 120/min.
**Decisión (HO-Gate humano 2026-09-14):** el **SPA lo sirve el gateway** (mismo origen, punto de
entrada único; implementación en FEAT-0009); **CORS acotado y configurable** en el gateway
(`gateway.cors.origenes`, **vacío por default** = same-origin only, `allow-credentials: false`,
preflight respondido por el gateway con `204` sin consumir cupo ni tocar el downstream, `403` sin
headers cuando el origen no está permitido); **autenticación del handshake WS en el gateway**
(verificador HS256 propio con JDK crypto — sin dependencias nuevas, mismo formato que el
`JwtAdapter` del registry — que valida firma + `exp` + rol **antes** de completar el upgrade,
aceptando `?token=` o `Authorization: Bearer`, sin propagar ni loguear el token); **endpoint
`GET /api/sensores/resumen` en `query-api`** (roles {ADMIN, VIEWER}, metadata vía REST al registry
con timeout explícito y paginación keyset, última lectura de todos los sensores en **una** consulta
`DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC`, `502 REGISTRY_UNAVAILABLE` sin datos
parciales); el **simulador sigue fuera del gateway** (la demo usa `docker-compose.dev.yml`).
**Hallazgo del Loop (bug real, no del backlog):** la primera versión del filtro CORS rechazaba con
`403` *todo* request con `Origin` no listado, incluidos los del **propio origen** — y el navegador
manda `Origin` en los POST y, sobre todo, en el **handshake WebSocket**. Con la lista vacía
(default de producción) el SPA servido por el gateway no habría podido ni loguearse ni abrir un WS.
Lo detectó el IT del túnel WS de FEAT-0007 (pasaba antes de FEAT-0008 y falló después). Corrección:
**same-origin no es CORS** — si el `Origin` coincide con el host del gateway (vía `Host` o
`X-Forwarded-Host`/`-Proto` cuando hay un terminador TLS) el request pasa sin headers CORS y sin
bloqueo; el `403` queda para orígenes cruzados realmente ajenos al gateway.
**Alternativas descartadas:** CORS en cada servicio (cuatro configuraciones que se desincronizan y
preflight que sí consume cupo); comodín `*` por default (inaceptable con `Authorization`);
`Sec-WebSocket-Protocol` para el token (funciona pero ensucia el subprotocolo negociado; queda como
evolución documentada); cookie de sesión (el proyecto no emite cookies); componer el resumen en el
gateway (lo convertiría en BFF y contradice el rol de proxy simple de ADR-0016); que el SPA arme el
mapa con N+1 (inviable bajo el cupo de 120/min); cache del resumen en Redis (fuera de alcance).
**Consecuencias:** el dashboard puede autenticarse, mapear y recibir datos en vivo a través del
punto de entrada único; los WS dejan de ser públicos (un upgrade sin token ya no abre sesión ni
llega al downstream); el mapa se resuelve con 2 requests en lugar de 1+N; el resumen **acopla
`query-api` al `sensor-registry` por REST** (nueva dependencia de runtime con credenciales VIEWER
configurables y un modo de fallo explícito `502`). Regresión de FEAT-0007 asumida y documentada: el
túnel WS ahora exige token, así que su IT manda uno válido (AC-006 lo contempla).

