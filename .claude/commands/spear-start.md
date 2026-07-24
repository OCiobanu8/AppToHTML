---
description: Run the SPEAR phase-gate pipeline (Scope→Plan→Execute→Assess→Resolve) with the three human gates enforced.
argument-hint: <kebab-slug> [short description of the unit of work]
---

# /spear-start — the gated SPEAR runner

You are driving the vendored `spear-cli` through one SPEAR cycle for the unit of work named by
the slug in `$ARGUMENTS`. **The CLI enforces phase order; YOU enforce the human gates.** The loop
will not stop for a human unless you make it. Never proceed on silence.

## 0. Invocation (inline, always `--name`)

Resolve the CLI from the *current* checkout every time — never store it in a shell variable
(it fails to word-split in some shells, exit 127):

```
node "$(git rev-parse --show-toplevel)/tools/spear-cli/dist/cli.js" <cmd> --name <slug>
```

- If `tools/spear-cli/dist/cli.js` is missing, run `bash scripts/setup-spear.sh` (or
  `scripts/setup-spear.ps1` on Windows) first — one-time build per checkout/worktree.
- **Pass `--name <slug>` on EVERY command.** Auto-resolve only works when exactly one slug exists
  under `.spear/`; more than one is normal, so an un-named command can hit the wrong project.
- **Slug ↔ Beads.** One slug = one unit of work = one bead. Use the bead id (or a short stable
  kebab derived from its title) as the slug, and claim the bead (`bd update <id> --claim`) when
  you start the cycle.

## 1. Scaffold

```
node "…/cli.js" init code <slug> --gated
```
Creates `.spear/<slug>/` with gated approvals required. Fill `SCOPE.md` before Gate 1.

## 2. ⛔ GATE 1 — Scope

1. Run `spear scope --name <slug>` (exit 0 = valid).
2. **Scope discipline** — enforce before presenting:
   - "Done means" must be **observable behavior**, not implementation facts.
   - Every criterion that *can* be an executable check (test/build/lint) **must** be one;
     inspection is a last resort.
   - Bug fixes require a **red→green** regression pin (a test that FAILS on pre-fix code, then
     passes) with both runs archived.
   - Set the round cap in `SCOPE.md` in the exact recognized form `` `MAX_ROUNDS = N` ``
     (backticked, `=` not `:`, on its own line). The CLI enforces it — do not hand-track rounds.
3. **Present the full `SCOPE.md` with a clickable link and STOP for an explicit human "yes."**
4. On approval only: `spear approve scope --name <slug>`.

## 3. ⛔ GATE 2 — Plan

1. Write `PLAN.md` + the feature-specific `ASSESS.md` additions; tick `[x] User confirmed`.
2. Run `spear plan --name <slug>` (exit 0 = valid; it FAILS while unapproved — that proves the
   gate is real).
3. **Present `PLAN.md` + the `ASSESS.md` additions with a clickable link and STOP for "yes."**
4. On approval only: `spear approve plan --name <slug>`.

## 4. Execute + Assess loop (one round per call)

Implement the plan steps, then iterate:

```
node "…/cli.js" loop --name <slug>
```
Exit codes drive the loop: `0` converged/stop honored · `1` execution failure · `2` defects open
(normal — apply fixes, re-loop) · `3` MAX_ROUNDS exhausted → **STOP, escalate to human.**

- **Independent, ratcheting grading — never self-graded.** The judgment half of Assess must be
  graded by someone other than the author, **stricter every round**. Use `--grader "<command>"`
  if a headless read-only assistant is available; otherwise spawn a **fresh adversarial subagent**
  each round, hand it `ASSESS.md` + artifact paths + the tool's grade contract, and have it
  gather its own fresh proof and attack a dimension the last round didn't.
  - If you wrap the grader in a script, **drain stdin first** (`cat > /dev/null`) or the CLI dies
    `EPIPE`. Give probe files a distinctive marker and check `git status` for remnants after the
    grader returns *or crashes*.
- **RED-first for behavior changes**, even when the fix extracts a new seam: temporarily populate
  the new seam with pre-fix behavior, run the pins (must FAIL), archive, then restore the fix and
  confirm GREEN.
- **Re-verify at the final state.** Green evidence is void the moment the artifact is edited
  again. Before declaring convergence, re-run the headline checks at the final artifact state —
  converging on a superseded version is falsified convergence.

## 5. ⛔ GATE 3 — Resolve (the output gate + flywheel)

1. After the loop converges, **present the finished work for explicit ACCEPT / REJECT before
   anything is committed or closed.** For user-visible behavior this means a **real human/device
   check**, recorded under `.spear/<slug>/evidence/`.
2. **Flywheel item (every cycle).** If the cycle exposed a *recurring or systemic* mistake (a
   human correction at a gate, the same defect count stuck ≥2 rounds, a grader defect with no
   matching rubric line, or a falsified-convergence re-entry), propose a **minimal, human-gated**
   improvement to the *right governing artifact* (the rubric, a `factory/` tier, `AGENTS.md`,
   this skill, or a spec), framed **root-cause → permanent guardrail**, ending with an explicit
   **"your call."** Never edit governance silently. If nothing systemic surfaced, say so
   explicitly — don't invent one.
3. On ACCEPT: `spear resolve --write PR.md --name <slug>`.
   - `resolve --write PR.md` writes to the **CWD (repo root)**, not the slug dir. Move it into
     `.spear/<slug>/` and hand-amend it feature-first; do **not** re-run `resolve --write` after
     amending (it clobbers your edits).

## 6. Close out — scoped commit only

- Commit **only the paths this cycle touched**, plus this cycle's `.spear/<slug>/` spec files
  (`SCOPE`/`PLAN`/`ASSESS`/`RESOLVE`/`PR`). **Never `git add -A`** — it sweeps in other cycles'
  and third-party changes. Serialize the commit across concurrent cycles.
- Then close the bead: `bd close <id>`.

## Recurring gotchas (do not relearn these mid-cycle)

- **`assess` rewrites `RESOLVE.md`**, clobbering any prior report block and completion marker.
  Order it: run `assess` first, then re-append your report + completion marker, then `loop` last
  to register the stop.
- **Re-entry is sticky.** After a falsified convergence, re-validating scope rewinds the phase
  machine but **approvals persist** — revoke them (`approve scope --revoke`,
  `approve plan --revoke`) so the gates re-arm, and neutralize the stale `<spear-complete/>` stop
  marker or the next `loop` honors it instantly.
- **Whole-repo adapter noise.** The `code` adapter scans the entire repo, surfacing pre-existing
  hits in `tools/spear-cli/**` and other in-flight work. Document those under an `ASSESS.md`
  "Known acceptable" section and register the stop with `--allow-fast-convergence` — never edit
  third-party source to force a zero count.
- **Worktrees:** run every path relative to the worktree root; prefer
  `git rev-parse --show-toplevel` over hard-coded paths, and never `cd` into a sibling checkout.

**Non-negotiables:** gates are unconditional and machine-checked; present a clickable link every
time; never approve on silence; scoped commits only; vendored code stays read-only.
