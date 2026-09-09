# /sdd-feature

## Descripción
Inicia un work item de tipo FEAT en SDD-GL.
Crea el Contract en DRAFT, entra en modo Gate y espera revisión humana.

## Uso
```
/sdd-feature "descripción breve de la feature"
```

## Comportamiento

1. **Generar ID**: contar archivos existentes en `contracts/` con prefijo FEAT → FEAT-XXXX
2. **Crear** `contracts/FEAT-XXXX.md` con:
   - `Status: DRAFT` y `Mode: GATE`
   - `Intent` inferido de la descripción recibida
   - `Use Case` con Main Flow de 3 pasos básicos inferidos
   - `Alternative Flows`, `Business Rules`, `Acceptance Criteria`: vacíos con comentarios guía
   - `Ambiguity Log`: vacío (`- [ ]`)
   - `Completion Map`: placeholder `(se construye al pasar a LOOP)`
3. **Notificar** al humano con ruta del Contract y secciones pendientes
4. **Detener** — no continuar sin intervención humana

## Output esperado

```
✅ Contract creado: contracts/FEAT-0001.md

📋 Revisá y completá antes de aprobar:
   - [ ] Alternative Flows (si aplica)
   - [ ] Business Rules (BR-XXX como invariante verificable)
   - [ ] Acceptance Criteria (AC-XXX en formato GIVEN/WHEN/THEN)

⏸️  Cuando estés listo: cambiá Status: APPROVED y Mode: LOOP
```

## Lo que este comando no hace
- No genera código
- No completa el Contract por su cuenta más allá del Use Case básico
- No avanza a Loop
- No cambia Status ni Mode
