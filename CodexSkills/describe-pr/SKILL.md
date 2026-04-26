---
name: describe-pr
description: Generate a comprehensive pull request description using the repository template, verification evidence, and PR metadata, then save and sync the result.
---

# Describe PR

Use this skill when the repository has a PR description template and the user wants Codex to fill it out thoroughly.

## Workflow

1. Read `thoughts/shared/pr_description.md`.
   If it is missing, explain that the repository template must be created first.
2. Identify the target PR from the current branch or by listing open PRs.
3. Check whether `thoughts/shared/prs/{number}_description.md` already exists.
4. Gather:
   - Full PR diff
   - Commit history
   - Base branch
   - Core PR metadata
5. Analyze the changes carefully enough to explain:
   - The problem solved
   - User-facing impact
   - Internal implementation details
   - Any breaking or migration concerns
6. Run verification commands where possible.
7. Fill out every section of the template.
8. Save to `thoughts/shared/prs/{number}_description.md`.
9. Run `humanlayer thoughts sync`.
10. Update the PR body from the saved file.

## Guidance

- Be thorough but scannable.
- Focus on why as much as what.
- Clearly mark any verification that still requires human testing.

