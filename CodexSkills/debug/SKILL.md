---
name: debug
description: Investigate a problem by examining logs, state, recent changes, and relevant context without editing files, then produce a concise evidence-based debug report.
---

# Debug

Use this skill when the user wants help diagnosing a problem rather than changing code immediately.

## Start

If the issue is not yet described, ask for:

- What they were doing
- What went wrong
- When it last worked
- Any relevant error messages

If a plan or ticket file was provided, read it fully first.

## Investigation Workflow

1. Check current git branch and recent commits.
2. Check for uncommitted changes.
3. Inspect the most relevant logs.
4. Inspect database or persisted state if the issue depends on it.
5. Identify what changed recently that could explain the problem.

## Output

Present findings as:

- What is wrong
- Evidence found
- Most likely root cause
- Concrete next steps

## Guardrails

- Investigate before proposing fixes.
- Read context files completely.
- Keep this workflow read-only unless the user explicitly pivots to implementation.

