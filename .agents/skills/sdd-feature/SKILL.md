---
name: sdd-feature
description: >
  Use this skill to start a new feature work item in SDD-GL. Creates a Contract file in
  contracts/ with Status: DRAFT and Mode: GATE, populates Intent and a basic Use Case from
  the description, and leaves BR, AC, and AF sections empty with guidance comments.
  Use when the user says "new feature", "start feature", "create feature", or provides a
  feature description to track. Do NOT use for bug fixes — use sdd-fix instead.
triggers:
  - "new feature"
  - "start feature"
  - "create feature"
  - "add feature"
  - "implement feature"
---

# SDD-GL — /sdd-feature (Antigravity)

## Purpose
Start a new FEAT work item. Create the Contract in DRAFT and enter Gate mode.

## Steps

1. Count existing `FEAT-*.md` files in `contracts/` → next ID is FEAT-XXXX (zero-padded, 4 digits)
2. Create `contracts/FEAT-XXXX.md` using the template below
3. Populate `Intent` from the user's description
4. Generate a basic 3-step `Main Flow` inferred from the description
5. Leave BR, AC, AF empty with guidance comments
6. Report to user with checklist of what to complete

## Contract template

```markdown
# CONTRACT: [description]
# ID: FEAT-XXXX
# Status: DRAFT
# Mode: GATE
# Gate-Mode: STRICT

## Intent
[Inferred from user description]

## Use Case
**Actor:**
**Goal:**

### Main Flow
1. [inferred step 1]
2. [inferred step 2]
3. [inferred step 3]

### Alternative Flows
<!-- AF-01: [condition] → [result] -->

## Business Rules
<!-- BR-001: [verifiable invariant — not a description of behavior] -->

## Acceptance Criteria
<!-- AC-001: GIVEN [context] WHEN [action] THEN [expected result] -->

## Entities Affected
<!-- [Entity]: [relevant attributes or invariants] -->

## Ambiguity Log
- [ ]

## Completion Map
(built by Loop when Mode changes to LOOP)
```

## Output to user

```
✅ Contract created: contracts/FEAT-XXXX.md

📋 Complete these sections before approving:
   - [ ] Alternative Flows (if applicable)
   - [ ] Business Rules (BR-XXX as verifiable invariants)
   - [ ] Acceptance Criteria (AC-XXX in GIVEN/WHEN/THEN format)

⏸️  When ready: change Status: APPROVED and Mode: LOOP
```

## What this skill does NOT do
- Generate implementation code
- Complete BR, AC, or AF sections automatically
- Change Status or Mode
