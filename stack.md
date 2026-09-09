# Stack — WR-Sensor

Referencia completa: `docs/water-monitoring-spec.md` y skill `water-monitoring-stack`
(regla dura del proyecto: no proponer alternativas sin pedido explícito).

## Lenguaje / runtime
- Java 25
- Spring Boot 4.1 / Spring Framework 7

## Web
- Spring **WebFlux** sobre Netty — **cero MVC bloqueante**
- Validación reactiva (Bean Validation + Reactor)

## Persistencia
- **R2DBC** — cero JPA / Hibernate / JDBC bloqueante
- **TimescaleDB** (Postgres) → hypertable para `lectura` (raw, 90 días sin comprimir;
  compresión automática desde día 8; continuous aggregates horario y diario)
- **Postgres** (mismo motor, schema separado) → metadata: `sensor`, `usuario`,
  `config_alerta`

## Cache / estado
- **Redis** con **Lettuce reactivo** → última lectura por sensor, cache de config
  de sensor para reducir round-trips a Postgres

## Mensajería
- **RabbitMQ** + **Reactor RabbitMQ** (cliente reactivo, no `RabbitTemplate` clásico)
- Exchanges: `sensor.lecturas` (topic, key `lectura.{sensorId}`),
  `sensor.alertas` (topic, key `alerta.{severidad}`)
- DLQ por cada cola de consumo. Reintentos y DLQ **siempre configurables vía
  `application.yml`**, nunca hardcodeados:
  `messaging.retry.{max-attempts,initial-backoff,multiplier,max-backoff}` +
  `messaging.dead-letter.exchange`

## Auth
- JWT + roles (`ADMIN`, `VIEWER`) emitido por `sensor-registry` (sin OAuth externo en v1)
- Spring Security con `WebFilter` reactivo (no `servletFilter` bloqueante)

## Frontend
- React
- Mapa: Leaflet o MapLibre
- Dashboards

## Build / orquestación
- Maven (multi-módulo, un módulo por servicio)
- Docker Compose para desarrollo local
- Manifiestos Kubernetes: solo documentación futura, no implementados en v1

## Arquitectura interna (por servicio — hexagonal)
```
service/
├── domain/                 # entidades, value objects, reglas — sin imports de Spring
├── application/
│   ├── ports/in/           # casos de uso expuestos
│   └── ports/out/          # lo que el dominio necesita de infra
└── infrastructure/
    ├── adapters/in/        # controllers WebFlux, consumers RabbitMQ
    └── adapters/out/       # repos R2DBC, publishers, cliente Redis
```

- `domain` y `application` **nunca** importan anotaciones de infraestructura
- Comunicación entre microservicios: solo **eventos (RabbitMQ)** o **API REST**
  — nunca acceso directo a la base de otro servicio

## Modelo de alertas
- Bandas: `NORMAL`, `WARNING`, `CRITICAL`
- **Histéresis**: subir severidad = inmediato; bajar = requiere permanecer en la
  banda inferior más de `histeresis` (en la misma unidad que la medida) antes
  de confirmar. Evita flapping en el borde.
- `AlertaEvento` se publica **solo** en cambio confirmado de severidad

## Paginación
- **Keyset/cursor** (nunca `OFFSET`); cursor = timestamp, opaco para el cliente
- `limit` configurable, default 100, máximo 1000

## Datos semilla
- 6 sensores reales (Paraná + Salado, Santa Fe) — ver `docs/water-monitoring-spec.md` §9.5
- Modelo `Sensor` sin restricción geográfica (lat/lon libres) — agregar sensor
  en cualquier ubicación = alta de datos, no cambio de código

## Testing
- JUnit 5 + Reactor Test (`StepVerifier`) + Testcontainers (R2DBC, RabbitMQ, Redis)
- Para WebFlux: `WebTestClient`
- Para contracts (mensajería): verificar exchange/queue/routing-key contra fixture

## Reglas duras para los agentes
1. **Cero mezcla bloqueante/no bloqueante**. Antes de sumar una dependencia,
   verificar que exista variante reactiva. Si no existe → escalar al humano,
   nunca resolver con `.block()`.
2. **Hexagonal estricto**: tests de `domain` no importan Spring ni Reactor (en las
   reglas puras); tests de `application` usan puertos falsos (fakes), no mocks
   de framework.
3. **Completitud viene del Contract** (Completion Map), no de una cobertura
   arbitraria. Si un criterio no es testeable, el `tester-agent` reporta
   `NO ESCRIBIBLE` y el `reviewer-agent` decide si es bug o ambigüedad.
4. **Stack locked**: no proponer alternativas al stack anterior sin pedido
   explícito del humano.
