---
name: create-plan
description: Create a detailed implementation plan through codebase research, clarification, and phased design, then save it under thoughts/shared/plans with explicit verification criteria.
---

# Create Plan

Use this skill when the user wants a technical implementation plan grounded in the codebase.

## Start

- If the user did not provide enough context, gather:
  - Task description
  - Relevant files
  - Constraints
- If a file path was provided, read the referenced files fully before asking follow-up questions.

## Research Workflow

1. Read every directly relevant file in full.
2. Investigate the codebase thoroughly.
3. Extract precise file references where they improve the plan.
4. Surface focused questions only when they materially affect the design.

## Plan Requirements

The final plan should include:

- Overview
- Current state
- Desired end state
- Phases with concrete file and behavior changes
- Success criteria split into automated verification and manual verification

## Guardrails

- Be skeptical of vague requirements.
- Resolve open decisions before finalizing the plan.
- Verify assumptions in code instead of guessing.
- Prefer runnable commands for automated verification.

## Output

Write the plan to:

`thoughts/shared/plans/YYYY-MM-DD-ENG-XXXX-description.md`

Include specific file paths and line references where useful.

