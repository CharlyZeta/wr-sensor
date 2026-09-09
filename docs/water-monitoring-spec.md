# Water Level Monitoring Platform — Spec Base (pre-SDD-GL)

> Documento de trabajo genérico. Sirve como Intent + Contract de referencia antes de formalizar cada
> servicio con SDD-GL (Gate → Contract → Loop). Nombre del proyecto: placeholder — renombrar cuando definas.

---

## 1. Visión general

Plataforma showcase de monitoreo de altura de agua en ríos/arroyos/bañados. Ingesta simulada
de alta frecuencia, procesamiento 100% reactivo, alertas por rango con histéresis, mapa +
dashboards, y base preparada para análisis externo/IA (fase 2, fuera de este spec).

**Fuera de alcance (fase 2, explícitamente diferido):**
- Análisis con IA / Spring AI sobre logs y lecturas
- Observabilidad avanzada (dashboards Grafana, tracing distribuido detallado)
- Notificación por email/SMS
- Kubernetes (se documenta como plus, no se implementa en v1)

---

## 2. Stack técnico fijo (no negociable dentro de este proyecto)

| Capa | Tecnología | Nota |
|---|---|---|
| Lenguaje/runtime | Java 25 | |
| Framework | Spring Boot 4.1 / Spring Framework 7 | |
| Web | Spring WebFlux | cero MVC bloqueante |
| Acceso a datos | R2DBC | cero JPA/Hibernate |
| Base histórica | TimescaleDB (Postgres) | hypertables para lecturas |
| Base metadata | Postgres (mismo motor, distinto schema) | sensores, usuarios, config |
| Cache/estado | Redis (Lettuce reactivo) | última lectura por sensor |
| Mensajería | RabbitMQ + Reactor RabbitMQ | cliente reactivo, no bloqueante |
| Auth | JWT + roles (admin/viewer) | emisor propio (auth simple, no OAuth externo en v1) |
| Frontend | React | mapa (Leaflet/MapLibre) + dashboards |
| Orquestación | Docker Compose | manifiestos k8s como documentación futura, no implementados |
| Arquitectura interna | Hexagonal (ports & adapters) por servicio | domain sin dependencias de Spring/Reactor en las reglas |

Regla dura: ningún servicio mezcla acceso bloqueante y no bloqueante. Si una librería no tiene
variante reactiva confiable, se evalúa antes de sumarla (no se "resuelve después").

---

## 3. Bounded contexts / microservicios

| Servicio | Responsabilidad | Expone |
|---|---|---|
| `sensor-registry` | CRUD de sensores, ubicación, metadata, config de rangos/histéresis, roles de usuario | API REST (admin) |
| `data-simulator` | Genera lecturas sintéticas por sensor, patrones + ruido + anomalías inyectables | API de control (start/stop/inject), publisher a RabbitMQ |
| `ingestion-service` | Consume lecturas, valida, persiste en TimescaleDB, evalúa contra rangos, emite eventos de alerta | Consumer RabbitMQ, no expone API pública |
| `alerting-service` | Consume eventos de alerta, aplica lógica de severidad/histéresis, notifica (WS) | Consumer + WebSocket push |
| `query-api` | Lecturas actuales + históricas, para frontend y consumidores externos | API REST + WebSocket/SSE |

Comunicación entre servicios: **solo vía eventos (RabbitMQ) o API REST**, nunca acceso directo
a la base de otro servicio (cada servicio es dueño de su schema).

---

## 4. Modelo de dominio (entidades núcleo)

### Sensor (dueño: `sensor-registry`)
```
Sensor {
  id: UUID
  codigo: string            // identificador legible, ej "RIO-PARANA-001"
  nombre: string
  tipo: enum [RIO, ARROYO, BAÑADO]
  latitud: double
  longitud: double
  unidadMedida: enum [METROS, CENTIMETROS]
  rangoNormal: { min: decimal, max: decimal }
  rangoWarning: { min: decimal, max: decimal }
  rangoCritical: { min: decimal, max: decimal }
  histeresis: decimal        // margen para evitar flapping al cruzar umbral
  frecuenciaReporteSegundos: int
  estado: enum [ACTIVO, INACTIVO, MANTENIMIENTO]
  fechaInstalacion: date
}
```

### Lectura (dueño: `ingestion-service`, tabla hypertable en TimescaleDB)
```
Lectura {
  sensorId: UUID
  timestamp: instant          // columna de partición Timescale
  valor: decimal
  unidadMedida: enum
  severidadCalculada: enum [NORMAL, WARNING, CRITICAL]
}
```

### AlertaEvento (evento de dominio, publicado por `ingestion-service`, consumido por `alerting-service`)
```
AlertaEvento {
  sensorId: UUID
  timestamp: instant
  valorLectura: decimal
  severidadAnterior: enum
  severidadNueva: enum
  cruceHisteresis: boolean     // true si el cambio se confirmó tras aplicar histéresis
}
```

### Usuario (dueño: `sensor-registry`, para auth)
```
Usuario {
  id: UUID
  email: string
  passwordHash: string
  rol: enum [ADMIN, VIEWER]
}
```

**Pendiente de definir en próxima pasada:** si `sensor-registry` es también el Auth Server, o
si conviene un servicio `auth-service` separado (más correcto para hexagonal puro, pero más
servicios para un showcase). Sugerido: mantenerlo dentro de `sensor-registry` en v1, separar
si el proyecto crece.

---

## 5. Modelo de alertas — multi-nivel + histéresis

- Tres bandas por sensor: `NORMAL`, `WARNING`, `CRITICAL`, definidas por rangos configurables.
- **Histéresis**: al cruzar un umbral hacia severidad mayor, se confirma inmediatamente. Al
  volver hacia severidad menor, el valor debe permanecer dentro de la banda inferior durante
  un margen (`histeresis`, en la misma unidad que la medida) antes de degradar la alerta —
  evita parpadeo cuando la lectura oscila justo en el borde.
- Cada cambio de severidad confirmado genera un `AlertaEvento`. Cambios que no cruzan
  histéresis no generan evento (solo se persiste la lectura cruda).

---

## 6. Contratos de mensajería (RabbitMQ) — borrador

| Exchange | Tipo | Routing key | Productor | Consumidor(es) |
|---|---|---|---|---|
| `sensor.lecturas` | topic | `lectura.{sensorId}` | `data-simulator` | `ingestion-service` |
| `sensor.alertas` | topic | `alerta.{severidad}` | `ingestion-service` | `alerting-service` |

Dead-letter queue por cada cola de consumo (a definir política de retry en el contrato formal
de cada servicio).

---

## 7. API — endpoints principales (borrador, no exhaustivo)

**sensor-registry**
- `POST/GET/PUT /api/sensores` (admin)
- `POST /api/auth/login` → JWT

**query-api**
- `GET /api/sensores/{id}/lecturas?desde&hasta` (histórico)
- `GET /api/sensores/{id}/actual` (última lectura, desde Redis)
- `WS /ws/sensores/{id}` (push en tiempo real)

**data-simulator**
- `POST /api/simulador/iniciar`
- `POST /api/simulador/{sensorId}/anomalia` (inyectar anomalía puntual)

---

## 8. Simulador de datos — spec funcional

- Genera N sensores configurables al arrancar (o los toma de `sensor-registry`).
- Por sensor: curva base (estacional/diaria) + ruido gaussiano + posibilidad de anomalía
  manual o programada.
- Frecuencia de publicación independiente por sensor (`frecuenciaReporteSegundos`).
- Corre desacoplado del resto — puede pausarse/reiniciarse sin afectar los demás servicios.

---

## 9. Decisiones resueltas (segunda pasada)

### 9.1 — Auth
`auth-service` queda **dentro de `sensor-registry`** en v1 (confirmado).

### 9.2 — Retención y downsampling en TimescaleDB (sugerido, dado el volumen a años)

Estrategia en 3 niveles, usando funcionalidad nativa de TimescaleDB (no lógica custom):

| Nivel | Resolución | Retención | Mecanismo |
|---|---|---|---|
| Raw | cada lectura tal cual llega | 90 días | hypertable normal |
| Compresión | mismo dato, comprimido | desde el día 8 | `compression policy` (7 días sin comprimir, después se comprime automáticamente — reduce tamaño ~90%) |
| Agregado horario | min/max/avg/count por hora | indefinido | `continuous aggregate` materializado, se actualiza solo |
| Agregado diario | min/max/avg/count por día | indefinido (años) | `continuous aggregate` sobre el agregado horario |

Regla: consultas de rango corto (< 7 días) van contra raw; rangos largos (meses/años) van
contra los continuous aggregates — esto es lo que hace viable tener "muchos años" de datos sin
que las consultas históricas se vuelvan inusables. Se implementa en `query-api`: el mismo
endpoint elige la fuente según el rango solicitado (transparente para el cliente).

### 9.3 — Reintentos/DLQ (configurable, protocolo de sensores aún no definido)

Como no está definida la tecnología IoT/protocolo real de los sensores, la resiliencia se
diseña en dos capas separadas:

- **Capa de transporte IoT → sistema**: se abstrae detrás de un *port* de entrada en
  `data-simulator` (o el futuro `ingestion-gateway` real). Cuando se defina el protocolo real
  (MQTT, HTTP, LoRaWAN, etc.), se implementa como un nuevo adapter sin tocar el dominio —
  coherente con hexagonal.
- **Capa de mensajería interna (RabbitMQ)**: totalmente configurable vía `application.yml`,
  no hardcodeado:
  ```yaml
  messaging:
    retry:
      max-attempts: 5          # configurable
      initial-backoff: 1s      # configurable
      multiplier: 2.0          # backoff exponencial
      max-backoff: 30s
    dead-letter:
      enabled: true
      exchange: sensor.dlx
  ```
  Cada cola tiene su DLQ asociada. Mensajes que agotan reintentos van a DLQ para inspección
  manual/reprocesamiento — no se pierden.

### 9.4 — Paginación (pensada para vida útil de años)

**Keyset/cursor pagination**, no offset — con volumen de años, `OFFSET` en Postgres degrada
linealmente (tiene que recorrer y descartar todas las filas previas). Cursor pagination usa el
propio timestamp como cursor, con costo constante sin importar cuán "atrás" se pagine.

```
GET /api/sensores/{id}/lecturas?desde&hasta&cursor&limit
```
- `limit`: configurable, default 100, máximo 1000 (cap para evitar payloads gigantes)
- `cursor`: timestamp de la última lectura recibida, opaco para el cliente
- Combinado con 9.2: si el rango pedido cae en zona "vieja", pagina sobre el continuous
  aggregate correspondiente en vez del raw.

### 9.5 — Dataset inicial de 6 sensores (datos reales de referencia)

Usando estaciones hidrométricas reales de los sistemas de alerta de Paraná y Salado en Santa
Fe (fuente: Secretaría de Recursos Hídricos de Santa Fe / Prefectura Naval / INA) como base de
ubicación y rangos de alerta creíbles. Las coordenadas son aproximadas (centro de la
localidad) — para un showcase no requieren precisión de estación real.

| # | Código | Nombre | Río | Lat | Lon | Tipo | Alerta técnica ref. (m) |
|---|---|---|---|---|---|---|---|
| 1 | `PARANA-RECONQUISTA` | Reconquista | Paraná | -29.15 | -59.65 | RIO | ~5.1 |
| 2 | `PARANA-SANTAFE` | Puerto de Santa Fe | Paraná | -31.63 | -60.70 | RIO | ~5.3 |
| 3 | `PARANA-ROSARIO` | Rosario | Paraná | -32.95 | -60.64 | RIO | ~5.0 |
| 4 | `SALADO-SANJUSTO` | San Justo (R.P. 2) | Salado | -30.79 | -60.59 | RIO | ~9.0 |
| 5 | `SALADO-RECREO` | Recreo (R.P. 70) | Salado | -31.65 | -60.83 | RIO | ~4.7 |
| 6 | `SALADO-SANTOTOME` | Santo Tomé | Salado | -31.66 | -60.78 | RIO | ~4.7 |

3 puntos en el Paraná (norte/centro/sur de la provincia) + 3 en el Salado (curso medio y
tramo final antes de la confluencia) — cubre "diferentes alturas" en ambos ríos como pediste.

**Extensibilidad garantizada por diseño**: `Sensor.latitud`/`longitud` son campos libres
(double), sin restricción geográfica en el modelo ni en el dominio — agregar un sensor en
cualquier punto del país o del mundo es simplemente un alta más en `sensor-registry`, no
requiere cambios de código. El único dato "regional" es el dataset semilla de arranque, no
una limitación estructural.

---

## 10. Próximo paso sugerido

Con esta base, el siguiente paso natural es tomar cada servicio y formalizarlo como un
Contract de SDD-GL (BR-XXX / AC-XXX) empezando por `sensor-registry` (es la base de la que
dependen los demás) o por `data-simulator` (para tener datos de prueba cuanto antes).
