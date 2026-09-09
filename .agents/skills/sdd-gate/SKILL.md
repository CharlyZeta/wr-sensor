---
name: sdd-gate
description: >
  Use this skill when a SDD-GL Contract exists in contracts/ with Mode: GATE or Status: DRAFT.
  Activates the Gate protocol: refines the spec section by section, validates internal consistency,
  detects contradictions between BR-XXX and AC-XXX, and prepares the Contract for human approval.
  Do NOT use when Mode: LOOP or Status: APPROVED — use sdd-loop instead.
triggers:
  - "contract in DRAFT"
  - "gate mode"
  - "refine spec"
  - "complete contract"
  - "review contract before approving"
---

# SDD-GL Gate Protocol (Antigravity)

You are operating the Gate mode of SDD-GL.
Load and follow `protocol/gate.md` in full before taking any action.

## Entry condition
Only activate when the Contract header shows:
- `Status: DRAFT` AND `Mode: GATE`

If Status is APPROVED and Mode is LOOP → do not activate. Tell the user to use /sdd-loop.

## Execution steps

1. Read the target Contract from `contracts/[ID].md`
2. Determine the Gate mode:
   - Read `# Gate-Mode: EXPRESS | STRICT` (if omitted, default to `EXPRESS` for `FIX-XXXX` and `STRICT` for `FEAT-XXXX`).
3. Execute according to Gate mode:
   - **If `GATE-EXPRESS`**: Complete all missing sections of the checklist in a single pass, run the consistency check, and present the pre-approval summary immediately (zero spec fatigue).
   - **If `GATE-STRICT`**: Run the checklist one section at a time. If a section is incomplete → complete it and STOP.
4. Run consistency check (BR vs AC contradictions, unverifiable criteria, unresolved Ambiguity Log items)
5. If contradiction found → write to Ambiguity Log and STOP. Present options to the human.
6. If Contract is complete and consistent → present summary and prompt human to approve

## What Gate never does
- Generate implementation code or tests
- Advance more than one section per iteration
- Change `Status` or `Mode` fields — only the human does this
- Assume an ambiguity is resolved

## Resources
- protocol/gate.md — full Gate protocol
- protocol/contract.md — Contract format and inference table
