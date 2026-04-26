# Generate PR Description (No Thoughts)

You are tasked with generating a comprehensive pull request description. This variant does not use the `humanlayer thoughts` system.

## PR Description Template

```md
## What problem(s) was I solving?

## What user-facing changes did I ship?

## How I implemented it

## How to verify it

### Manual Testing

## Description for the changelog
```

## Steps to follow:

1. **Identify the PR to describe:**
   - Check if the current branch has an associated PR: `gh pr view --json url,number,title,state 2>/dev/null`
   - If no PR exists, list open PRs: `gh pr list --limit 10 --json number,title,headRefName,author`

2. **Check for existing description:**
   - Check if `/tmp/{repo_name}/prs/{number}_description.md` already exists

3. **Gather comprehensive PR information:**
   - Get the full PR diff: `gh pr diff {number}`
   - Get commit history: `gh pr view {number} --json commits`
   - Get PR metadata: `gh pr view {number} --json url,title,number,state`

4. **Analyze the changes thoroughly** — read the entire diff carefully.

5. **Handle verification requirements** — run commands where possible, mark checked/unchecked.

6. **Generate the description** filling out each template section.

7. **Save:** Write to `/tmp/{repo_name}/prs/{number}_description.md`

8. **Update the PR:** `gh pr edit {number} --body-file /tmp/{repo_name}/prs/{number}_description.md`

## Important notes:
- Be thorough but concise
- Focus on the "why" as much as the "what"
- Include breaking changes prominently
