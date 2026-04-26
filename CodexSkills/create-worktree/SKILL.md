---
name: create-worktree
description: Create a worktree for implementing an approved plan, confirm the launch details with the user, and prepare the command that starts the implementation session.
---

# Create Worktree

Use this skill when an approved plan should move into a dedicated implementation worktree.

## Workflow

1. Read the worktree helper script, such as `hack/create_worktree.sh`, before using it.
2. Determine:
   - Branch name
   - Relative plan path
   - Launch prompt
   - Launch command
3. Keep `thoughts/` references relative to the repo, for example:
   `thoughts/shared/plans/example-plan.md`
4. Present the full proposed setup to the user before launching:
   - Worktree path
   - Branch name
   - Plan path
   - Launch prompt
   - Exact command to run
5. Incorporate feedback, then create the worktree and launch the implementation session.

## Guidance

- Use only relative `thoughts/shared/...` paths in prompts passed to the worktree.
- Keep the launch prompt explicit about implementation, verification, commit, PR description, and ticket updates when those are part of the workflow.

