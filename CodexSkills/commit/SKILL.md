---
name: commit
description: Plan and create one or more focused git commits for the current session after showing the proposed grouping and messages to the user.
---

# Commit

Use this skill when the user asks Codex to commit work and should be shown the proposed commit plan first.

## Workflow

1. Review the conversation history to understand what changed.
2. Inspect repo state with `git status` and `git diff`.
3. Decide whether the work should be one commit or several logical commits.
4. Present the commit plan before executing:
   - Files in each commit
   - Commit message for each commit
   - A brief rationale for the grouping
5. After approval, stage files explicitly with `git add <path>`.
   Never use `git add .` or `git add -A`.
6. Create the commit or commits.
7. Show the resulting history with a short `git log --oneline` summary.

## Guardrails

- Do not add Claude or Codex attribution.
- Do not add co-author trailers unless the user explicitly asks.
- Write commit messages as if the user authored them.
- Keep commits atomic whenever practical.

