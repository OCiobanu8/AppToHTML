---
description: Research codebase comprehensively using parallel sub-agents (no thoughts system)
---

# Research Codebase (No Thoughts)

You are tasked with conducting comprehensive research across the codebase to answer user questions by spawning parallel sub-agents and synthesizing their findings. This variant does not use the `humanlayer thoughts` system.

## Initial Setup

When this command is invoked, respond with:
```
I'm ready to research the codebase. Please provide your research question or area of interest.
```

## Steps

1. **Read any directly mentioned files first** — no limit/offset parameters
2. **Decompose the research question** into composable research areas
3. **Spawn parallel sub-agent tasks** for comprehensive research
4. **Wait for all sub-agents to complete** and synthesize findings
5. **Present concise summary** with key file references for developer navigation

## Core Principle

**Document the codebase as it exists today.** Do not suggest improvements unless explicitly requested.

## Important Notes
- Always use parallel agents for efficiency
- Include specific file paths and line numbers
- Do NOT use `humanlayer thoughts sync` or write to `thoughts/` directory
- Research is read-only — no file editing
