---
description: Research codebase comprehensively using parallel sub-agents
model: opus
---

# Research Codebase

You are tasked with conducting comprehensive research across the codebase to answer user questions by spawning parallel sub-agents and synthesizing their findings.

## Initial Setup

When this command is invoked, respond with:
```
I'm ready to research the codebase. Please provide your research question or area of interest, and I'll analyze it thoroughly by exploring relevant components and connections.
```

Then wait for the user's research query.

## Steps to follow after receiving the research query:

1. **Read any directly mentioned files first** — use Read tool WITHOUT limit/offset parameters
2. **Analyze and decompose the research question** into composable research areas
3. **Spawn parallel sub-agent tasks** for comprehensive research
4. **Wait for all sub-agents to complete** and synthesize findings
5. **Gather metadata** (git commit, branch, repository info)
6. **Generate research document** with YAML frontmatter at `thoughts/shared/research/YYYY-MM-DD-[ENG-XXXX-]description.md`
7. **Add GitHub permalinks** if on main branch or pushed commits
8. **Sync and present findings**: Run `humanlayer thoughts sync`, present a concise summary
9. **Handle follow-up questions** by appending to the same research document

## Core Principle

**YOUR ONLY JOB IS TO DOCUMENT AND EXPLAIN THE CODEBASE AS IT EXISTS TODAY.** Do not suggest improvements, perform root cause analysis, or propose enhancements unless explicitly requested.

## Document Structure

```markdown
---
date: [ISO timestamp]
researcher: [name]
git_commit: [hash]
branch: [branch name]
repository: [repo name]
topic: "[Research Topic]"
tags: [research, relevant-component-names]
status: complete
last_updated: [YYYY-MM-DD]
last_updated_by: [name]
---

# Research: [Topic]

## Research Question
## Summary
## Detailed Findings
## Code References
## Architecture Insights
## Historical Context (from thoughts/)
## Related Research
## Open Questions
```

## Important Notes
- Always run fresh codebase research — never rely solely on existing documents
- Keep the main agent focused on synthesis, not deep file reading
- Include specific file paths and line numbers for developer reference
- Never write research documents with placeholder values
