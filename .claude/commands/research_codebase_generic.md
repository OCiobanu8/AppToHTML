---
description: Research codebase comprehensively using parallel sub-agents (generic, no thoughts system)
---

# Research Codebase (Generic)

You are tasked with conducting comprehensive research across the codebase to answer user questions by spawning parallel sub-agents and synthesizing their findings.

## Initial Setup

When this command is invoked, respond with:
```
I'm ready to research the codebase. Please provide your research question or area of interest.
```

## Steps

1. **Read any directly mentioned files first** — no limit/offset parameters
2. **Decompose the research question** into composable areas
3. **Spawn parallel sub-agent tasks** for comprehensive research
4. **Wait for all sub-agents to complete** and synthesize findings
5. **Present findings** to the user with specific file paths and line numbers

## Core Principle

**Document the codebase as it exists today.** Do not suggest improvements or propose enhancements unless explicitly requested.

## Important Notes
- Always use parallel agents for efficiency
- Include specific file paths and line numbers
- Do NOT use `humanlayer thoughts sync` or write to `thoughts/` directory
