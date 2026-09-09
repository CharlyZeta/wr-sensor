---
name: water-monitoring-stack
description: Reglas fijas de arquitectura y stack para el proyecto Water Level Monitoring Platform (sensores de altura de agua). Usar SIEMPRE que se cree, edite o revise código de cualquiera de los microservicios (sensor-registry, data-simulator, ingestion-service, alerting-service, query-api) o del frontend. Cubre: no mezclar bloqueante/no bloqueante, estructura hexagonal, convenciones de paginación/retención, modelo de alertas, y contratos de mensajería.
---

# Water Level Monitoring Platform — Reglas del proyecto

Referencia completa: `docs/water-monitoring-spec.md` en la raíz del repo. Este skill resume las
reglas que Claude debe aplicar sin que se le repitan en cada prompt.

## Stack fijo (no proponer alternativas sin que el usuario lo pida explícitamente)

- Java 25, Spring Boot 4.1 / Spring Framework 7
- Spring WebFlux (nunca Spring MVC bloqueante)
- R2DBC (nunca JPA/Hibernate/JDBC bloqueante)
- TimescaleDB (Postgres) para históricos, Postgres para metadata
- Redis vía Lettuce reactivo
- RabbitMQ vía Reactor RabbitMQ (nunca RabbitTemplate clásico bloqueante)
- React en el frontend
- Docker Compose para orquestación local

## Regla dura: cero mezcla bloqueante/no bloqueante

Antes de agregar cualquier librería o dependencia nueva, verificar que tenga variante reactiva.
Si una tarea "parece" requerir código bloqueante (librería sin soporte reactivo), señalarlo
explícitamente al usuario en vez de resolverlo silenciosamente con `.block()` o similar.

## Arquitectura hexagonal — esqueleto obligatorio por servicio

```
service/
├── domain/              # entidades, value objects, reglas de negocio — sin imports de Spring
├── application/
│   ├── ports/in/         # casos de uso expuestos
│   └── ports/out/        # lo que el dominio necesita de infra
└── infrastructure/
    ├── adapters/in/      # controllers WebFlux, consumers RabbitMQ
    └── adapters/out/     # repos R2DBC, publishers, cliente Redis
```

- `domain` y `application` no dependen de anotaciones de infraestructura.
- Comunicación entre microservicios: solo eventos (RabbitMQ) o API REST. Nunca acceso directo
  a la base de datos de otro servicio.

## Modelo de alertas

Tres bandas: `NORMAL`, `WARNING`, `CRITICAL`, con **histéresis** al degradar severidad (debe
permanecer en la banda inferior más de `histeresis` unidades antes de confirmar el cambio).
Subir de severidad se confirma inmediato; bajar requiere el margen de histéresis.

## Mensajería (RabbitMQ)

- Exchange `sensor.lecturas` (topic, routing key `lectura.{sensorId}`)
- Exchange `sensor.alertas` (topic, routing key `alerta.{severidad}`)
- Reintentos y DLQ **siempre configurables** vía `application.yml`, nunca hardcodeados:
  `messaging.retry.max-attempts`, `initial-backoff`, `multiplier`, `max-backoff`,
  `messaging.dead-letter.exchange`.

## Persistencia histórica (TimescaleDB)

- Raw: hypertable, 90 días sin comprimir, compresión automática desde el día 8.
- Continuous aggregates horario y diario para rangos largos.
- Reglas de qué fuente usar según rango de consulta van en `query-api`, no en el cliente.

## Paginación

Siempre **keyset/cursor**, nunca `OFFSET`, para cualquier endpoint de históricos. `limit`
configurable con default 100 y máximo 1000.

## Dataset semilla (data-simulator)

6 sensores iniciales sobre estaciones reales de los ríos Paraná y Salado en Santa Fe (ver
spec para código, nombre, coordenadas). El modelo `Sensor` no tiene restricción geográfica —
agregar sensores en cualquier ubicación es un alta de datos, no un cambio de código.

## Fuera de alcance en v1 (no implementar salvo pedido explícito)

Spring AI / análisis de logs con IA, observabilidad avanzada (Grafana/tracing detallado),
notificaciones por email/SMS, manifiestos Kubernetes.
