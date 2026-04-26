---
name: ci-describe-pr
description: Generate and publish a comprehensive pull request description using the repository template and available verification evidence, without pausing for extra confirmation.
---

# CI Describe PR

Use this skill when the user wants Codex to prepare a PR description end to end.

## Workflow

1. Read `thoughts/shared/pr_description.md`.
   If it does not exist, explain that the repository template is missing and stop.
2. Identify the target PR.
   Check the current branch PR first; if none exists, list recent open PRs and select the appropriate one.
3. Check for an existing draft at `thoughts/shared/prs/{number}_description.md`.
4. Gather context:
   - Full PR diff
   - Commit history
   - Base branch
   - PR metadata
5. Analyze the change carefully.
   Read related files when the diff alone is not enough to explain behavior or impact.
6. Run verification steps from the template when possible.
   Mark successful checks, leave manual or failing items clearly explained.
7. Write the completed description to `thoughts/shared/prs/{number}_description.md`.
8. Run `humanlayer thoughts sync`.
9. Update the PR body from the generated file.

## Guidance

- Keep the writeup scannable, but specific.
- Explain both the problem solved and the implementation choices.
- Call out breaking changes, migrations, and manual verification needs clearly.
- Do not leave template sections half-filled.

