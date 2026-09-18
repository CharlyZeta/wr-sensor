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

### Resumen de suites (verde al 2026-09-14)

| Módulo | Unit/assert | ITs | Total |
|---|---|---|---|
| `sensor-registry` | 89 | 34 | 123 |
| `data-simulator` | 19 | 1 | 20 |
| `ingestion-service` | 88 | 33 | 121 |
| `alerting-service` | 10 | 2 | 12 |
| `query-api` | 33 | 8 | 41 |
| `api-gateway` | 94 | 42 | 136 |
| **Total** | **333** | **120** | **453** |
| `web/` (SPA) | 37 | — | 37 |

> Los `*IT` no corren en `mvn test` (surefire los excluye): se ejecutan con
> `mvn -o test -Dtest='*IT'` y requieren Docker Desktop (los de `api-gateway` no: usan
> downstreams stub en proceso).

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

Estado del circuito (endpoint interno, sin auth, no expone datos de sensores). En el compose base
`ingestion-service` no publica puerto (es sólo consumer), así que se consulta **con el override de
desarrollo**, que lo expone en `:8090`:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
Invoke-WebRequest http://localhost:8090/api/ingestion/resiliencia | Select-Object -Expand Content
# → {"circuito":"CERRADO","fallosConsecutivos":0,"llamadas":12,"cacheTamano":6, ...}
```

Los logs también marcan las transiciones: `WARN ... circuit breaker del registry ABIERTO` e
`INFO ... SEMIABIERTO`/`CERRADO`. Si el circuito queda abierto mucho tiempo, revisar
`sensor-registry` y su healthcheck (`docker compose ps sensor-registry`).

## 8. Habilitadores del frontend: CORS, WS autenticado y resumen (FEAT-0008)

### CORS por entorno

| Entorno | `GATEWAY_CORS_ORIGENES` | Efecto |
|---|---|---|
| Producción (SPA servido por el gateway) | *(vacío, default)* | same-origin only: el navegador no necesita CORS |
| Desarrollo (SPA con Vite) | `http://localhost:5173` | habilita ese origen; varios separados por coma |
| Consumidor externo | el origen real | agregarlo a la lista, nunca `*` en producción |

```powershell
# dev: SPA en Vite contra el gateway del compose
$env:GATEWAY_CORS_ORIGENES = "http://localhost:5173"
docker compose up -d api-gateway

# preflight (lo responde el gateway: 204, sin cupo y sin downstream)
curl -i -X OPTIONS http://localhost:8084/api/sensores/resumen `
  -H 'Origin: http://localhost:5173' -H 'Access-Control-Request-Method: GET' `
  -H 'Access-Control-Request-Headers: Authorization'
# → HTTP/1.1 204 · Access-Control-Allow-Origin: http://localhost:5173 · Access-Control-Max-Age: 3600

# origen no permitido → 403 sin headers Access-Control-*
curl -i -X OPTIONS http://localhost:8084/api/sensores/resumen `
  -H 'Origin: http://otro.example' -H 'Access-Control-Request-Method: GET'
```

Otros knobs: `GATEWAY_CORS_MAX_AGE` (segundos), `gateway.cors.metodos|headers|headers-expuestos|
permitir-credenciales` (por default `false`: el token viaja en header, no en cookies). Un `Origin`
igual al del propio gateway **no** es un request CORS (el navegador lo manda en POST y en el
handshake WS): pasa sin headers y sin bloqueo.

### Autenticar un WebSocket a mano

`/ws/alertas` y `/ws/sensores/{id}` exigen JWT válido + rol `ADMIN`/`VIEWER`
(`gateway.ws.roles-permitidos`, `gateway.ws.jwt-secreto` = `AUTH_JWT_SECRET`):

```powershell
$token = (Invoke-RestMethod -Method POST http://localhost:8084/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"email":"viewer@wrsensor.local","password":"Viewer123!"}').token

# navegador / websocat: el token va por query string (el upgrade no acepta headers propios)
websocat "ws://localhost:8084/ws/alertas?token=$token"

# cliente no navegador: también vale el header
websocat -H="Authorization: Bearer $token" ws://localhost:8084/ws/alertas

# sin token → 401 UNAUTHENTICATED y el downstream no recibe ninguna conexión
curl -i http://localhost:8084/ws/alertas -H 'Upgrade: websocket' -H 'Connection: Upgrade'
```

El token del query string **no** se loguea (la línea de acceso registra sólo el path) ni se propaga
al downstream. Si el nodo queda detrás de un terminador TLS, setear `X-Forwarded-Proto`/`-Host`
para que la detección de same-origin siga funcionando.

### Resumen del mapa

```powershell
curl -s http://localhost:8084/api/sensores/resumen -H "Authorization: Bearer $token"
# → [{"id":"…","codigo":"S-01",…,"ultimaLectura":{"valor":21.5,"timestamp":"…","severidad":"NORMAL","calidad":"OK"}}]
```

Configuración (`services/query-api/src/main/resources/application.yml`, prefijo `query.registry`):

| Pieza | Env | Default |
|---|---|---|
| URL del registry | `REGISTRY_URL` | `http://localhost:8080` |
| Credenciales de servicio (VIEWER) | `QUERY_REGISTRY_EMAIL` / `QUERY_REGISTRY_PASS` | `viewer@wrsensor.local` / `Viewer123!` |
| Timeout de respuesta | `QUERY_REGISTRY_TIMEOUT_MS` | 5000 ms |
| Timeout de conexión | `QUERY_REGISTRY_CONEXION_TIMEOUT_MS` | 2000 ms |
| Página del listado keyset | `QUERY_REGISTRY_LIMIT_PAGINA` | 200 |
| Tope de sensores | `QUERY_REGISTRY_MAX_SENSORES` | 5000 |

Si el registry no responde, el endpoint devuelve `502 REGISTRY_UNAVAILABLE` (nunca una lista
parcial). El resumen es la fuente del mapa y del listado del SPA (`FEAT-0009`).

## 9. Seguridad del punto de entrada (FIX-0008)

Todo el endurecimiento vive en el `api-gateway` (es el único punto de entrada publicado y el host del
SPA) y es **configuración**, no código: `gateway.seguridad.*` en
`services/api-gateway/src/main/resources/application.yml`.

### Secreto del handshake WebSocket (hallazgo S1 de la revisión de seguridad)

El gateway valida el JWT del upgrade WS, así que **necesita el mismo `AUTH_JWT_SECRET`** que
`sensor-registry`. Antes no se le pasaba: caía al default de desarrollo (público en el repo) y
cualquiera podía **forjar un token `ADMIN`/`VIEWER`** y abrir la telemetría y las alertas.

```powershell
# 1) el secreto debe llegar al gateway (y ser el mismo que usa el registry)
docker compose config | Select-String 'AUTH_JWT_SECRET'
docker compose exec api-gateway printenv AUTH_JWT_SECRET

# 2) un token firmado con el secreto de desarrollo NO debe abrir el WS en un entorno real
#    (rotar AUTH_JWT_SECRET y reintentar el handshake: 401 UNAUTHENTICATED)
curl.exe -i -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Sec-WebSocket-Version: 13" `
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" "http://localhost:8084/ws/alertas?token=$forjado"
```

**Fail-fast:** con un perfil que no sea de desarrollo (`gateway.seguridad.perfiles-desarrollo`, por
default `dev`/`local`/`test`) el gateway **no arranca** si el secreto sigue siendo el de desarrollo;
en desarrollo arranca con un `WARN` que lo dice explícitamente (para no romper `docker compose up` sin
configurar nada).

### Headers de seguridad y CSP

El gateway aplica los headers a **toda** respuesta (API, SPA y errores) y **descarta** los que mande
el downstream, así que aparecen una sola vez y no pueden ser pisados por un servicio comprometido:

| Header | Default | Config |
|---|---|---|
| `Content-Security-Policy` | `script-src 'self'` (sin `unsafe-inline`/`unsafe-eval`), `img-src` con los tiles de OSM, `connect-src 'self' ws: wss:`, `object-src 'none'`, `frame-ancestors 'none'` | `gateway.seguridad.content-security-policy` / `GATEWAY_CSP` |
| `X-Content-Type-Options` | `nosniff` | fijo |
| `Referrer-Policy` | `no-referrer` (los tiles de terceros no reciben la URL del panel) | fijo |
| `X-Frame-Options` | `DENY` (anti clickjacking del panel) | fijo |
| `Permissions-Policy` | `geolocation=(), camera=(), microphone=()` | `gateway.seguridad.permisos-politica` |
| `Cross-Origin-Resource-Policy` | `same-origin` | fijo |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` **sólo** si la request llegó por HTTPS (`X-Forwarded-Proto: https`) | `gateway.seguridad.hsts` / `GATEWAY_HSTS` |

La CSP es la segunda línea de defensa del token (que vive en `sessionStorage`): si alguien lograra
inyectar un `<script>` inline, la CSP lo bloquea. `style-src` sí permite `'unsafe-inline'` porque
Leaflet manipula estilos; es una excepción **documentada** y no habilita scripts.

```powershell
# verificar headers (en PowerShell usar curl.exe: `curl` es alias de Invoke-WebRequest)
curl.exe -sS -D - -o NUL http://localhost:8084/
curl.exe -sS -D - -o NUL http://localhost:8084/api/sensores/resumen -H "Authorization: Bearer $token"
# HSTS sólo con TLS adelante:
curl.exe -sS -D - -o NUL -H "X-Forwarded-Proto: https" http://localhost:8084/
```

### Hosting del SPA y contrato de la API (hallazgo S2/A6)

El gateway sirve el SPA desde `classpath:/static/` (`gateway.seguridad.static-location`) con una regla
explícita de resolución:

| Request | Respuesta |
|---|---|
| `/`, `/login`, `/mapa`, `/sensores/**` (configurables) | `200 text/html` del índice, `Cache-Control: no-store` |
| `/assets/<nombre>-<hash>.<ext>` existente | `200` con `Cache-Control: public, max-age=31536000, immutable` |
| `/assets/noexiste.js` (o cualquier path con extensión que no existe) | `404 ROUTE_NOT_FOUND` (**nunca** el índice) |
| `/api/**` no declarado | `404 ROUTE_NOT_FOUND` en JSON (contrato de FEAT-0007 intacto) |
| `/ws/**` declarado sin upgrade | `426 WS_UPGRADE_REQUIRED`; sin token válido → `401 UNAUTHENTICATED` |
| `/..%2f..%2fapplication.yml`, `/WEB-INF/web.xml` | `404`, sin leer nada fuera de `static/` |

```powershell
curl.exe -i http://localhost:8084/                       # 200 text/html
curl.exe -i http://localhost:8084/sensores/abc           # 200 text/html (ruta de cliente)
curl.exe -i http://localhost:8084/api/loquesea           # 404 JSON ROUTE_NOT_FOUND
curl.exe -i http://localhost:8084/assets/noexiste.js     # 404 (nunca 200 con HTML)
curl.exe -i "http://localhost:8084/..%2f..%2fapplication.yml"
```

> El SPA **construido** se copia a `services/api-gateway/src/main/resources/static/` (carpeta generada,
> no versionada). Sin build, esas rutas responden `404` y el gateway lo registra en el log.

## 10. SPA del operador (`web/`) — FEAT-0009

El frontend vive en **`web/`** (Vite + React + TypeScript) y se **sirve desde el gateway** (mismo
origen), así que no hay CORS ni URLs absolutas. Requiere **Node ≥ 22** (en este entorno: Node 26.3.0 /
npm 11.16.0; no hay pnpm/yarn).

### Desarrollo (proxy, sin CORS)

```powershell
# 1) el gateway tiene que estar arriba (docker compose up -d; sólo publica :8084)
cd web
npm ci                     # reproducible, sobre el lockfile versionado
npm run dev                # http://localhost:5173
```

`vite.config.ts` **proxya** `/api` y `/ws` al gateway (`http://localhost:8084`, override con
`VITE_PROXY_TARGET`): el navegador ve un único origen, igual que en producción, y por eso **no hace
falta** habilitar CORS para desarrollar. El dev server escucha sólo en `localhost` a propósito (con
credenciales del operador, no debe quedar expuesto en la LAN).

### Tests y calidad

```powershell
cd web
npm test               # vitest run (jsdom + Testing Library + MSW)
npm run test:coverage  # cobertura v8
npm run typecheck      # tsc -b (estricto)
npm run lint           # eslint (prohíbe dangerouslySetInnerHTML/innerHTML y console.*)
npm run build          # tsc -b && vite build → web/dist
npm run e2e            # Playwright (requiere el stack levantado)
```

Variables del SPA (todas se **inlinean en el build**: nunca poner secretos): ver `web/.env.example`.
Las relevantes son `VITE_API_BASE` (vacío = mismo origen), `VITE_RESUMEN_REFRESCO_MS` (default
30 000 ms), `VITE_TILES_URL`/`VITE_TILES_ATRIBUCION`, `VITE_LOCALE`.

### Detalle en vivo (FEAT-0014)

El detalle del sensor (`/sensores/{id}`) combina el **WebSocket** de lecturas con el **histórico
keyset**:

| Variable | Default | Para qué |
|---|---|---|
| `VITE_SERIE_HORAS` | `24` | ventana de la serie temporal |
| `VITE_SERIE_MAX_PUNTOS` | `2000` | tope de puntos pedidos al histórico (protege el cupo `lectura` de 120/min) |
| `VITE_SERIE_REFRESCO_MS` | `60000` | refresco de respaldo del histórico **sólo** mientras el WS no entrega datos |
| `VITE_TABLA_FILAS` | `25` | filas visibles de la tabla de últimas lecturas |
| `VITE_DATO_VENCIDO_MS` | `120000` | a partir de cuándo el último dato se marca como vencido |
| `VITE_WS_BACKOFF_BASE_MS` / `_FACTOR` / `_TOPE_MS` | `1000` / `2` / `30000` | backoff exponencial con jitter de la reconexión |
| `VITE_WS_MAX_INTENTOS` | `6` | intentos antes de quedar en `pausado` (con la hora del último dato) |

```powershell
# el WS del detalle se puede probar a mano (el token va en el query: FEAT-0008)
$token = (Invoke-RestMethod -Method POST http://localhost:8084/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"email":"viewer@wrsensor.local","password":"Viewer123!"}').token
websocat "ws://localhost:8084/ws/sensores/<uuid-del-sensor>?token=$token"
# → {"sensorId":"…","timestamp":"…","valor":21.5,"unidadMedida":"CELSIUS","calidad":"OK"}
# el payload NO trae severidad: el detalle la toma del último dato conocido (histórico/resumen)
```

La ventana de 24 h con frecuencia de 30 s supera el `limit` máximo del histórico (1000), así que el
SPA **encadena el cursor** hasta cubrir la ventana o llegar a `VITE_SERIE_MAX_PUNTOS`; si trunca, lo
dice en la leyenda de la serie.

### Servirlo desde el gateway

Dos caminos, ambos con el SPA dentro del jar/imagen del gateway:

```powershell
# A) Docker (lo normal): la imagen construye el SPA en un stage de Node y lo monta en /app/static/
docker compose up -d --build api-gateway
curl.exe -i http://localhost:8084/            # 200 text/html (índice del SPA)

# B) Local sin Docker: build del SPA + profile opt-in de Maven
cd web; npm ci; npm run build; cd ..
& $mvn -o -f services/api-gateway/pom.xml package -Pcon-spa   # copia web/dist a target/classes/static
```

El profile `con-spa` **sólo copia** `web/dist` (no invoca npm), así que el ciclo `mvn -o test` del
backend sigue siendo offline: el build del SPA es la única pieza que necesita red. La ubicación de
los estáticos es configuración (`GATEWAY_STATIC_LOCATION`): `classpath:static/` por default y
`file:/app/static/` en la imagen Docker.

### Qué verificar del hosting

```powershell
curl.exe -i http://localhost:8084/                        # 200 text/html, Cache-Control: no-store
curl.exe -i http://localhost:8084/sensores/abc             # 200 text/html (ruta de cliente)
curl.exe -i http://localhost:8084/api/loquesea             # 404 JSON ROUTE_NOT_FOUND (nunca HTML)
curl.exe -i http://localhost:8084/assets/noexiste.js       # 404 (nunca 200 con el índice)
curl.exe -sS -D - -o NUL http://localhost:8084/ | Select-String 'Content-Security-Policy'
```

Si el SPA se sirve pero la CSP bloquea el mapa, revisar `gateway.seguridad.content-security-policy`:
el origen de los tiles tiene que estar en `img-src` (ver §9).

> **e2e (Playwright)**: `npm run e2e` requiere `npx playwright install` una vez y el stack levantado
> (`docker-compose.dev.yml` publica los puertos de los servicios, o el gateway con el SPA construido).

> **NOTA:** el SPA construido se copia a `services/api-gateway/src/main/resources/static/` sólo si se
> usa el flujo manual; esa carpeta es **generada** y no se versiona.

## 11. Orquestación SDD-GL

- Orquestador: `CLAUDE.md` (Claude Code) / `AGENTS.md` (Antigravity). Arranque: leer
  `contracts/[ID].md` → DRAFT/GATE → `sdd-gate`; APPROVED/LOOP → `sdd-loop`;
  RESOLVED → work item completo.
- Auditoría de cada Loop: `.sdd/runs/FEAT-XXXX-<timestamp>.md` (Glass Box).





