# FIX-0004 — Validación de rango físico de lecturas en `ingestion-service`
> **PROMOCIONADO Y RESUELTO (2026-09-11):** este doc de backlog es el origen de
> **`contracts/FIX-0004.md`** (Status: RESOLVED, 16/16 ✅) — mismo tema, mismo número. Se
> conserva como registro histórico; el campo `calidad.estado` del emisor quedó como
> compatibilidad hacia adelante (BR-007 de ese contrato) y el versionado del payload sigue
> pendiente en `docs/FIX-0002-schema-versionado-lecturas.md` (a renumerar como FIX-0006).
**Status:** PROMOCIONADO a `contracts/FIX-0004.md` (RESOLVED)
**Mode:** GATE
**Servicio(s) afectado(s):** `ingestion-service`
**Relacionado:** FIX-0002 (usa `calidad.estado`), FEAT-0011, FEAT-0012 (alerting)
**Depende de:** FIX-0002 (necesita el campo `calidad` en el evento)

## 1. Contexto

Hoy cualquier valor numérico que llegue como `medicion.valor` se persiste y puede disparar
severidad/alerta, incluidos valores físicamente imposibles (ej. altura negativa, o muy por
encima del máximo plausible del río). Esto es tolerable con `data-simulator`, pero es una
falla de dominio real si el origen es hardware físico con ruido o falla de sensor.

## 2. Business Rules (BR)

- **BR-01**: Cada tipo de medición (`altura_agua`) tiene un rango físico válido configurable
  (mínimo/máximo), no hardcodeado en el dominio.
- **BR-02**: Si `medicion.valor` está fuera del rango configurado, `ingestion-service` debe
  marcar la lectura con `calidad.estado = "ERROR_SENSOR"` en la persistencia, aunque el
  evento entrante haya llegado con otro estado.
- **BR-03**: Una lectura marcada `ERROR_SENSOR` **no** debe participar del cálculo de
  severidad ni disparar evaluación de histéresis en `alerting-service`.
- **BR-04**: Una lectura marcada `ERROR_SENSOR` sí debe persistirse (para auditoría/debug),
  pero excluida de los continuous aggregates usados por dashboards, si estos ya existen.
- **BR-05**: El rango físico por sensor debe poder tener un override específico (algunos
  sensores en distinta ubicación pueden tener rangos distintos), con fallback al rango
  global del tipo de medición si no hay override.

## 3. Acceptance Criteria (AC)

- **AC-01** (BR-01): Dado un rango configurado de `-1.0` a `15.0` metros para
  `altura_agua`, cuando se valida una lectura de `3.4`, entonces se considera dentro de rango.
- **AC-02** (BR-02): Dado el mismo rango, cuando llega una lectura de `-50.0`, entonces se
  persiste con `calidad.estado = "ERROR_SENSOR"` independientemente del estado original
  del evento.
- **AC-03** (BR-03): Dado una lectura persistida como `ERROR_SENSOR`, cuando
  `ingestion-service` decide si publica a `sensor.alertas`, entonces no la incluye en el
  cálculo de severidad.
- **AC-04** (BR-05): Dado un sensor con override de rango `0.0` a `8.0`, cuando llega una
  lectura de `9.5` para ese sensor, entonces se marca `ERROR_SENSOR` aunque `9.5` esté
  dentro del rango global del tipo de medición.

## 4. Fuera de alcance

- Notificación proactiva de sensor con lecturas erróneas repetidas (posible FIX futuro:
  "sensor degradado" tras N lecturas `ERROR_SENSOR` consecutivas).
- Exclusión de continuous aggregates existentes (BR-04) si estos aún no están implementados
  — confirmar en Gate si aplica ya o queda como TODO explícito.

## 5. Ambiguity Log

- [ ] ¿Los rangos físicos viven en `sensor-registry` (config por sensor, fuente de verdad
  ya existente) o en configuración propia de `ingestion-service`? — **pendiente, impacta
  si este Contract requiere cambios en dos servicios o uno solo.**
- [ ] ¿BR-04 (exclusión de aggregates) es parte de este Contract o un Contract separado
  porque depende del estado actual de los continuous aggregates? — **pendiente.**

## 6. Completion Map

- [ ] BR-01 → AC-01
- [ ] BR-02 → AC-02
- [ ] BR-03 → AC-03
- [ ] BR-04 → (sin AC hasta resolver Ambiguity Log)
- [ ] BR-05 → AC-04
