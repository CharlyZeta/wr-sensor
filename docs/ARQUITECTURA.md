# WR-Sensor — Arquitectura

> Documentación senior · fecha 2026-09-09 · fuente: spec `docs/water-monitoring-spec.md`,
> `stack.md` y contracts SDD-GL.

## 1. Visión de sistema

WR-Sensor es una plataforma de telemetría fluvial por **microservicios reactivos
(Java 25 / Spring Boot 4.1 WebFlux)** con arquitectura hexagonal y mensajería
RabbitMQ como columna vertebral. Los 4 servicios viven hoy como módulos Maven
**standalone** (`services/<nombre>`, pom propio); el aggregator multi-módulo y
docker-compose se formalizan en la fase de infraestructura.

### Diagrama de flujo de datos

```
                  ┌──────────────────┐
                  │  sensor-registry │  (CRUD de sensores + auth JWT)
                  │  8080 · Postgres │
                  └────────┬─────────┘
                           │ GET /api/sensores/{id} (config: rangos/estado)
                           ▼
┌────────────────┐   publica   ┌──────────────────┐   persiste   ┌───────────────┐
│ data-simulator │───────────▶ │ ingestion-service│────────────▶ │  TimescaleDB  │
│ (generador)    │  sensor.    │ (consumer)       │  hypertable  │  (lectura)    │
│                │  lecturas   │ evalúa severidad │  'lectura'   └───────────────┘
└────────────────┘             └────────┬─────────┘
                                        │ publica (cambios de severidad)
                                        ▼
                              ┌──────────────────────┐    push    ┌────────────┐
                              │    alerting-service  │──────────▶ │ WebSocket  │
                              │  (histéresis)        │ /ws/alertas│ clientes   │
                              └──────────────────────┘            └────────────┘
```

## 2. Contratos de mensajería (RabbitMQ)

| Exchange | Tipo | Routing key | Productor | Consumidor | Payload |
|---|---|---|---|---|---|
| `sensor.lecturas` | topic (durable) | `lectura.{sensorId}` | `data-simulator` (FEAT-0010) | `ingestion-service` (FEAT-0011) | `{sensorId, timestamp, valor, unidadMedida}` (FEAT-0010 BR-002) |
| `sensor.alertas` | topic (durable) | `alerta.{severidad}` | `ingestion-service` (FEAT-0011) | `alerting-service` (FEAT-0012) | `{sensorId, timestamp, valorLectura, severidadAnterior, severidadNueva, cruceHisteresis}` |

- **Colas**: `queue.sensor.lecturas` (bind `lectura.#`) y `queue.sensor.alertas`
  (bind `alerta.#`), durables.
- **DLQ/DLX por consumidor**: exchange fanout `sensor.lecturas.dlx` /
  `sensor.alertas.dlx` con cola DLQ y header `x-rechazo` (motivo). Retry y DLX
  configurables en `application.yml` (`messaging.retry.*`,
  `messaging.dead-letter.exchange`), nunca hardcodeados (spec §9.3).
- **Clientes**: Reactor RabbitMQ (`Sender`/`Receiver`), nunca `RabbitTemplate`.

## 3. Contratos de datos

### sensor-registry — tabla `sensor` (Postgres, metadata)
`id UUID PK · codigo UNIQUE · nombre · tipo (RIO…) · latitud/longitud · unidad_medida ·
estado (ACTIVO|INACTIVO|MANTENIMIENTO) · histeresis · frecuencia_reporte_segundos ·
fecha_instalacion · rango_normal/warning/critical (min,max)` — TIMESTAMP naive-UTC.

### sensor-registry — tabla `usuario` (auth)
`id UUID PK · email UNIQUE (minúsculas) · password_hash (BCrypt) · rol (ADMIN|VIEWER)`.

### ingestion-service — hypertable `lectura` (TimescaleDB)
`sensor_id UUID · ts TIMESTAMPTZ (columna de partición) · valor NUMERIC(12,2) ·
unidad_medida · severidad (NORMAL|WARNING|CRITICAL)`. Retención raw 90 días +
compresión día 8 + continuous aggregates = política TimescaleDB futura (spec §9.2).

## 4. Decisiones de arquitectura por servicio (resumen; detalle en DECISIONES.md)

- **sensor-registry** (FEAT-0001..0006, FIX-0001): hexagonal; guards de acceso
  resuelven el rol **una vez por request como atributo del exchange**
  (`RolGuard.ATTR_ROL`) — fix de una carrera de Reactor Context detectada en el Loop
  de FEAT-0005; timestamps persistidos en **naive-UTC** (`LocalDateTime.ofInstant`)
  — fix del tie-break keyset de FEAT-0002; JWT HS256 con JDK estándar (sin libs
  nuevas; ver DECISIONES).
- **data-simulator** (FEAT-0010): stateless; un `Flux.interval` por sensor; payload
  JSON manual; control `iniciar/detener/{id}/anomalia/estado` sin auth (dev).
- **ingestion-service** (FEAT-0011): consumer con ack tras persistir; config del
  sensor vía REST a registry (cache); severidad por bandas inclusivas; último estado
  en memoria; eventos solo en cambio de severidad.
- **alerting-service** (FEAT-0012): histéresis por **debounce temporal** (subida
  inmediata; bajada confirmada tras ventana `alerting.histeresis-segundos`;
  re-subida cancela); broadcast reactivo `Sinks` → WS `/ws/alertas`.

## 5. Códigos de error (convención)

Toda respuesta de error: `{"code":"...","message":"..."}`. Familia
`sensor-registry`: `SENSOR_INVALID_*`, `SENSOR_CODE_DUPLICATED`, `SENSOR_NOT_FOUND`,
`UNAUTHENTICATED`, `INSUFFICIENT_ROLE`, `INVALID_CREDENTIALS`.
`data-simulator`: `SIMULATOR_ALREADY_RUNNING`, `SIMULATOR_NOT_RUNNING`,
`SENSOR_NOT_FOUND`. Rechazos de consumo (DLQ): `PAYLOAD_INVALID`, `SENSOR_UNKNOWN`,
`SENSOR_INACTIVE`, `TIMESTAMP_OUT_OF_WINDOW`, `INFRA_ERROR`.

## 6. Frontend / infra futura

- Frontend React + Leaflet/MapLibre + dashboards (sin iniciar).
- `docker-compose.yml` con los 4+2 servicios, Postgres+TimescaleDB, Redis, RabbitMQ
  (sin iniciar; pendiente infra).
- Redis para "última lectura" de query-api (FEAT-0013; pendiente).

