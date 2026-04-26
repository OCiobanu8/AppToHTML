---
name: research-codebase-nt
description: Conduct comprehensive current-state codebase research without using the thoughts system, keeping the work read-only and reference-heavy.
---

# Research Codebase No Thoughts

Use this skill when the user wants read-only research without any `thoughts/` artifacts.

## Workflow

1. Ask for the research question if it is missing.
2. Read any directly mentioned files fully.
3. Break the question into clear research areas.
4. Investigate the relevant code.
5. Present a concise synthesis with strong file references.

## Guardrails

- Research is read-only.
- Do not call `humanlayer thoughts sync`.
- Do not write to `thoughts/`.
- Describe the current implementation rather than proposing changes unless asked.

