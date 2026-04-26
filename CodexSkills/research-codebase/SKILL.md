---
name: research-codebase
description: Conduct comprehensive codebase research by decomposing the question, gathering evidence across relevant components, and synthesizing a current-state document under thoughts/shared/research.
---

# Research Codebase

Use this skill when the user wants a careful explanation of how the codebase works today.

## Start

If the research question is not yet provided, ask for it first.

## Workflow

1. Read any directly mentioned files in full.
2. Break the research question into composable areas.
3. Investigate each area thoroughly.
4. Synthesize the findings into a coherent answer.
5. Gather metadata such as branch, commit, and repository name.
6. Write a research document to:
   `thoughts/shared/research/YYYY-MM-DD-[ENG-XXXX-]description.md`
7. Add stable repository links when possible.
8. Run `humanlayer thoughts sync`.
9. Present a concise summary to the user.

## Core Principle

Document the codebase as it exists today.

Do not propose fixes, redesigns, or root-cause theories unless the user explicitly asks for them.

## Suggested Structure

- Research question
- Summary
- Detailed findings
- Code references
- Architecture insights
- Historical context
- Related research
- Open questions

