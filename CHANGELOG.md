# Changelog

## [0.1.0] — 2025-07-01

### Initial release

- Gate protocol: refinamiento iterativo de spec con revisión humana obligatoria
- Loop protocol: ejecución autónoma con criterios inferidos del Contract
- Completion Map: persistencia de progreso por ítem en disco
- Crash recovery: reanudación desde el último punto guardado (⏳ al arrancar)
- Detección de contradicciones en Gate antes de permitir aprobación
- Ambiguity Log: escalado a Gate con descripción precisa del bloqueo
- Múltiples ciclos Gate/Loop por Contract con progreso acumulado
- Cuatro agentes bundled: requirements, coder, tester, reviewer
- Tres comandos: /sdd-feature, /sdd-fix, /sdd-status
- Stack-agnostic: stack.md opcional para convenciones de proyecto

## [0.2.0] — 2025-07-01

### Added — Multi-platform support

- Antigravity CLI support via `.agents/skills/` structure
- Antigravity IDE support (same skills directory)
- `AGENTS.md` — orchestrator for Antigravity (equivalent of CLAUDE.md)
- Nine Antigravity skills: sdd-gate, sdd-loop, sdd-feature, sdd-fix,
  sdd-status, sdd-requirements-agent, sdd-reviewer-agent, sdd-coder-agent,
  sdd-tester-agent
- Intent-matching triggers in all skill frontmatter (Antigravity auto-activates
  skills when user intent matches description/triggers)
- Updated plugin.json with multi-platform `platforms` section
- README installation section for Antigravity CLI and IDE
- Platform comparison table (Claude Code vs Antigravity)

### Unchanged

- protocol/ — 100% portable across all platforms
- contracts/ — same format on all platforms
- Gate/Loop logic — identical regardless of platform
- Completion Map format — identical

## [0.2.1] — 2026-08-10

### Fixed — Architectural and Protocol Enhancements

- **Split Gate Checklist**: Differentiated validation criteria in Gate mode between Features (`FEAT`) and Bugfixes (`FIX`). Bugfixes now require only Reproduction Steps and one Acceptance Criterion (`AC-001`), preventing them from getting stuck in Gate.
- **Immediate Escalation on Blockers**: Updated Loop execution logic to intercept `BLOCKED` (from coder-agent) and `NOT_WRITABLE` (from tester-agent) states, immediately triggering a return to Gate mode via the Ambiguity Log to prevent useless retry cycles.
- **Reviewer Retry Limit**: Introduced a limit of 1 reviewer-guided retry cycle in Loop mode to prevent infinite feedback loops.
- **Aligned Entity Invariants**: Removed the entity test row from criteria inference tables to keep the framework lightweight and avoid logical discrepancies.
- **QA Validated**: Architectural fixes reviewed and validated by the `sdd-tester-agent`.

## [0.3.0] — 2026-08-25

### Added — Adaptive Governance, Glass Box Telemetry, Presets & MCP Standard

- **Adaptive Gate Governance (`GATE-EXPRESS` vs. `GATE-STRICT`)**: Eliminates spec review fatigue by enabling 1-step approvals for bugfixes and small tasks, while reserving rigorous section-by-section reviews for complex domain features.
- **Glass Box Loop (Audit & Telemetry)**: The Loop now generates an auditable execution trace in `.sdd/runs/[ID]-[timestamp].md` detailing context files inspected, diffs generated, test runner outputs, retry counts, and decision rationales.
- **Official Stack Presets**: Added zero-config presets in `presets/` for Java Spring Boot 3.x (Hexagonal/DDD), Python FastAPI (Pydantic v2/Async), and TypeScript Node.js/Bun.
- **Model Context Protocol (MCP) Server Specification**: Added `mcp/sdd-gl-mcp-spec.md` with standard JSON-RPC tool schemas (`sdd_create_contract`, `sdd_validate_gate`, `sdd_step_loop`, `sdd_log_ambiguity`, `sdd_get_status`), making SDD-GL 100% agnostic to any IDE or LLM runtime.

