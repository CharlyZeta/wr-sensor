---
name: sdd-status
description: >
  Use this skill to show the current status of all SDD-GL Contracts in the project.
  Reads all .md files in contracts/, extracts Status, Mode, and Completion Map progress,
  and displays a grouped dashboard. Use when the user asks about project status, work in
  progress, what's done, or wants an overview of active Contracts.
triggers:
  - "sdd status"
  - "show contracts"
  - "what's in progress"
  - "project status"
  - "what's done"
  - "active work items"
---

# SDD-GL — /sdd-status (Antigravity)

## Purpose
Show the current status of all SDD-GL Contracts grouped by Status.

## Steps

1. Read all `.md` files in `contracts/`
2. For each file, extract:
   - `ID` from `# ID:` line
   - `Title` from `# CONTRACT:` line
   - `Status` from `# Status:` line
   - `Mode` from `# Mode:` line
   - Completion Map progress: count lines matching `# ...|...|...|` and tally ✅ vs total
3. Group by Status: DRAFT → APPROVED → RESOLVED
4. Display dashboard

## Output format

```
📊 SDD-GL Status

🔴 DRAFT (Gate — awaiting review)
   └── FEAT-0003: [title]

🟡 APPROVED (Loop in progress)
   └── FEAT-0002: [title] [8/11 ✅]

🟢 RESOLVED
   └── FEAT-0001: [title] [12/12 ✅]
   └── FIX-0001: [title] [4/4 ✅]

Total: N contracts | N in Gate | N in Loop | N resolved
```

## What this skill does NOT do
- Modify any Contract
- Start or resume any process
- Change Status or Mode
