---
name: implement-plan
description: Implement an approved technical plan from thoughts/shared/plans, track progress through phases, and verify each phase before moving on.
---

# Implement Plan

Use this skill when the user wants an approved plan turned into working code.

## Start

1. Read the plan completely.
2. Note any existing checked items and resume from the first unfinished part.
3. Read the original ticket and every file referenced by the plan.
4. Build a task list before editing.

## Execution Rules

- Follow the plan intent, but adapt to codebase reality when needed.
- Implement each phase fully before starting the next.
- Update plan checkboxes as work is completed.
- If the codebase has drifted from the plan in a meaningful way, stop and surface the mismatch clearly.

## Verification

After each phase:

1. Run the automated checks described by the plan.
2. Fix failures before continuing.
3. Update the completed checkboxes in the plan file.
4. Pause for manual verification when the phase is ready, unless the user explicitly asked for multiple phases in one pass.

## Guardrails

- Do not mark manual test items complete without user confirmation.
- Trust existing completed checkboxes unless something looks clearly wrong.

