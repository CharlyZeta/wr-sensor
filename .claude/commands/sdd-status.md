# /sdd-status

## Descripción
Muestra el estado actual de todos los Contracts del proyecto.

## Uso
```
/sdd-status
```

## Comportamiento

1. Leer todos los archivos `.md` en `contracts/`
2. Extraer de cada uno: ID, título, Status, Mode y Completion Map
3. Mostrar dashboard agrupado por Status con progreso del mapa

## Output esperado

```
📊 SDD-GL Status

🔴 DRAFT (Gate — esperando revisión)
   └── FEAT-0003: Alta de usuario con verificación de email

🟡 APPROVED (Loop en progreso)
   └── FEAT-0002: Transferencia internacional [8/11 ✅]

🟢 RESOLVED
   └── FEAT-0001: Registrar pago entre cuentas [12/12 ✅]
   └── FIX-0001: Fix validación de monto negativo [4/4 ✅]

Total: 4 contracts | 1 en Gate | 1 en Loop | 2 resueltos
```

## Lo que este comando no hace
- No modifica ningún Contract
- No arranca ningún proceso
- No cambia Status ni Mode
