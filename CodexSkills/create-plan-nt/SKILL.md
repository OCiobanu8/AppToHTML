---
name: create-plan-nt
description: Create a detailed implementation plan through codebase research and clarification without using the thoughts directory or thoughts sync workflow.
---

# Create Plan No Thoughts

Use this skill when the repository should not write to `thoughts/`.

## Workflow

1. Gather missing context: task, files, constraints.
2. Read all mentioned files fully.
3. Research the current implementation thoroughly.
4. Clarify any important ambiguity before finalizing.
5. Produce a complete plan with:
   - Overview
   - Current state
   - Desired end state
   - Phased implementation
   - Automated verification
   - Manual verification

## Guardrails

- Verify assumptions against code.
- Do not leave open questions in the finished plan.
- Include concrete file references where they add value.
- Do not call `humanlayer thoughts sync`.
- Do not write into `thoughts/`.

