# /sdd-fix

## Descripción
Inicia un work item de tipo FIX en SDD-GL.
Gate mínimo: solo requiere Reproduction Steps y un AC-001 que defina el comportamiento correcto.

## Uso
```
/sdd-fix "descripción del bug"
```

## Diferencias respecto a /sdd-feature

| Elemento            | FEAT              | FIX                              |
|---------------------|-------------------|----------------------------------|
| Use Case completo   | requerido         | no requerido                     |
| Alternative Flows   | recomendado       | omitir salvo que aplique         |
| Business Rules      | requerido         | solo si el bug es de regla de negocio |
| AC mínimo           | N criterios       | 1 AC-XXX obligatorio             |
| Pasos de Gate       | múltiples         | uno solo                         |
| Completion Map mín. | todos los ítems   | 1 regression test + AC cubiertos |

## Comportamiento

1. **Generar ID**: contar archivos existentes en `contracts/` con prefijo FIX → FIX-XXXX
2. **Crear** `contracts/FIX-XXXX.md` con:
   - `Status: DRAFT` y `Mode: GATE`
   - `Intent` con descripción del bug
   - Sección exclusiva de FIX: Reproduction Steps, Current Behavior, Expected Behavior
   - `AC-001` vacío con guía GIVEN/WHEN/THEN (debe fallar hoy, pasar después del fix)
   - `Ambiguity Log`: vacío
   - `Completion Map`: placeholder
3. **Notificar** al humano con ruta y qué revisar
4. **Detener** — no continuar sin intervención humana

## Template de Contract para FIX

```markdown
# CONTRACT: [descripción del bug]
# ID: FIX-XXXX
# Status: DRAFT
# Mode: GATE

## Intent
[Descripción del bug y comportamiento esperado]

## Reproduction Steps
1.
2.
3.

## Current Behavior
[Qué pasa hoy]

## Expected Behavior
[Qué debería pasar]

## Business Rules
<!-- Solo si el bug involucra una regla de negocio -->

## Acceptance Criteria
- AC-001: GIVEN [estado que produce el bug] WHEN [acción] THEN [resultado correcto]

## Ambiguity Log
- [ ]

## Completion Map
(se construye al pasar a LOOP)
```

## Output esperado

```
✅ Contract creado: contracts/FIX-0001.md

📋 Fix rápido — revisá solo:
   - [ ] Reproduction Steps completos
   - [ ] AC-001: GIVEN [estado bugueado] WHEN [acción] THEN [resultado correcto]

⏸️  Cuando estés listo: cambiá Status: APPROVED y Mode: LOOP
```

## Lo que este comando no hace
- No reproduce el bug por su cuenta
- No busca la causa raíz antes de que el humano apruebe el Contract
- No omite el Gate aunque el fix parezca obvio
