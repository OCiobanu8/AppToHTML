# Design Principles for SPEAR Systems

The protocol only works if the surrounding system is built for it. These five principles make SPEAR composable.

## 1. Every action equals an API call

That sounds restrictive, but it forces clarity. If an action can't be translated into a concrete call, it's still vague — and vagueness is where assistants start inventing progress.

In practice: prefer APIs over CLIs, structured tools over free-text prompts, idempotent operations over implicit state changes. If your assistant says "I'll handle that," ask: which API? Which call? With which parameters?

## 2. Structured I/O — JSON in, JSON out

Free text is the enemy of validation. Every action's input and output should be structured (JSON, Pydantic, TypeScript types — pick one). This:
- Reduces ambiguity at the boundary
- Makes Assess concrete (compare structured output to structured spec)
- Lets the system retry, batch, and roll back cleanly

When you're tempted to return a free-text "summary" — don't. Return a struct.

## 3. Cap blast radius

Hard limits — 4KB payload chunks, 10-item batches, max 5 retries. Small bounded actions are easy to validate, retry, roll back. Large unbounded actions fail in messy ways.

This is why SPEAR systems work better than "agentic AI" demos. Every step has a small blast radius. A failure costs you one chunk, not a database.

## 4. Make the plan visible

The user can see what the assistant intends to do BEFORE execution. The cost of catching a wrong assumption at Plan time is seconds. The cost of catching it after Execute is minutes (cleanup) to days (data corruption).

In practice: write `PLAN.md`. Show it. Let the user edit it. Only proceed when they confirm.

## 5. Cap iterations

`MAX_ROUNDS = 5` (or 10, or 20 — pick by artifact). Diminishing scope each round. The cap prevents endless loops AND forces the assistant to narrow the unresolved work each pass.

If the system can't converge in N rounds, that's a signal the scope was wrong, not that it needs more rounds.

---

## How the principles compose

Together these principles produce a specific shape:

- **Tools, not prompts.** The assistant calls APIs, not free-text agents.
- **Structs, not strings.** Inputs and outputs are validated.
- **Small steps, not big ones.** Every action's failure is recoverable.
- **Visible, not implicit.** The user sees the plan and the rubric.
- **Bounded, not infinite.** Convergence in N rounds or escalate.

That's the shape of a system that finishes.
