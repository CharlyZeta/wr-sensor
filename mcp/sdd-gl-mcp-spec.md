# SDD-GL Model Context Protocol (MCP) Server Specification

Esta especificación formal define el **Servidor MCP de SDD-GL (`sdd-gl-mcp`)**, permitiendo que cualquier cliente compatible con el estándar MCP (Cursor, Windsurf, VS Code Copilot, Claude Desktop/Code, Antigravity, Zed, OpenCode) ejecute la metodología Spec-Driven Development de forma 100% agnóstica.

---

## 1. Arquitectura del Servidor MCP

El servidor expone herramientas (`tools`) y recursos (`resources`) que operan directamente sobre el sistema de archivos del proyecto local (`contracts/`, `protocol/`, `stack.md`, `.sdd/runs/`).

```
┌─────────────────────────────────────────────────────────────┐
│                 Cualquier Cliente MCP                       │
│    (Cursor / Windsurf / Claude Code / Antigravity / Zed)    │
└──────────────────────────────┬──────────────────────────────┘
                               │ JSON-RPC (stdio / SSE)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   SDD-GL MCP Server                         │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Tools:                                                │  │
│  │ • sdd_create_contract   • sdd_step_loop               │  │
│  │ • sdd_validate_gate     • sdd_log_ambiguity           │  │
│  │ • sdd_get_status        • sdd_load_preset             │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────┘
                               │ I/O Local
                               ▼
┌─────────────────────────────────────────────────────────────┐
│  Proyecto: contracts/ | protocol/ | presets/ | .sdd/runs/   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Definición de Herramientas (Tools Schema)

### `sdd_create_contract`
Crea un nuevo archivo de contrato en `contracts/` con el ID correspondiente y modo Gate inicial.
- **Parameters**:
  - `title` (string, required): Título o descripción corta del work item.
  - `type` (string, enum: `["FEAT", "FIX"]`, required): Tipo de contrato.
  - `gate_mode` (string, enum: `["EXPRESS", "STRICT"]`, optional): Nivel de gobernanza (por defecto `EXPRESS` para FIX y `STRICT` para FEAT).
  - `intent` (string, optional): Descripción del problema y objetivo.

### `sdd_validate_gate`
Valida la completitud estructural y consistencia lógica interna de un contrato en modo DRAFT.
- **Parameters**:
  - `contract_id` (string, required): Identificador del contrato (ej. `FEAT-0001` o `FIX-0001`).
- **Returns**:
  - `status`: `"READY_FOR_APPROVAL"` | `"INCOMPLETE"` | `"CONTRADICTION_DETECTED"`
  - `inferred_criteria_count`: Número total de tests que inferirá el Loop.
  - `blocking_issues`: Lista de problemas o contradicciones detectadas.

### `sdd_step_loop`
Ejecuta de forma determinista un paso del Completion Map en modo Loop, emitiendo el log de auditoría (Glass Box).
- **Parameters**:
  - `contract_id` (string, required): Contrato en estado `APPROVED` y modo `LOOP`.
  - `item_id` (string, optional): ID del criterio específico a ejecutar (o `auto` para el siguiente pendiente).
- **Returns**:
  - `item_status`: `"PASS"` | `"FAIL"` | `"BLOCKED"` | `"NOT_WRITABLE"`
  - `attempt_number`: Número de intento (1..3).
  - `audit_log_path`: Ruta del log de auditoría en `.sdd/runs/`.

### `sdd_log_ambiguity`
Registra un bloqueo formal en el `Ambiguity Log` y transiciona el contrato de regreso a `Status: DRAFT` / `Mode: GATE`.
- **Parameters**:
  - `contract_id` (string, required): ID del contrato.
  - `item_id` (string, required): Criterio que disparó el bloqueo.
  - `reason` (string, required): Motivo preciso de la ambigüedad o falta de diseño en la spec.

### `sdd_get_status`
Genera el tablero de estado consolidado de todos los contratos del repositorio.
- **Parameters**: Ninguno.
- **Returns**:
  - Resumen agrupado por `DRAFT (Gate)`, `APPROVED (Loop)` y `RESOLVED`.

---

## 3. Configuración en Clientes MCP

### Configuración en Claude Desktop / Claude Code (`claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "sdd-gl": {
      "command": "npx",
      "args": ["-y", "@sdd-gl/mcp-server"]
    }
  }
}
```

### Configuración en Cursor / Windsurf (`.cursor/mcp.json` o settings):
```json
{
  "mcpServers": {
    "sdd-gl": {
      "command": "node",
      "args": ["./node_modules/@sdd-gl/mcp-server/dist/index.js"]
    }
  }
}
```
