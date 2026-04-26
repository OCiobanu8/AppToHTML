---
description: Generate comprehensive PR descriptions following repository templates
---

# Generate PR Description

You are tasked with generating a comprehensive pull request description following the repository's standard template.

## Steps to follow:

1. **Read the PR description template:**
   - First, check if `thoughts/shared/pr_description.md` exists
   - If it doesn't exist, inform the user that their `humanlayer thoughts` setup is incomplete and they need to create a PR description template at `thoughts/shared/pr_description.md`
   - Read the template carefully to understand all sections and requirements

2. **Identify the PR to describe:**
   - Check if the current branch has an associated PR: `gh pr view --json url,number,title,state 2>/dev/null`
   - If no PR exists for the current branch, or if on main/master, list open PRs: `gh pr list --limit 10 --json number,title,headRefName,author`
   - Ask the user which PR they want to describe

3. **Check for existing description:**
   - Check if `thoughts/shared/prs/{number}_description.md` already exists
   - If it exists, read it and inform the user you'll be updating it

4. **Gather comprehensive PR information:**
   - Get the full PR diff: `gh pr diff {number}`
   - Get commit history: `gh pr view {number} --json commits`
   - Review the base branch: `gh pr view {number} --json baseRefName`
   - Get PR metadata: `gh pr view {number} --json url,title,number,state`

5. **Analyze the changes thoroughly:**
   - Read through the entire diff carefully
   - Understand the purpose and impact of each change
   - Identify user-facing changes vs internal implementation details
   - Look for breaking changes or migration requirements

6. **Handle verification requirements:**
   - For each verification step: run it if possible, mark checked/unchecked accordingly
   - Document any verification steps you couldn't complete

7. **Generate the description** filling out each template section thoroughly.

8. **Save and sync:**
   - Write to `thoughts/shared/prs/{number}_description.md`
   - Run `humanlayer thoughts sync`

9. **Update the PR:**
   - `gh pr edit {number} --body-file thoughts/shared/prs/{number}_description.md`

## Important notes:
- Be thorough but concise - descriptions should be scannable
- Focus on the "why" as much as the "what"
- Include breaking changes prominently
- Always attempt to run verification commands when possible
