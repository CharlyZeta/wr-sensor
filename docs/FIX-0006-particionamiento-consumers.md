# FIX-0006 — Particionamiento de consumers de `ingestion-service` por `sensorId`

**Status:** DRAFT
**Mode:** GATE
**Servicio(s) afectado(s):** `ingestion-service`, infraestructura RabbitMQ
**Relacionado:** `stack.md` §Mensajería
**Depende de:** FIX-0002 (evento con `eventId`/`sequence` estable), FIX-0003 (idempotencia
ya resuelta antes de paralelizar consumo)

## 1. Contexto

Hoy `ingestion-service` consume de una cola vinculada al exchange topic `sensor.lecturas`
con un único consumer lógico. Agregar más sensores (más allá de los 6 iniciales de Paraná/
Salado) eventualmente satura throughput porque no hay paralelismo real por partición.

## 2. Business Rules (BR)

- **BR-01**: Las lecturas de un mismo `sensorId` deben procesarse en orden relativo entre sí
  (no importa el orden global entre sensores distintos).
- **BR-02**: El particionamiento debe permitir escalar horizontalmente el número de
  instancias de `ingestion-service` sin requerir cambios en el publisher
  (`data-simulator`) más allá de la routing key ya existente (`lectura.{sensorId}`).
- **BR-03**: La cantidad de particiones/colas debe ser configurable, no hardcodeada, para
  poder ajustar el nivel de paralelismo sin redeploy de código.
- **BR-04**: Un sensor específico siempre debe enrutarse a la misma partición mientras la
  configuración de particiones no cambie (consistencia del hash).

## 3. Acceptance Criteria (AC)

- **AC-01** (BR-01): Dado un sensor que publica 3 lecturas consecutivas con `sequence`
  1, 2, 3, cuando `ingestion-service` las consume, entonces se procesan y persisten en ese
  mismo orden.
- **AC-02** (BR-02, BR-03): Dado un `docker compose up --scale ingestion-service=3`, cuando
  se publican lecturas de distintos sensores, entonces las 3 instancias procesan en paralelo
  sin duplicar trabajo sobre el mismo `sensorId`.
- **AC-03** (BR-04): Dado un `sensorId` fijo, cuando se publican lecturas en momentos
  distintos, entonces todas terminan en la misma partición/cola mientras el número de
  particiones no cambie.

## 4. Fuera de alcance

- Rebalanceo automático de particiones al cambiar el número de particiones en caliente
  (asumir downtime breve o reinicio coordinado para ese caso).
- Cambios en `alerting-service`/`query-api` (consumen de forma distinta, evaluar si les
  aplica en Contract separado).

## 5. Ambiguity Log

- [ ] Mecanismo concreto: ¿consistent-hash exchange plugin de RabbitMQ, o N colas fijas con
  routing key modulada por hash de `sensorId` en el binding? — **pendiente de decisión
  humana, impacta si se necesita un plugin adicional en la imagen de RabbitMQ.**
- [ ] Número de particiones default — **pendiente, depende de cuántos sensores se proyectan
  a mediano plazo.**

## 6. Completion Map

- [ ] BR-01 → AC-01
- [ ] BR-02 → AC-02
- [ ] BR-03 → AC-02
- [ ] BR-04 → AC-03
