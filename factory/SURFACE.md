# SURFACE — Interface tier

> **Durability:** changes with the surface. One section per interface — its role, its stack
> (durable: platform + framework, not build versions), and the constraints specific to it.

## Android capture app

- **Role:** the product itself — selects a target app, drives capture and deep crawl, and exports
  the artifacts.
- **Stack:** native Android (Kotlin); Compose for the operator UI; the platform
  AccessibilityService as the capture mechanism; local persisted preferences for the selected
  target.
- **Constraints:**
  - Runs inside the accessibility sandbox — it sees a serialized snapshot of the tree, not the
    target's internals, and cannot instrument the target.
  - Must be non-destructive and safety-gated: never actuate dangerous system or cross-app
    controls while crawling.
  - Time- and settle-bounded: capture and navigation operate under explicit timeouts and
    debounce/settle windows so a stuck target cannot hang a run.

## Developer tooling surface

- **Role:** the workflow tools a contributor or agent uses to plan, track, and converge work on
  this repo — not shipped to end users.
- **Stack:** the SPEAR phase-gate CLI (vendored under `tools/spear-cli/`, built locally) for the
  Scope→Plan→Execute→Assess→Resolve loop, and the Beads issue tracker (`bd`) for durable task
  state. A Node ≥ 20 runtime backs the CLI; Git carries both.
- **Constraints:**
  - Vendored tooling is read-only third-party source, built locally; build outputs stay out of
    git.
  - The runner skill owns the human gates; the CLI only enforces phase order.
  - Task state lives in Beads, not in ad-hoc markdown TODO lists.
