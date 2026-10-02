# /sdd-metrics Command (Claude Code)

Compute and display quality and delivery metrics from `.sdd/metrics.json` and `.sdd/runs/`.

## Calculated Metrics
1. **First-Pass Quality Rate (FPQR)**: Percentage of contracts resolved in the Loop without requiring coder retries or reviewer intervention.
2. **Average Retries**: Mean number of coder/solve attempts per resolved contract.
3. **Ambiguity Escalation Rate**: Percentage of contracts that escalated from Loop back to Gate due to ambiguous specifications.
4. **Verification Layer Pass Rate**: Success percentage per layer (`functional`, `static`, `security`, `arch`).

## Output Format
```
📊 SDD-GL Quality Metrics Summary
=============================================
Total Contracts Evaluated: [N] (FEAT: [X], FIX: [Y])

• First-Pass Quality Rate (FEAT): [X]%
• First-Pass Quality Rate (FIX):  [Y]%
• Overall First-Pass Rate:        [Z]%

• Average Retries per Contract:   [N.N]
• Ambiguity Escalation Rate:      [P]%

Verification Layer Pass Rates (First Attempt):
  - 🧪 Functional Tests:   [A]%
  - 🔍 Static Analysis:    [B]%
  - 🛡️ Security Scans:     [C]%
  - 🏛️ Architecture Guard: [D]%
=============================================
```
