---
name: research-codebase-generic
description: Conduct comprehensive current-state codebase research and present findings with file references, without writing to the thoughts system.
---

# Research Codebase Generic

Use this skill when the user wants research output directly in the conversation and no `thoughts/` artifact is needed.

## Workflow

1. Ask for the research question if it is missing.
2. Read any directly mentioned files in full.
3. Break the question into manageable research areas.
4. Investigate the relevant code paths thoroughly.
5. Synthesize the findings into a concise but specific answer with file references.

## Guardrails

- Focus on how the codebase works today.
- Do not write to `thoughts/`.
- Do not suggest improvements unless the user explicitly asks.

