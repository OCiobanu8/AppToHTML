---
name: resume-handoff
description: Resume work from a handoff document by validating the referenced state, summarizing the current situation, and turning the handoff into an actionable task list.
---

# Resume Handoff

Use this skill when the user wants Codex to continue from a previous handoff document.

## Entry Modes

If a handoff path was provided:

1. Read the handoff completely.
2. Read any linked plan or research documents directly.
3. Validate the referenced state before taking action.

If a ticket number was provided:

1. Run `humanlayer thoughts sync`.
2. Look in `thoughts/shared/handoffs/ENG-XXXX/`.
3. If there are multiple handoffs, choose the most recent timestamped file.
4. Read the handoff and linked documents fully.

If nothing was provided:

Ask the user for either a handoff path or ticket number.

## Workflow

1. Extract tasks, changes, learnings, artifacts, and next steps from the handoff.
2. Verify that referenced files and changes still exist.
3. Present a summary of:
   - Original tasks
   - Validated learnings
   - Current status of recent changes
   - Recommended next actions
4. Convert the next steps into a task list.
5. Proceed only after aligning with the user on the next action.

## Guardrails

- Never assume the handoff still matches the current repo state.
- Pay special attention to the learnings section.

