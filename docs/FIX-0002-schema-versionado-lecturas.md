# FIX-0002 — Versionado y enriquecimiento del schema de `sensor.lecturas`

**Status:** DRAFT **Mode:** GATE **Servicio(s) afectado(s):** `data-simulator` (publisher), `ingestion-service` (consumer), `sensor-registry` (referencia de contrato) **Relacionado:** `stack.md` §Mensajería, FEAT-0010, FEAT-0011 **Depende de:** ninguno

## 1\. Contexto

El payload actual del evento `lectura.{sensorId}` en el exchange `sensor.lecturas` no tiene versión de schema, ni campos de calidad de dato, ni metadata de dispositivo. Esto bloquea: evolución segura del contrato de mensajería, idempotencia en `ingestion-service`, y trazabilidad end-to-end lectura → severidad → alerta.

Este Contract solo cubre el **cambio de estructura del evento y su consumo compatible**. No cubre outbox/idempotencia real (ver FIX-0003) ni validación de rango físico (ver FIX-0004).

## 2\. Business Rules (BR)

- **BR-01**: Todo evento publicado en `sensor.lecturas` debe incluir `schemaVersion` (string, ej. `"1.0"`).  
- **BR-02**: Todo evento debe incluir `eventId` (UUID v4) único por publicación, generado por el publisher.  
- **BR-03**: Todo evento debe incluir `sequence` (long monotónico creciente por `sensorId`), generado por el publisher.  
- **BR-04**: `timestampUtc` debe serializarse en formato ISO-8601 con offset explícito (`Instant`/`OffsetDateTime`), nunca datetime naive.  
- **BR-05**: El evento debe incluir un objeto `calidad` con `estado` (`OK` | `SOSPECHOSA` | `ERROR_SENSOR`), `confianza` (double 0–1) y `codigosAnomalias` (array, puede ser vacío).  
- **BR-06**: El evento debe incluir un objeto `dispositivo` con `firmwareVersion`, `bateriaPct` (nullable), `rssiDbm` (nullable), `origen` (string, identifica el publisher).  
- **BR-07**: `ingestion-service` debe rechazar (log \+ DLQ) cualquier evento con `schemaVersion` no soportado por la versión actual del consumer, sin romper el consumo del resto de la cola.  
- **BR-08**: `ingestion-service` debe ser compatible hacia atrás con el payload plano previo a este Contract durante la ventana de migración (ver Fuera de alcance si no aplica).

## 3\. Acceptance Criteria (AC)

- **AC-01** (BR-01, BR-02, BR-03): Dado que `data-simulator` publica una lectura, cuando se inspecciona el mensaje en RabbitMQ, entonces contiene `schemaVersion`, `eventId` único y `sequence` incremental respecto a la lectura anterior del mismo `sensorId`.  
- **AC-02** (BR-04): Dado un evento publicado, cuando se deserializa `timestampUtc`, entonces el valor es interpretable sin ambigüedad de zona horaria en cualquier locale.  
- **AC-03** (BR-05): Dado un evento con `calidad.estado = "OK"`, cuando `ingestion-service` lo consume, entonces persiste la lectura marcada como válida.  
- **AC-04** (BR-06): Dado un evento con `dispositivo.bateriaPct = null`, cuando se persiste, entonces el campo se acepta como nulo sin fallar la deserialización.  
- **AC-05** (BR-07): Dado un evento con `schemaVersion = "99.0"` (no soportado), cuando `ingestion-service` lo consume, entonces el mensaje se enruta a la DLQ y el consumer continúa procesando el resto de la cola sin caerse.  
- **AC-06** (BR-08): Dado un evento con el payload plano anterior (sin `schemaVersion`), cuando `ingestion-service` lo consume durante la ventana de compatibilidad, entonces se procesa correctamente asumiendo `schemaVersion = "0.0"` implícito.

## 4\. Fuera de alcance

- Outbox pattern / garantías de entrega exactly-once (FIX-0003).  
- Validación de rango físico del valor de la medición (FIX-0004).  
- Cambios en `alerting-service` o `query-api` más allá de deserializar el nuevo schema si ya consumen el evento (a confirmar en Gate si están en el path de consumo directo).

## 5\. Ambiguity Log

- [ ] ¿La ventana de compatibilidad hacia atrás (BR-08) tiene fecha de expiración o es indefinida hasta v2 del schema? — **pendiente de decisión humana.**  
- [ ] ¿`sequence` se persiste y se usa para detectar gaps, o solo viaja en el evento sin uso aguas abajo en esta iteración? — **pendiente.**

## 6\. Completion Map

- [ ] BR-01 → AC-01  
- [ ] BR-02 → AC-01  
- [ ] BR-03 → AC-01  
- [ ] BR-04 → AC-02  
- [ ] BR-05 → AC-03  
- [ ] BR-06 → AC-04  
- [ ] BR-07 → AC-05  
- [ ] BR-08 → AC-06

&nbsp;