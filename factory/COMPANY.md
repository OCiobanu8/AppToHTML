# COMPANY — Foundation tier

## Identity

AppToHTML is a tool that captures a target Android app's live UI — via the platform
AccessibilityService — into faithful, structured, machine-readable representations (merged HTML
and XML). It exists to turn opaque, on-screen app state into durable, inspectable artifacts.

## Mission

Make any app's UI capturable, mergeable, and understandable as structured data — accurately and
safely — without instrumenting or modifying the target app.

## Vision

A reliable capture substrate that AI agents and humans can build on: crawl an app, get a
complete, deduplicated, navigable model of its screens and reachable states.

## Values (the tie-breakers when goals collide)

- **Correctness over speed.** A capture that is fast but wrong is worse than useless — it
  silently corrupts everything downstream. When the two collide, we choose the accurate result
  and pay the time.
- **Safety over coverage.** Crawling a live app must never trigger destructive or irreversible
  actions. We would rather miss a screen than tap something dangerous. Guardrails against system
  UI, back-outs, and cross-app boundaries are non-negotiable.
- **Reproducibility over convenience.** A result that only exists on one machine did not happen.
  Vendored tools, deterministic merges, and evidence we can regenerate beat one-off local magic.
- **Evidence over assertion.** "It works" is a claim; a passing check re-run at the final state
  is proof. We converge on evidence, not on optimism.
- **Durable specs over duplicated detail.** One fact, one home. Duplication means drift.
