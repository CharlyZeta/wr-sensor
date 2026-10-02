# SDD-GL Loop Protocol

## Propósito

El modo Loop opera cuando el Contract está en `Status: APPROVED` y `Mode: LOOP`.
Ejecuta de forma autónoma hasta que todos los criterios inferidos están cubiertos
o detecta una ambigüedad que requiere intervención humana.

---

## Cuándo se activa

- Cuando el humano cambia `Status: APPROVED` y `Mode: LOOP` en el Contract
- Al reanudarse tras una resolución humana (lee el mapa existente, no lo reconstruye)
- **Nunca se autoactiva**

---

## Inferencia de criterios de salida

Al arrancar, el Loop lee el Contract y construye el Completion Map.
**Si ya existe un Completion Map en el Contract, lo usa tal cual — no lo reconstruye.**

Mapeo de inferencia:

| Elemento         | Test inferido                   | Agente        |
|------------------|---------------------------------|---------------|
| Main Flow        | integration-test:[ID]-main      | coder+tester  |
| AF-XX            | unit-test:[ID]-afXX             | tester        |
| BR-XXX           | unit-test:[ID]-brXXX            | tester        |
| AC-XXX           | assertion:[ID]-acXXX            | tester        |

Formato de cada línea en el Completion Map:
```
# [nombre]|[test-id]|[agente]|[❌|⏳|✅]
```

---

## Persistencia de progreso — CRÍTICO

El Loop escribe el estado en disco **antes y después** de ejecutar cada ítem y capa.

Secuencia por ítem y capa:
1. Seleccionar primer ítem `❌` o `⏳` del Completion Map
2. Escribir `⏳` en el Contract → **persistir en disco**
3. Delegar al agente según la capa (`functional` → `tester`, `static`/`security`/`arch` → `verifier`)
4. Si el agente retorna `BLOCKED` o `NOT_WRITABLE` → escalar de inmediato a Gate (ver sección Ambigüedad)
5. Si la verificación pasa → escribir `✅` → **persistir en disco** → continuar
6. Si la verificación falla:
   - Si es un hallazgo acotado (lint, tipado simple, assertion desfasada) → invocar `solve-agent` (máx. 3 intentos de reparación rápida).
   - Si es un fallo funcional complejo → invocar `coder-agent` para corregir implementación (máx. 3 intentos).
   - Si tras 3 intentos sigue fallando → invocar `reviewer-agent` (análisis de ambigüedad vs. 1 reintento guiado).

Si al arrancar hay ítems `⏳` en el mapa, el proceso fue interrumpido:
reanudar desde el primer `⏳` sin re-ejecutar los `✅`.

**El Loop nunca reconstruye el Completion Map si ya existe uno en el Contract.**

---

## Ciclo de ejecución

```
LOOP:
  Si Completion Map existe en Contract:
    cargar mapa del disco (no reconstruir)
    si hay ítems ⏳ → reanudar desde el primero
  Si no existe:
    construir Completion Map multicapa desde Contract
    persistir en disco

  ReviewerRetries = 0

  WHILE hay ítems ❌ o ⏳:
    ítem = primer ❌ o ⏳ del mapa
    escribir ⏳ en Contract → persistir
    intentos = 0

    WHILE intentos < 3:
      delegar según capa:
        capa functional → tester-agent (+ coder-agent si Main Flow)
        capas static / security / arch → verifier-agent

      si el agente responde BLOCKED o NOT_WRITABLE:
        escribir en Ambiguity Log: "- [ ] [ID-ítem]: [motivo del bloqueo]"
        escribir ❌ en Contract → persistir
        cambiar Mode: GATE y Status: DRAFT → persistir
        notificar al humano y DETENER

      si verificación pasa:
        escribir ✅ en Contract → persistir
        break

      si verificación falla:
        intentos++
        si es hallazgo acotado (lint/tipos/typos):
          solve-agent repara código
        sino:
          coder-agent corrige implementación

    si intentos == 3 y sigue fallando:
      si ReviewerRetries >= 1:
        escribir en Ambiguity Log: "- [ ] [ID-ítem]: Falla persistente tras reintentos guiados."
        escribir ❌ en Contract → persistir
        cambiar Mode: GATE y Status: DRAFT → persistir
        notificar al humano y DETENER
      sino:
        reviewer-agent analiza causa
        si es ambigüedad real:
          escribir en Ambiguity Log: "- [ ] [ID-ítem]: [descripción del bloqueo]"
          escribir ❌ en Contract → persistir
          cambiar Mode: GATE y Status: DRAFT → persistir
          notificar al humano y DETENER
        si es error de implementación (RETRY):
          ReviewerRetries++
          intentos = 0
          coder-agent aplica la sugerencia del reviewer
          continue

  COMPLETION REPORT (actualiza .sdd/metrics.json)
```
```

---

## Criterios de ambigüedad

El Loop considera ambigüedad cualquiera de estas condiciones:

- El test no puede escribirse porque la spec no define el resultado esperado
- La implementación requiere una decisión de diseño que la spec no contempla
- Dos BR-XXX se contradicen entre sí
- Un AC-XXX referencia datos o fuentes no definidas en el Contract
- Un test pasa en aislamiento pero rompe un test existente no contemplado en la spec

Ante ambigüedad:
1. Escribir en Ambiguity Log: `- [ ] [ID-ítem]: [descripción precisa del bloqueo]`
2. Escribir `❌` con nota en Completion Map → persistir
3. Cambiar `Mode: GATE` y `Status: DRAFT` → persistir
4. Detener ejecución
5. Notificar al humano con Completion Map completo y punto de bloqueo

**El Loop nunca adivina. Ante ambigüedad, siempre persiste y vuelve a Gate.**

---

## Reanudación tras resolución humana

Al reanudarse después de que el humano resuelve una ambigüedad y re-aprueba:
1. Leer Completion Map del Contract (ya tiene `✅` de ciclos anteriores)
2. Leer Ambiguity Log para entender qué cambió
3. Continuar desde el primer ítem que no sea `✅`
4. Los ítems `✅` **no se re-ejecutan**

---

## Glass Box Loop: Registro de Auditoría y Telemetría

Para eliminar la opacidad ("caja negra") del modo Loop, el orquestador genera y actualiza un archivo de auditoría transparente para cada ejecución en:
`.sdd/runs/[ID]-[timestamp].md`

### Estructura del Registro de Auditoría:
Cada paso del bucle añade una entrada detallada:

```markdown
# EXECUTION AUDIT: [ID]
# Started: [timestamp]

## Step: [Item-ID] (ej. BR-001) | Attempt: [N/3]
- **Agent Invoked**: `sdd-coder-agent` / `sdd-tester-agent`
- **Context Inspected**: [archivos leídos por el agente]
- **Diff Generated**:
  ```diff
  [diff block o código añadido]
  ```
- **Test Command**: `[comando ejecutado, ej. npm test / mvn test]`
- **Runner Output**: `[salida exacta del test runner]`
- **Result**: `✅ PASS` | `❌ FAIL` | `⚠️ BLOCKED/NOT_WRITABLE`
- **Decision Rationale**: `[razón técnica del cambio o sugerencia del reviewer]`
```

Este registro permite al desarrollador inspeccionar en cualquier momento exactamente qué hizo la IA, qué archivos leyó y por qué tomó cada decisión.

---

## Completion Report

Cuando todos los ítems están `✅`:

```
COMPLETION REPORT: [ID]
  Status:              RESOLVED
  Completion Map:      N/N ✅
  Tests generados:     N
  Criterios cubiertos: N/N
  Ciclos Gate/Loop:    N
  Ambigüedades:        N escaladas, N resueltas
```

Cambia `Status: RESOLVED` → persiste. Notifica al humano. El proceso termina.

---

## Lo que Loop nunca hace

- Solicitar aprobación durante la ejecución
- Modificar el Contract más allá de Completion Map, Ambiguity Log, Status y Mode
- Asumir criterios que no están en el Contract
- Terminar con ítems `❌` sin escalar a Gate
- Reconstruir el Completion Map si ya existe uno en el Contract
- Cambiar `Mode: GATE` sin escribir primero en Ambiguity Log y persistir el mapa
