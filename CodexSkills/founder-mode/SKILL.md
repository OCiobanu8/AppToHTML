---
name: founder-mode
description: Backfill ticketing and PR workflow for an already-implemented experimental feature by creating the commit, Linear ticket, branch, PR, and description in sequence.
---

# Founder Mode

Use this skill when work was implemented before the usual ticket and PR workflow was set up.

## Workflow

1. Confirm there is a commit for the finished work.
   If not, run the `commit` skill first.
2. Review what was implemented and create a Linear ticket using the `linear` skill.
   The ticket should include:
   - `### Problem to solve`
   - `### Proposed solution`
3. Fetch the recommended branch name from the ticket workflow.
4. Switch back to `main`.
5. Create the new branch using the Linear branch name.
6. Cherry-pick the implementation commit onto that branch.
7. Push the branch.
8. Open a PR, for example with `gh pr create --fill`.
9. Run the `describe-pr` skill to finish the PR body.

## Guardrails

- Keep the backfilled ticket accurate to what actually shipped.
- Reconstruct the workflow cleanly rather than rewriting history destructively.

