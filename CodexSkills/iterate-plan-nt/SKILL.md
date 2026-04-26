---
name: iterate-plan-nt
description: Update an existing implementation plan based on user feedback without using the thoughts system, preserving structure and verification criteria.
---

# Iterate Plan No Thoughts

Use this skill when a local plan needs targeted updates but `thoughts/` should not be used.

## Workflow

1. Read the complete plan file.
2. Clarify missing feedback only if needed.
3. Research technical changes when necessary.
4. Confirm your understanding before editing.
5. Apply surgical updates.
6. Preserve:
   - Existing structure where possible
   - Accurate file references
   - Automated vs manual verification separation
7. Present a concise summary of changes made.

## Guardrails

- Do not call `humanlayer thoughts sync`.
- Be precise rather than rewriting broadly.
- Verify feasibility before changing the plan.

