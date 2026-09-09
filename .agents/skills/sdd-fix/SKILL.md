---
name: sdd-fix
description: >
  Use this skill to start a bugfix work item in SDD-GL. Creates a minimal Contract in contracts/
  with Status: DRAFT and Mode: GATE, structured around Reproduction Steps, Current Behavior,
  Expected Behavior, and a single AC-001 that fails today and passes after the fix.
  Use when the user mentions a bug, regression, error, or unexpected behavior to fix.
  Do NOT use for new features — use sdd-feature instead.
triggers:
  - "fix bug"
  - "bug fix"
  - "regression"
  - "something broke"
  - "unexpected behavior"
  - "error in"
  - "broken"
---

# SDD-GL — /sdd-fix (Antigravity)

## Purpose
Start a FIX work item. Minimal Gate — only requires Reproduction Steps and AC-001.

## Steps

1. Count existing `FIX-*.md` files in `contracts/` → next ID is FIX-XXXX
2. Create `contracts/FIX-XXXX.md` using the template below
3. Populate `Intent` with the bug description
4. Leave Reproduction Steps, Current Behavior, Expected Behavior, and AC-001 for the human
5. Report to user with minimal checklist

## Contract template (FIX)

```markdown
# CONTRACT: [bug description]
# ID: FIX-XXXX
# Status: DRAFT
# Mode: GATE
# Gate-Mode: EXPRESS

## Intent
[Bug description and expected behavior]

## Reproduction Steps
1.
2.
3.

## Current Behavior
[What happens today]

## Expected Behavior
[What should happen]

## Business Rules
<!-- Only if the bug involves a business rule violation -->

## Acceptance Criteria
- AC-001: GIVEN [state that triggers the bug] WHEN [action] THEN [correct result]
<!-- This test must FAIL today and PASS after the fix -->

## Ambiguity Log
- [ ]

## Completion Map
(built by Loop when Mode changes to LOOP)
```

## Key differences from FEAT

| Element            | FEAT              | FIX                          |
|--------------------|-------------------|------------------------------|
| Use Case           | Required          | Not required                 |
| Alternative Flows  | Recommended       | Omit unless relevant         |
| Business Rules     | Required          | Only if rule-related bug     |
| Min. AC            | N criteria        | 1 AC-001 mandatory           |
| Gate steps         | Multiple          | One (AC-001 approval)        |

## Output to user

```
✅ Contract created: contracts/FIX-XXXX.md

📋 Quick fix — complete only:
   - [ ] Reproduction Steps (3 steps minimum)
   - [ ] AC-001: GIVEN [buggy state] WHEN [action] THEN [correct result]

⏸️  When ready: change Status: APPROVED and Mode: LOOP
```

## What this skill does NOT do
- Reproduce the bug or search for its root cause
- Skip Gate even if the fix seems obvious
- Change Status or Mode
