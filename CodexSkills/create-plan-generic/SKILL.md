---
name: create-plan-generic
description: Create a detailed implementation plan through codebase research and clarification, then save it to a local plans directory without relying on the thoughts system.
---

# Create Plan Generic

Use this skill when the user wants a plan but the repository does not use `thoughts/`.

## Workflow

1. Gather the task description, relevant files, and constraints if they were not provided.
2. Read all directly relevant files completely.
3. Research the codebase enough to explain current state and feasible change boundaries.
4. Clarify any decision that would materially alter the implementation.
5. Draft a complete plan with:
   - Overview
   - Current state
   - Desired end state
   - Phased implementation steps
   - Automated verification
   - Manual verification

## Guardrails

- Be skeptical and verify assumptions in code.
- Do not leave unresolved design questions in the final plan.
- Keep file references specific.

## Output

Write the plan to a local path such as:

`/tmp/{repo_name}/plans/YYYY-MM-DD-description.md`

