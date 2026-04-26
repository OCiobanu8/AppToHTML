---
name: create-handoff
description: Create a concise but thorough handoff document for another Codex session, including current status, key references, learnings, and next steps.
---

# Create Handoff

Use this skill when work should be resumable by another agent or a future session.

## File Location

Write the handoff under:

`thoughts/shared/handoffs/ENG-XXXX/YYYY-MM-DD_HH-MM-SS_ENG-ZZZZ_description.md`

Use `general` when there is no ticket. Keep the description short and kebab-case.

## Preparation

1. Run the repository metadata helper such as `scripts/spec_metadata.sh` if available.
2. Collect:
   - Current date and time
   - Researcher or operator name
   - Current commit hash
   - Branch name
   - Repository name
   - Task or topic name

## Required Structure

Use YAML frontmatter followed by these sections:

- `Task(s)`
- `Critical References`
- `Recent changes`
- `Learnings`
- `Artifacts`
- `Action Items & Next Steps`
- `Other Notes`

## Writing Guidance

- Be concise, but preserve the context someone needs to continue effectively.
- Prefer file paths and tight file references over long code snippets.
- Mention both what is done and what is still pending.
- List every important artifact created or updated in the session.

## Finish

1. Save the document.
2. Run `humanlayer thoughts sync`.
3. Reply with a short resume instruction that points at the handoff path.

