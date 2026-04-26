---
name: validate-plan
description: Validate implementation against an approved plan by checking completed phases, running verification, and documenting deviations, risks, and remaining manual testing.
---

# Validate Plan

Use this skill when the user wants to know whether implementation matches the intended plan.

## Workflow

1. Locate and read the implementation plan completely.
2. Inspect recent implementation evidence:
   - Relevant commits
   - Diffs
   - Test or build results
3. Determine what the plan expected to change.
4. Compare that against what was actually implemented.
5. For each phase:
   - Check stated completion
   - Run automated verification where possible
   - Separate manual verification that still needs a human
   - Consider edge cases and regressions

## Output

Produce a validation report that covers:

- Implementation status by phase
- Automated verification results
- Where the code matches the plan
- Deviations from the plan
- Potential issues or regressions
- Remaining manual testing
- Recommended next steps

## Validation Checklist

- All completed phases are actually implemented
- Automated checks pass or failures are explained
- Code follows existing patterns
- No obvious regressions were introduced
- Error handling remains sound

