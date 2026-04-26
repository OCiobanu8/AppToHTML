---
name: local-review
description: Set up a local review worktree for another developer's branch, including remote configuration, environment setup, and session launch preparation.
---

# Local Review

Use this skill when the user wants a clean local environment for reviewing a colleague's branch.

## Input

Expect a parameter in the form:

`github_username:branch-name`

If it is missing, ask for that exact format.

## Workflow

1. Parse the username and branch name.
2. Extract a ticket identifier from the branch name when possible to form a short worktree directory.
3. Check whether the remote already exists.
   If not, add it.
4. Fetch the remote branch.
5. Create a worktree for the colleague branch.
6. Copy any needed local settings such as `.claude/settings.local.json` when that is part of the repo workflow.
7. Run project setup in the worktree.
8. Initialize any repo-specific context such as `humanlayer thoughts` if required.

## Error Handling

- If the worktree path already exists, stop and explain.
- If fetching fails, verify the remote and branch names.
- If setup fails, surface the error clearly and preserve the created worktree when possible.

