# SDD-GL Contract Format

Un Contract es la spec ejecutable de un work item.
Legible para el humano en modo Gate, parseable por el Loop para inferir criterios de completitud.

---

## Formato de archivo

```
# CONTRACT: [nombre del work item]
# ID: [FEAT|FIX]-XXXX
# Status: DRAFT | APPROVED | RESOLVED
# Mode: GATE | LOOP
# Gate-Mode: EXPRESS | STRICT

## Intent
<!-- Qué problema resuelve. Una o dos oraciones. -->
<!-- El Loop no lee esta sección para inferir criterios. -->

## Use Case
**Actor:**
**Goal:**

### Main Flow
1.
2.
3.

### Alternative Flows
- AF-01: [condición] → [resultado]

## Business Rules
- BR-001: [invariante verificable]

## Acceptance Criteria
- AC-001: GIVEN [contexto] WHEN [acción] THEN [resultado esperado]

## Entities Affected
- [Entidad]: [atributos relevantes o invariantes]

## Ambiguity Log
- [ ]

## Completion Map
# generated: [timestamp]
# [nombre]|[test-id]|[agente]|[status]
```

---

## Tabla de inferencia de criterios

| Elemento en la spec        | Criterio inferido                         |
|----------------------------|-------------------------------------------|
| Main Flow con N pasos      | 1 integration test mínimo                 |
| Alternative Flow AF-XX     | 1 unit test por cada AF-XX                |
| Business Rule BR-XXX       | 1 unit test de validación por cada BR-XXX |
| Acceptance Criteria AC-XXX | 1 assertion verificable por cada AC-XXX   |

---

## Formato del Completion Map

Cada línea del mapa sigue el formato:
```
# [nombre]|[test-id]|[agente]|[status]
```

Donde status es uno de:
- `❌` — pendiente
- `⏳` — en ejecución (si aparece al arrancar = proceso interrumpido, reanudar desde ahí)
- `✅` — completado

El Loop escribe el status en disco **antes** y **después** de ejecutar cada ítem.
Si al arrancar hay ítems `⏳`, el proceso fue interrumpido: reanudar desde el primero `⏳`.

---

## Regla del HO-Gate

El humano es el único que puede cambiar:
- `Status: DRAFT → APPROVED`
- `Mode: GATE → LOOP`

El proceso no modifica estos campos solo.
La combinación `Status: APPROVED + Mode: LOOP` es el único trigger del Loop.
