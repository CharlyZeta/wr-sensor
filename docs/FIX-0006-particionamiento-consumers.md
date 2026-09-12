# FIX-0006 — Particionamiento de consumers de `ingestion-service` por `sensorId`

> **PROMOCIONADO (2026-09-11):** este documento de backlog fue refinado en Gate y es hoy
> **`contracts/FIX-0005.md`** (Status: DRAFT / Mode: GATE). La serie autoritativa de IDs es
> `contracts/`, por eso el work item viaja como `FIX-0005` y no como `FIX-0006`.
> Defectos corregidos en la promoción: (1) `sequence` no existe en el payload y ningún AC lo
> usa; (2) el escalado con una cola única rompe `ultimaSeveridad` en memoria y pierde
> transiciones reales → pasó a ser criterio; (3) el orden por `sensorId` no es expresable con
> bindings de topic → se resolvió con consistent-hash exchange + binding e2e (publisher
> intacto); (4) los AC de escalado se reescribieron a afirmaciones deterministas (afinidad,
> ausencia de consumers duplicados) en lugar de "las 3 instancias procesan en paralelo".
> Este archivo queda como registro histórico del backlog.

**Status:** PROMOCIONADO a `contracts/FIX-0005.md`
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

> Ítems **resueltos por el humano (2026-09-11)** y trazados en el Ambiguity Log de
> `contracts/FIX-0005.md`; se conservan aquí como histórico.

- [x] Mecanismo concreto: RESUELTO = consistent-hash exchange (`x-consistent-hash`) con binding
  exchange-to-exchange desde `sensor.lecturas` y 4 colas fijas con peso `"1"`. Requiere
  habilitar el plugin `rabbitmq_consistent_hash_exchange` (viene con la distribución de
  RabbitMQ, no habilitado por defecto en `rabbitmq:3.13-management-alpine`) en compose e ITs.
- [x] Número de particiones default: RESUELTO = **4** (configurable por entorno).

## 6. Completion Map

- [ ] BR-01 → AC-01
- [ ] BR-02 → AC-02
- [ ] BR-03 → AC-02
- [ ] BR-04 → AC-03
