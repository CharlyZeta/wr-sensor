# WR-Sensor — API (registro actual)

Convención: errores `{"code","message"}`. Auth (FEAT-0006): `Authorization: Bearer <jwt>`
obtenido en `POST /api/auth/login`. El header literal `Bearer ADMIN` **ya no es
válido** (AC-008 FEAT-0006).

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
`SENSOR_UNKNOWN`, `SENSOR_INACTIVE`, `TIMESTAMP_OUT_OF_WINDOW`).

## Ejemplos curl

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@wrsensor.local","password":"Admin123!"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -s localhost:8080/api/sensores -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/sensores -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{…SensorRequest…}'
```
