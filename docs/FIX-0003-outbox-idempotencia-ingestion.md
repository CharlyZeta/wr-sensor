# FIX-0003 — Outbox pattern e idempotencia en `ingestion-service`

**Status:** DRAFT
**Mode:** GATE
**Servicio(s) afectado(s):** `ingestion-service`
**Relacionado:** FIX-0002 (requiere `eventId`), FEAT-0011
**Depende de:** FIX-0002 (necesita `eventId` en el evento)

## 1. Contexto

`ingestion-service` consume `sensor.lecturas`, calcula severidad y persiste en TimescaleDB.
Hoy no hay garantía de que un mensaje redelivered por RabbitMQ (por caída del consumer entre
persistencia y ACK) no genere una lectura duplicada, ni evidencia de que la publicación
posterior a `sensor.alertas` esté atómicamente ligada a la persistencia.

## 2. Business Rules (BR)

- **BR-01**: Cada `eventId` procesado debe quedar registrado (tabla de deduplicación o
  constraint único) antes de considerarse persistido.
- **BR-02**: Si `ingestion-service` recibe un `eventId` ya procesado, debe descartarlo sin
  re-persistir ni recalcular severidad, y hacer ACK igualmente.
- **BR-03**: La persistencia de la lectura y el registro de deduplicación deben ocurrir en
  la misma transacción R2DBC.
- **BR-04**: La publicación del evento `sensor.alertas` (cuando corresponde) debe seguir el
  patrón outbox: se escribe una fila outbox en la misma transacción que la persistencia de
  la lectura; un publisher separado (poller o CDC) es responsable de emitir el evento a
  RabbitMQ y marcar la fila outbox como enviada.
- **BR-05**: Si el proceso cae después de commitear la transacción pero antes de que el
  publisher de outbox emita el evento, al reiniciar el publisher debe reintentar el envío
  sin duplicar entradas en la tabla outbox.
- **BR-06**: El registro de deduplicación debe tener una política de retención (no crecer
  indefinidamente) — a definir junto con la retención de `lectura` en TimescaleDB (90 días).

## 3. Acceptance Criteria (AC)

- **AC-01** (BR-01, BR-03): Dado un evento válido con `eventId` nuevo, cuando
  `ingestion-service` lo procesa, entonces la lectura y el registro de deduplicación
  quedan persistidos en la misma transacción.
- **AC-02** (BR-02): Dado un evento con `eventId` ya procesado previamente (redelivery),
  cuando `ingestion-service` lo consume de nuevo, entonces no se crea una segunda fila de
  lectura y el mensaje se ACKea.
- **AC-03** (BR-04): Dado que el cálculo de severidad determina que corresponde alerta,
  cuando se persiste la lectura, entonces se escribe una fila outbox en la misma transacción,
  sin publicar directamente a RabbitMQ desde el flujo de consumo.
- **AC-04** (BR-04, BR-05): Dado que existen filas outbox pendientes, cuando el publisher de
  outbox corre, entonces emite cada evento pendiente a `sensor.alertas` y marca la fila como
  enviada; si se ejecuta dos veces sobre la misma fila ya marcada, no la reenvía.
- **AC-05** (BR-05): Simulando una caída del proceso entre commit de transacción y ejecución
  del publisher (test de integración con Testcontainers), cuando el servicio reinicia,
  entonces el evento pendiente se emite exactamente una vez tras la recuperación.

## 4. Fuera de alcance

- Outbox en `sensor-registry` (evaluar como Contract separado si aplica).
- Cambios al schema del evento en sí (cubierto por FIX-0002).
- Estrategia de particionamiento de consumers (FIX-0006).

## 5. Ambiguity Log

- [ ] ¿El publisher de outbox es un `@Scheduled` poller reactivo simple, o se evalúa
  Debezium/CDC sobre TimescaleDB? — **pendiente de decisión humana (impacta complejidad
  de infraestructura).**
- [ ] Retención del registro de deduplicación (BR-06): ¿mismos 90 días que `lectura`, o un
  TTL más corto porque solo importa para redeliveries recientes? — **pendiente.**

## 6. Completion Map

- [ ] BR-01 → AC-01
- [ ] BR-02 → AC-02
- [ ] BR-03 → AC-01
- [ ] BR-04 → AC-03, AC-04
- [ ] BR-05 → AC-04, AC-05
- [ ] BR-06 → (sin AC hasta resolver Ambiguity Log)
