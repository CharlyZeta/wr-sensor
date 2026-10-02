---
name: sdd-audit
description: >
  Audits traceability between specification contracts and project codebase.
  Detects orphan specs (BR/AC without matching tests) and code without spec (domain logic without contracts).
triggers:
  - "audit traceability"
  - "sdd audit"
  - "find orphan specs"
  - "spec coverage audit"
---

# SDD-GL Audit Command

This skill executes a non-blocking traceability audit comparing all contracts in `contracts/` with the active codebase.

## Audit Checks
1. **Orphan Specs (Specs without Tests)**:
   - Identifies any `BR-XXX` or `AC-XXX` defined in active or resolved contracts that lacks an automated test referencing its identifier.
2. **Untracked Domain Code (Code without Spec)**:
   - Identifies domain service files, entities, or core modules that are not referenced in the `Entities Affected` section of any contract.
3. **Specification Coverage Score**:
   - Calculates the overall percentage of business rules and acceptance criteria with verified assertions in the test suite.

## Output Format
```
🔍 SDD-GL Traceability Audit Report
=============================================
Total Contracts Inspected: [N]
Specification Coverage Score: [XX.X]%

📋 Orphan Specifications (No Associated Tests):
  - [ ] FEAT-0001: BR-003 (Missing unit test)
  - [ ] FIX-0002: AC-001 (Missing assertion test)

📁 Untracked Domain Code (No Linked Contract):
  - src/domain/LegacyPaymentGateway.ts
  - src/domain/TaxCalculator.ts

Status: ⚠️ Non-blocking findings reported.
=============================================
```
