# WR-Sensor — API (registro actual)

Convención: errores `{"code","message"}`. Auth (FEAT-0006): `Authorization: Bearer <jwt>`

**Punto de entrada único (FEAT-0007):** todos los endpoints de esta página se consumen a través
del `api-gateway` (`http://localhost:8084`), que enruta al servicio dueño, aplica rate limiting
por IP y propaga/genera `X-Correlation-Id`. Las rutas y la tabla de límites están en
`docs/ARQUITECTURA.md` §1; el acceso directo a cada servicio (para debug) requiere
`docker-compose.dev.yml`.

Códigos propios del gateway:

| HTTP | code | Cuándo |
|---|---|---|
| `429` | `RATE_LIMIT_EXCEEDED` | cupo de la clase agotado (con `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`) |
| `404` | `ROUTE_NOT_FOUND` | path no declarado en la tabla de rutas |
| `502` | `UPSTREAM_UNAVAILABLE` | servicio destino caído (REST) o handshake WS imposible |
| `504` | `UPSTREAM_TIMEOUT` | el destino no respondió dentro de `timeout-ms` de la ruta |
| `426` | `WS_UPGRADE_REQUIRED` | request sin upgrade a una ruta `/ws/**` |
| `401` | `UNAUTHENTICATED` | upgrade WS sin token, con token inválido o expirado (FEAT-0008) |
| `403` | `INSUFFICIENT_ROLE` | token válido con rol fuera de `gateway.ws.roles-permitidos` (FEAT-0008) |
| `403` | `ORIGIN_NOT_ALLOWED` | request/preflight de un origen cruzado fuera de `gateway.cors.origenes` (FEAT-0008) |

obtenido en `POST /api/auth/login`. El header literal `Bearer ADMIN` **ya no es
válido** (AC-008 FEAT-0006).

### CORS (FEAT-0008)

El gateway es el único punto donde se configura CORS (`gateway.cors.*`, lista de orígenes **vacía**
por default = mismo origen). Responde él mismo los preflight `OPTIONS` (`204` + headers, sin
consumir cupo ni tocar el downstream) y expone `X-Correlation-Id`, `X-RateLimit-*` y `Retry-After`
al JavaScript del SPA. Un origen cruzado no permitido recibe `403 ORIGIN_NOT_ALLOWED` **sin**
headers `Access-Control-*`. Un `Origin` que coincide con el propio gateway (p. ej. el handshake WS
del SPA servido por el gateway) no es un request CORS: pasa sin headers y sin bloqueo.

### WebSocket autenticado (FEAT-0008)

`/ws/alertas` y `/ws/sensores/**` exigen JWT válido (HS256 con `AUTH_JWT_SECRET`, firma + `exp`) y
un rol permitido antes de completar el handshake. El token se acepta por **`?token=<jwt>`** (el
navegador no puede mandar `Authorization` en el upgrade) o por `Authorization: Bearer <jwt>`. Sin
token válido el gateway responde `401 {"code":"UNAUTHENTICATED"}` (o `403
{"code":"INSUFFICIENT_ROLE"}`) **antes** de abrir sesión: el servicio downstream no recibe ninguna
conexión. El token del query string no se registra en el log de acceso ni se propaga al downstream.

## sensor-registry (default :8080)

| Método | Ruta | Auth | 200/éxito | Errores típicos |
|---|---|---|---|---|
| `POST` | `/api/sensores` | ADMIN | `201` `SensorResponse` | 400 `SENSOR_INVALID_*` · 409 `SENSOR_CODE_DUPLICATED` · 401/403 |
| `GET` | `/api/sensores?cursor&limit` | ADMIN/VIEWER | `200` `{items, nextCursor}` | 400 `SENSOR_INVALID_LIMIT/CURSOR` · 401/403 |
| `GET` | `/api/sensores/{id}` | ADMIN/VIEWER | `200` `SensorResponse` | 400 `SENSOR_INVALID_ID` · 404 `SENSOR_NOT_FOUND` · 401/403 |
| `PUT` | `/api/sensores/{id}` | ADMIN | `200` `SensorResponse` actualizado | 400 `SENSOR_INVALID_*`/`SENSOR_INVALID_REQUEST` · 404 · 401/403 · INACTIVO rechazado |
| `DELETE` | `/api/sensores/{id}` | ADMIN | `204` (baja lógica → INACTIVO, idempotente) | 400/404 · 401/403 |
| `POST` | `/api/auth/login` | — | `200` `{token, rol, expiraEnSegundos}` | 400 `SENSOR_INVALID_REQUEST` · 401 `INVALID_CREDENTIALS` |

`SensorResponse`: `id, codigo, nombre, tipo, latitud, longitud, unidadMedida, estado,
histeresis, frecuenciaReporteSegundos, fechaInstalacion, rangoNormal, rangoWarning,
rangoCritical`. Listado keyset: DESC `fechaInstalacion`, desempate ASC `id`;
`nextCursor` opaco solo si hay más; `limit` default 100, máx 1000 (nunca OFFSET).

`PUT` body (subset config completo): `estado ∈ {ACTIVO, MANTENIMIENTO}`, `histeresis`,
`frecuenciaReporteSegundos`, `rangoNormal/Warning/Critical {min,max}`. Campos fuera
del DTO → `400 SENSOR_INVALID_REQUEST` (Jackson estricto).

## query-api (default :8082)

| Método | Ruta | Auth | 200/éxito | Errores típicos |
|---|---|---|---|---|
| `GET` | `/api/sensores/{id}/lecturas?desde&hasta&cursor&limit` | ADMIN/VIEWER | `200 {items, nextCursor}` | 400 `SENSOR_INVALID_*`/`INVALID_RANGE` · 401/403 |
| `GET` | `/api/sensores/{id}/actual` | ADMIN/VIEWER | `200` lectura | 404 `SENSOR_NOT_FOUND` · 401/403 |
| `GET` | `/api/sensores/resumen` | ADMIN/VIEWER | `200` array de resumen (FEAT-0008) | **502 `REGISTRY_UNAVAILABLE`** · 401/403 |

`GET /api/sensores/resumen` (FEAT-0008 BR-005) es la fuente única del mapa y del listado del SPA:
devuelve **todos** los sensores con su metadata del registry y su última lectura.

```json
[{"id":"…","codigo":"S-01","nombre":"Sensor S-01","tipo":"TEMPERATURA",
  "latitud":-34.60,"longitud":-58.40,"estado":"ACTIVE","unidadMedida":"CELSIUS",
  "ultimaLectura":{"valor":3.00,"timestamp":"2026-09-14T12:01:00Z","severidad":"CRITICAL","calidad":"SOSPECHOSA"}}]
```

- Un sensor **sin lecturas** aparece igual, con `"ultimaLectura":null` (nunca se omite).
- La última lectura de todos los sensores sale de **una sola** consulta a la hypertable
  (`DISTINCT ON (sensor_id) … ORDER BY sensor_id, ts DESC`), nunca N+1.
- La metadata se pide al `sensor-registry` por REST con paginación keyset, timeout explícito
  (`query.registry.timeout-ms`) y credenciales de servicio (`query.registry.auth.*`). Si el
  registry no responde o responde con error, la respuesta es `502 REGISTRY_UNAVAILABLE` **sin**
  datos parciales: el mapa no se muestra a medias.
- Los endpoints existentes no cambian: el resumen no los reemplaza.

## data-simulator (default :8081)

| Método | Ruta | Descripción | Respuesta |
|---|---|---|---|
| `POST` | `/api/simulador/iniciar` | Arranca generadores (6 sensores seed) | `200 {estado:"RUNNING", sensores:[…], lecturasPublicadas:N}` · `409 SIMULATOR_ALREADY_RUNNING` |
| `POST` | `/api/simulador/detener` | Detiene (idempotente) | `200 {estado:"STOPPED", …}` |
| `POST` | `/api/simulador/{sensorId}/anomalia` | Inyecta anomalía puntual | `200` · `404 SENSOR_NOT_FOUND` · `409 SIMULATOR_NOT_RUNNING` |
| `GET` | `/api/simulador/estado` | Estado actual | `200 {estado, sensores, lecturasPublicadas}` |

Sin auth en v1 (decisión HO-Gate FEAT-0010). Publica a `sensor.lecturas`
(payload `{sensorId, timestamp, valor, unidadMedida}`), frecuencia por sensor
(default 30 s, configurable).

## ingestion-service y alerting-service

Sin API REST (solo consumidores): `ingestion-service` lee `sensor.lecturas` y
publica `sensor.alertas`; `alerting-service` lee `sensor.alertas` y expone el
**WebSocket `ws://<host>:<port>/ws/alertas`** que push de alertas confirmadas:

```json
{"sensorId":"…","severidadNueva":"WARNING","confirmada":true,"timestamp":"…"}
```

Rechazos de consumo terminan en las DLQ (`queue.sensor.lecturas.dlq`,
`queue.sensor.alertas.dlq`) con header `x-rechazo` (`PAYLOAD_INVALID`,
`SENSOR_UNKNOWN`, `SENSOR_INACTIVE`, `TIMESTAMP_OUT_OF_WINDOW`, `SCHEMA_UNSUPPORTED`,
`REGISTRY_UNAVAILABLE`).
`SCHEMA_UNSUPPORTED` sólo aparece con `ingestion.schema.tolerar-versiones-mayores: false`
(FIX-0006); por default una versión mayor desconocida se procesa con WARN.
`REGISTRY_UNAVAILABLE` (FIX-0007) indica que `sensor-registry` no responde **y** no hay config
cacheada para ese sensor.

## Ejemplos curl

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@wrsensor.local","password":"Admin123!"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -s localhost:8080/api/sensores -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/sensores -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{…SensorRequest…}'
```
