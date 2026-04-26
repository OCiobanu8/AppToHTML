---
name: describe-pr-nt
description: Generate a comprehensive pull request description and save it locally without using the thoughts system or repository PR template files.
---

# Describe PR No Thoughts

Use this skill when the repo does not use `thoughts/` but still needs a structured PR description.

## Default Template

Use these sections unless the repository requires something else:

- What problem(s) was I solving?
- What user-facing changes did I ship?
- How I implemented it
- How to verify it
- Manual testing
- Description for the changelog

## Workflow

1. Identify the target PR from the current branch or open PR list.
2. Check whether `/tmp/{repo_name}/prs/{number}_description.md` already exists.
3. Gather the PR diff, commits, and metadata.
4. Analyze the change carefully.
5. Run verification commands where possible and distinguish manual verification from automated verification.
6. Write the final description.
7. Save it under `/tmp/{repo_name}/prs/{number}_description.md`.
8. Update the PR body using that file.

## Guardrails

- Be concise but complete.
- Call out breaking changes clearly.
- Do not use `thoughts/` paths or `humanlayer thoughts sync`.

