---
name: ci-commit
description: Create one or more focused git commits for the current session without asking for confirmation. Use when the user has already decided the changes should be committed immediately.
---

# CI Commit

Use this skill when the user wants Codex to create commits directly from the current session state.

## Workflow

1. Review the conversation history to understand what changed and why.
2. Inspect the repo state with `git status` and `git diff`.
3. Decide whether the changes belong in one commit or multiple logical commits.
4. Group files into atomic commits and draft imperative commit messages that explain the reason for the change.
5. Stage files explicitly with path-specific `git add`. Never use `git add .` or `git add -A`.
6. Create the commit or commits with `git commit -m`.

## Guardrails

- Never commit `thoughts/` content.
- Never commit scratch files, dummy files, or generated artifacts that are unrelated to the finished work.
- Prefer small, coherent commits over one mixed commit.
- Do not stop to ask for approval once this skill is being executed.

