# FIX-0007 — Circuit breaker en llamadas de `ingestion-service` a `sensor-registry`

> **PROMOCIONADO (2026-09-13):** este doc de backlog fue revisado en Gate y promocionado como
> **`contracts/FIX-0007.md`** (Status: DRAFT / Mode: GATE), con el alcance ampliado a
> **resiliencia del lookup de config** (y no sólo circuit breaker). Hallazgos del Gate que
> corrigieron el documento: (1) **la cache de config nunca expira** — un sensor desactivado o con
> bandas nuevas no se refleja jamás, y eso es un bug de negocio más grave que el circuit breaker;
> (2) **el token del registry nunca se refresca** — el registry valida `exp`, así que tras 1 h de
> uptime todo sensor no cacheado falla de forma permanente hasta reiniciar (hallazgo nuevo);
> (3) **BR-04 se apoyaba en un mecanismo inexistente**: `messaging.retry-max-attempts` no se usa en
> ningún lado (0 referencias), así que el camino de fallo real es sólo DLQ — se adopta el motivo
> específico `REGISTRY_UNAVAILABLE` y los reintentos quedan fuera de alcance; (4) **BR-05 requería
> `actuator`** (no está en ningún servicio y el starter no estaba en el `.m2`) → se decidió endpoint
> propio; (5) el circuit breaker se implementa **en el dominio** (Resilience4j sólo tenía el BOM
> cacheado y habría exigido descarga, rompiendo el build offline); (6) el doc citaba un "FIX-0008"
> que **no existe**. Decisiones humanas del 2026-09-13 registradas en el Ambiguity Log del
> contract (mecanismo propio, cache local con TTL + last-known-good, sólo DLQ, endpoint propio y
> umbrales moderados).
> Este archivo queda como registro histórico del backlog.

**Status:** PROMOCIONADO a `contracts/FIX-0007.md`
**Mode:** GATE (histórico)
**Servicio(s) afectado(s):** `ingestion-service`
**Relacionado:** `docker-compose.yml` (`REGISTRY_URL`, `REGISTRY_AUTH_EMAIL`)
**Depende de:** ninguno

## 1. Contexto

`ingestion-service` depende de `sensor-registry` vía HTTP (`REGISTRY_URL`) para obtener
config de sensores, autenticado con credenciales `viewer`. Si `sensor-registry` cae o
responde lento, hoy no hay evidencia de aislamiento: el riesgo es que `ingestion-service`
se bloquee esperando respuestas o consuma su pool de conexiones reactivo con requests
colgadas, degradando el consumo de RabbitMQ también.

## 2. Business Rules (BR)

- **BR-01**: Toda llamada HTTP de `ingestion-service` a `sensor-registry` debe tener timeout
  explícito configurable (nunca timeout infinito por default de librería).
- **BR-02**: Debe existir un circuit breaker (ej. Resilience4j reactivo) que abra el circuito
  tras N fallos/timeouts consecutivos configurables, evitando seguir golpeando a
  `sensor-registry` mientras está caído.
- **BR-03**: Con el circuito abierto, `ingestion-service` debe usar la última config de
  sensor conocida (cache local o Redis) en vez de fallar el procesamiento del mensaje
  completo, si existe una copia cacheada.
- **BR-04**: Si no hay copia cacheada disponible y el circuito está abierto, el mensaje debe
  reintentarse vía el mecanismo de retry/DLQ ya existente (`messaging.retry.*`), no
  perderse silenciosamente.
- **BR-05**: El estado del circuito (abierto/cerrado/semi-abierto) debe quedar expuesto en
  el endpoint de salud/métricas del servicio.

## 3. Acceptance Criteria (AC)

- **AC-01** (BR-01): Dado `sensor-registry` respondiendo con latencia artificial superior al
  timeout configurado, cuando `ingestion-service` llama, entonces la llamada falla por
  timeout en el tiempo configurado, no indefinidamente.
- **AC-02** (BR-02): Dado N fallos consecutivos configurados como umbral, cuando ocurren,
  entonces las siguientes llamadas no golpean la red y fallan rápido (circuito abierto).
- **AC-03** (BR-03): Dado un circuito abierto y una config de sensor previamente cacheada,
  cuando llega un mensaje para ese sensor, entonces se procesa usando la config cacheada
  sin error.
- **AC-04** (BR-04): Dado un circuito abierto sin cache disponible para un sensor nuevo,
  cuando llega un mensaje para ese sensor, entonces se enruta según la política de
  retry/DLQ existente, sin pérdida silenciosa.
- **AC-05** (BR-05): Dado el circuito en estado abierto, cuando se consulta el endpoint de
  salud/métricas, entonces refleja ese estado.

## 4. Fuera de alcance

- Circuit breaker en otras integraciones (ej. `query-api` → RabbitMQ) — evaluar como
  Contract separado si se detecta el mismo riesgo.
- Alertas operacionales cuando el circuito se abre (posible extensión de FIX-0008).

## 5. Ambiguity Log

- [ ] ¿La cache de config de sensor (BR-03) es la misma cache Redis ya usada para "última
  lectura", o requiere una entrada nueva de cache específica para config? — **pendiente,
  ya existe mención en `stack.md` de "cache de config de sensor para reducir round-trips",
  a confirmar si ya está implementada o es parte de este Contract.**
- [ ] Umbrales concretos (N fallos, timeout en ms) — **pendiente de decisión humana.**

## 6. Completion Map

- [ ] BR-01 → AC-01
- [ ] BR-02 → AC-02
- [ ] BR-03 → AC-03
- [ ] BR-04 → AC-04
- [ ] BR-05 → AC-05
