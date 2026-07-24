# Setting up SPEAR in a new repo — from scratch

A hand-to-an-agent recipe for standing up the **SPEAR** phase-gate pipeline in any code
repository. It is deliberately **repo-agnostic**: drop this file into a fresh repo, follow it
top-to-bottom, and you get a working, human-gated, evidence-backed iteration loop with no prior
context. Every step records the **lesson** behind it — the steps are ordered so that skipping
one re-introduces the failure it was added to fix.

SPEAR is **S**cope → **P**lan → **E**xecute → **A**ssess → **R**esolve: the five phases of
project management compressed into a deterministic loop an AI assistant runs between two human
approval gates. It is driven by the vendored **`spear-cli`** tool (Ryan Waliany, Apache-2.0)
plus one **runner skill** that owns the human gates. The tool enforces phase *order*; the skill
enforces human *judgment*. You build both here.

> **The thesis you are buying into** (see *Concepts* at the end): *"The strong-start-weak-finish
> problem in AI is a process problem, not a model problem."* The fix is to run all five phases
> every time, fast — not to skip phases for speed. Skipping a phase only defers its cost.

## What you will end up with

```
<your-repo>/
├── AGENTS.md                     # imports the factory/ tiers (context every agent inherits)
├── factory/                      # COMPANY / FUNCTION / PRODUCT / SURFACE  (Step 3)
├── tools/spear-cli/              # vendored tool, source-only (Step 1)
├── scripts/setup-spear.sh        # one-time local build (Step 2)
├── .claude/commands/spear-start.md   # the gated runner skill (Step 4)
├── .gitignore                    # split-tracking rules (Step 2)
└── .spear/<slug>/                # per-cycle SCOPE/PLAN/ASSESS/RESOLVE/PR + runtime (Step 5;
                                  #   one slug = one unit of work, many coexist)
```

---

## Step 1 — Vendor `spear-cli` (source-only)

Copy the `spear-cli` tool into `tools/spear-cli/` and commit the **source**, so a fresh clone
works on any device with no separate global install.

- **Obtain the tool first.** Get `spear-cli` from its upstream (Ryan Waliany's `spear-cli`
  project — clone the repo, or `npm pack`/`npm install @waliany/spear` and take the package
  source). Vendor from a specific known commit so the pin is reproducible.
- Vendor **source only**, excluding `.git/`, `node_modules/`, and `dist/`. Those build outputs
  are produced locally (Step 2) and gitignored.
- Write a `tools/spear-cli/VENDORED.md` recording: author (**Ryan Waliany**), upstream URL,
  package name, the exact **version** and **commit** you vendored (`<version>` / `<commit>`),
  and the license (**Apache-2.0**). Retain the upstream `LICENSE` verbatim — Apache-2.0 §4(b)
  is satisfied by keeping that notice. If you modify any file, mark it
  `[Local modification — <your-repo>]` per §4(b); if you vendor unmodified, say so explicitly.
- Treat `tools/spear-cli/**` as **read-only third-party code**. To update later, re-vendor the
  desired upstream commit, bump the version/commit in `VENDORED.md`, and rebuild.

> **Lesson — vendor, don't depend on a global install.** A tool that lives only in someone's
> `~/Projects` is not reproducible: a new clone or a teammate's machine has nothing. Vendoring
> source-only + building locally makes `/spear-start` work from a fresh checkout every time,
> while keeping bulky build artifacts out of git.

### You do NOT need to write an adapter (for a code repo)

`spear-cli` ships built-in **adapters** — one per project type — and a code repo uses the stock
**`code`** adapter as-is. An adapter is the deterministic, mechanical half of Assess: it runs
the build, checks for leftover debug, validates the spec files, counts what needs counting.
Because the `code` adapter already exists upstream, **a code repo writes no adapter at all**.

> Contrast: a **non-code** pipeline (e.g. a docs/wiki knowledge base) has *no* stock adapter, so
> that repo must author its own (`src/adapters/<name>.ts`, registered in `adapters/index.ts`)
> and rebuild the tool. If you are setting up SPEAR for something other than code, budget for
> writing and testing that adapter — it is the single biggest task the CLI does not hand you.
> A code repo skips this entirely.

---

## Step 2 — One-time local build + the split-tracking `.gitignore`

Add `scripts/setup-spear.sh` — idempotent, resolves the current checkout's root (so it works in
the main repo and in any `git worktree`), needs Node ≥ 20, and hard-asserts the built CLI
reports the version you vendored:

```bash
#!/usr/bin/env bash
# One-time, idempotent build of the vendored spear-cli for THIS checkout. Safe to re-run.
set -euo pipefail
EXPECTED_VERSION="<version>"                        # match tools/spear-cli/VENDORED.md
ROOT="$(git rev-parse --show-toplevel)"             # current checkout (main repo OR worktree)
SPEAR_DIR="$ROOT/tools/spear-cli"
command -v node >/dev/null || { echo "✗ need Node >= 20 on PATH" >&2; exit 1; }
cd "$SPEAR_DIR"
[ -f package-lock.json ] && npm ci || npm install   # reproducible from the lockfile
npm run build
ACTUAL="$(node dist/cli.js --version 2>/dev/null | tr -d '[:space:]')"
[ "$ACTUAL" = "$EXPECTED_VERSION" ] || { echo "✗ built version '$ACTUAL' != '$EXPECTED_VERSION'" >&2; exit 1; }
echo "✓ spear ready (v$ACTUAL) at tools/spear-cli/dist/cli.js"
```

Then add the **split-tracking** rules to the repo-root `.gitignore` (the tracked `.gitignore`,
**not** `.git/info/exclude`, so the rules are portable across devices):

```gitignore
# spear-cli build outputs (rebuilt locally by scripts/setup-spear.sh)
tools/spear-cli/dist/
tools/spear-cli/node_modules/

# SPEAR runtime + bulky evidence — reproducible, keep out of git
.spear/*/state.json
.spear/*/rounds/
.spear/*/output/
.spear/*/workspace/
.spear/*/evidence/
.spear/*/grader/
```

### What lives where

| Path | Tracked? | What |
|------|----------|------|
| `tools/spear-cli/` (source) | ✅ tracked | Vendored tool source (Apache-2.0). Read-only third-party code. |
| `tools/spear-cli/dist/`, `node_modules/` | 🚫 ignored | Built locally by `setup-spear.sh`. |
| `scripts/setup-spear.sh` | ✅ tracked | One-time local build. |
| `.claude/commands/spear-start.md` | ✅ tracked | The gated runner skill (Step 4). |
| `factory/`, `AGENTS.md` | ✅ tracked | Inherited agent context (Step 3). |
| `.spear/<slug>/SCOPE\|PLAN\|ASSESS\|RESOLVE\|PR.md` | ✅ tracked | Per-cycle decision trail. |
| `.spear/<slug>/{state.json,rounds,output,workspace,evidence,grader}` | 🚫 ignored | Volatile runtime + bulky artifacts. |

> **Lesson — the spec files are the durable record; runtime is reproducible.** The
> `SCOPE/PLAN/ASSESS/RESOLVE/PR` files are *why you decided what you decided* — commit them. The
> `state.json`, per-round logs, and evidence blobs regenerate on the next run — ignoring them
> keeps history clean and diffs reviewable. Put the exclusion in the tracked `.gitignore` so a
> new device inherits it.

Make the script executable once (`chmod +x scripts/setup-spear.sh`) so it can be run directly.
New-device setup, forever after, is one line per checkout (and once per worktree):

```bash
bash scripts/setup-spear.sh    # or ./scripts/setup-spear.sh once it is chmod +x
```

---

## Step 3 — The `factory/` context tiers (what every agent inherits)

SPEAR's architecture layer is a small hierarchy of context files that **every** agent run
inherits. This is how you *"change behavior at scale by changing the foundation"* rather than
reviewing every output. Create `factory/` with four tiers, broad → narrow. Each tier
conceptually sits above the next; **none imports another** (see loading, below).

| Tier | File | Holds | Durability |
|------|------|-------|------------|
| FOUNDATION | `COMPANY.md` | identity · beliefs · values (mission, vision) — what "good" means | most stable |
| DOMAIN | `FUNCTION.md` | the craft, reused across products — engineering · design · ops (principles, patterns, processes) | stable |
| MODULE | `PRODUCT.md` | goal · approach · examples; the surfaces the product ships | changes when approach shifts |
| INTERFACE | `SURFACE.md` | one interface each (API / mobile / web) — role · stack · constraints | changes with the surface |

### How it loads — import all four from `AGENTS.md`

Put an `AGENTS.md` at the repo root that imports all four tiers explicitly (and let `CLAUDE.md`
be a one-line `@AGENTS.md` importer, if your assistant reads that):

```
@factory/COMPANY.md
@factory/FUNCTION.md
@factory/PRODUCT.md
@factory/SURFACE.md
```

Import all four **directly** — do **not** chain them (`SURFACE` imports `PRODUCT` imports …).
Import recursion is capped and dedup is not guaranteed, so a chain either drops the top tier
past the hop limit or inlines shared tiers several times. Importing all four directly loads each
exactly once.

### The durability rule (keep the tiers clean)

These files must be **time-invariant** — true across refactors. Keep implementation detail (file
paths, function names, model ids, token limits, retry counts) **out** — that lives in code.
Inclusion test: *"will this still be true after the next refactor?"* If no, it belongs in code,
not here. Deeper specs are **referenced, not copied** — because *"duplication means drift"*: one
fact, one home, so nothing falls out of sync.

### Operator: fill these in for your repo

The tier *structure* is generic; the *content* is yours. Do not copy another product's business
logic — write your own. Fill each prompt with a few durable lines:

> **`COMPANY.md` (FOUNDATION) — operator, add:** who you are (identity), your mission (what
> you're here to do), your vision (where it goes), and the **values you hold even when they cost
> you** — the tie-breakers for when speed collides with quality, revenue with trust. *Culture is
> what agents actually do when values collide, not a slogan.*

> **`FUNCTION.md` (DOMAIN) — operator, add:** your engineering / design / ops **craft** —
> durable principles, patterns, and processes reused across everything you build (e.g. "how we
> test", "what belongs in code vs a spec", "our review discipline"). Reusable *how*, never a
> specific feature's detail.

> **`PRODUCT.md` (MODULE) — operator, add:** the product's **goal**, **approach**, and a couple
> of **examples**; the customer surfaces it ships. Point at deeper specs; don't restate them.

> **`SURFACE.md` (INTERFACE) — operator, add:** one section per surface (API / mobile / web /
> tooling) — its **role**, its **stack** (durable: platform + framework, not build versions),
> and the **constraints** specific to it.

> **Deferred companions (optional).** Ryan's model also pairs `ROLE.md` (a coding-agent's role
> and mandate) and `DECISIONS.md` (an append-only, timestamped decision log). Add them when you
> onboard agents that need an explicit role contract or a cross-cutting decision trail.

---

## Step 4 — The gated runner skill (`/spear-start`)

**This is the most important step, and the one the tool does not do for you.** `spear-cli`
enforces phase *order* via exit codes and approval files; it does **not** stop and ask a human
anything. The three **⛔ human gates** are the obligation of a **runner skill** you write —
`.claude/commands/spear-start.md` — that drives the tool and pauses for explicit human approval.

> **Lesson — the loop will not stop for you unless the skill makes it.** Without a gated runner,
> the pipeline runs straight to convergence and the human only finds out afterward that it never
> paused. Human acceptance must be an explicit, skill-enforced step, not a hope. A *skill*
> (markdown the assistant reads) is probabilistic; back it with the tool's *deterministic* exit
> codes so order can't be skipped even if judgment is.

The skill must encode:

1. **Invocation.** Resolve the CLI from the current checkout so it works in any worktree:
   `node "$(git rev-parse --show-toplevel)/tools/spear-cli/dist/cli.js" <cmd>`. Pass
   `--name <slug>` on **every** command once more than one slug exists (auto-resolve only works
   with exactly one).

   > **Lesson — invoke inline; a shell variable for the command is fragile.** Stuffing the
   > multi-word invocation into a variable (`SPEAR="node …/cli.js"; $SPEAR scope`) breaks in
   > some shells (e.g. zsh) because the variable does not word-split — you get
   > `no such file or directory: node …/cli.js` (exit 127). Write the `node …` invocation out
   > inline every time, or wrap it in a shell *function*, never a bare variable.

2. **Three ⛔ gates**, each presenting the artifact with a clickable link and **stopping** for an
   explicit human "yes" (never proceed on silence):
   - **Gate 1 — Scope.** After `spear scope` exits 0, present the full `SCOPE.md`. On approval:
     `spear approve scope --name <slug>`.
   - **Gate 2 — Plan.** After the plan is written and `spear plan` exits 0, present `PLAN.md`
     plus the feature-specific `ASSESS.md` additions. On approval: `spear approve plan`.
   - **Gate 3 — Resolve (the output gate).** After the loop converges, present the finished work
     for explicit ACCEPT/REJECT before anything is committed or closed. For user-visible
     behavior this means a **real human/device check**, recorded under `evidence/`.

   > **Lesson — gates must be unconditional and machine-checked, with versioned names.** A
   > *conditional* gate ("ask only if something looks unusual") makes "nothing detected" equal
   > "approved" — exactly the runs where drift hides. A prose-only gate gets skipped under
   > pressure. Record approval as a machine-checkable token (an approval file or a named
   > checkbox like `[x] approved scope`, versioned per phase) so a stale approval from a previous
   > run cannot satisfy a new one. Always present a **clickable link** to each artifact — humans
   > can't approve what they can't find.

3. **Scope discipline.** Require the `SCOPE.md` "Done means" to be **observable behavior**, not
   implementation facts; every criterion that *can* be an executable check (test/build/lint)
   **must** be one, with inspection a last resort. Bug fixes require a **red→green** regression
   pin — a test that demonstrably FAILS on pre-fix code, then passes — with both runs archived.

4. **`MAX_ROUNDS`.** Set it in `SCOPE.md` in the exact recognized form `` `MAX_ROUNDS = N` ``
   (backticked, `=` not `:`, on its own line). The CLI enforces the cap; treat exit 3 as the
   escalate-to-human signal — do not hand-track rounds.

---

## Step 5 — Run a cycle

The pipeline, driven through the skill:

```bash
S="node $(git rev-parse --show-toplevel)/tools/spear-cli/dist/cli.js"   # illustrative only —
                                                                        # invoke inline in practice
$S init code <slug> --gated        # scaffold .spear/<slug>/ with gated approvals required
# → fill SCOPE.md, then Gate 1
$S scope  --name <slug>            # exit 0 = valid   ⛔ GATE 1 → $S approve scope --name <slug>
# → write PLAN.md + ASSESS.md additions, tick [x] User confirmed, then Gate 2
$S plan   --name <slug>            # exit 0 = valid   ⛔ GATE 2 → $S approve plan  --name <slug>
# → implement the plan steps, then iterate:
$S loop   --name <slug>            # ONE round: execute + assess
#   exit 0 = converged/stop honored · 1 = execution failure · 2 = defects open (normal) ·
#   3 = MAX_ROUNDS exhausted → STOP, escalate to human
# → apply fixes, re-loop until converged      ⛔ GATE 3 (accept) → then commit + close
$S resolve --write PR.md --name <slug>
```

Exit codes drive the loop (`0` pass/converged · `1` phase failed · `2` defects open · `3`
MAX_ROUNDS hit). Each `loop` call is **exactly one round**; the CLI enforces the cap itself.

### Slugs — one per unit of work, and parallel-safety

A **slug** names one SPEAR project directory, `.spear/<slug>/`. The model is **one slug = one
unit of work** (one issue/ticket/task): its own `SCOPE/PLAN/ASSESS/RESOLVE/PR` and its own
runtime. **One repo hosts many slugs at once**, and old ones are never auto-cleaned — so `.spear/`
accumulates them. Getting slugs right is the difference between a smooth cycle and cross-cycle
corruption, so set them up deliberately:

- **Pick a short, stable kebab-case slug** from the work item's title, and keep it for the whole
  cycle. Scaffold with the project type: `spear init code <slug> --gated`.
- **Pass `--name <slug>` on EVERY command.** Omitting it (auto-resolve) works **only when exactly
  one slug exists** under `.spear/`. The moment a second slug exists — which is the normal state —
  an un-named command is ambiguous and can act on the wrong project. Always name it explicitly.

**Running cycles in parallel — a separate slug per chat/agent.** Separate slugs isolate SPEAR
*state* (each cycle's `SCOPE/PLAN/ASSESS`, `state.json`, approvals), so two cycles don't overwrite
each other. If your runner skill hardcodes a single slug, two concurrent runs collide on that one
slot and step on each other's spec files and `state.json` — the symptom is a session that refuses
to continue because another cycle's work is live in the shared slot. The fix is to give each
concurrent chat/agent its **own** slug.

> **Lesson — slugs isolate SPEAR state, but NOT git.** The `code` adapter inspects the **whole**
> repo (`git status`), and a naive closeout does `git add -A`. So two slugs sharing one working
> tree still see each other's uncommitted edits — at Assess (a whole-repo scan is fooled by another
> cycle's changed files) and at commit (`git add -A` sweeps them in). Two ways to stay
> parallel-safe, weakest to strongest:
>
> - **Scoped commits (minimum):** commit only the paths *this* cycle touched, plus its own
>   `.spear/<slug>/` spec files — `git add <those paths>`, **never** `git add -A` — and serialize
>   the commit step across concurrent cycles so they don't interleave.
> - **Worktree isolation (stronger):** run each parallel cycle in its own `git worktree`, so the
>   working trees never overlap. Run the one-time build script (Step 2) once per worktree, since
>   it resolves the *current* checkout's root.

### Independent, ratcheting grading (never self-graded)

Assess has a deterministic half (the adapter) and a **judgment** half (does this actually meet
the scope?). The judgment half must be graded by someone **other than the author of the work**,
and **stricter every round** — canon: *"each assess pass is stricter than the last."*

`spear-cli` supports `--grader "<command>"`. If a headless assistant CLI is available, point the
grader at it (read-only tools). If not, **emulate** an independent grader: spawn a **fresh
adversarial subagent** each round, hand it the `ASSESS.md` rubric + the artifact paths + the
tool's grade-output contract, and tell it to **gather its own fresh proof** (run the checks
itself) rather than trusting saved reports. Have it ratchet: raise the evidence bar and attack a
dimension the previous round didn't.

> **Lesson — a grader that trusts saved reports proves nothing.** The grade must be produced by
> re-running the checks at the **final** artifact state. Green evidence is **void the moment the
> artifact is edited again** — before declaring convergence, re-verify the headline checks at the
> final state (and confirm any recorded lineage, like a content hash, matches what actually
> shipped). Converging on evidence from a superseded version is falsified convergence.

> **Lesson — if you wrap the grader in a shell script, drain stdin.** The CLI pipes the grader
> prompt to the wrapper on stdin. A wrapper that emits a canned grade without reading stdin makes
> the CLI die with `EPIPE`. The wrapper must consume stdin first (`cat > /dev/null`) before
> writing its grade. Also: have the grader use a **distinctive filename marker** for any probe
> files it creates, and check `git status` for remnants after it returns *or dies* — a grader
> that crashes mid-run can leave build-breaking files behind.

### RED-first for behavior changes — even when the fix extracts a new seam

Every behavior-change pin must exercise the **real** code path and **fail before the fix**. When
the fix *extracts* a new function/seam that didn't exist pre-fix, capture honest RED anyway:
temporarily populate the new seam with the **pre-fix behavior**, run the pins (they must FAIL),
archive that run, then restore the fix and confirm GREEN — so the archived RED exercises the
real shipped seam, not a throwaway shim.

### The flywheel step (Gate 3) — the tool does NOT do this

`spear-cli` is a converge-the-artifact loop; it does **not** implement the software factory's
**flywheel** (recursive self-improvement). So the flywheel lives in the **skill**: at Gate 3,
every cycle, surface a **flywheel item**. If the cycle exposed a *recurring or systemic* mistake
— a human correction at a gate, the same defect count stuck ≥2 rounds, a grader defect with no
matching rubric line, or a falsified-convergence re-entry — propose a **minimal, human-gated**
improvement to the *right governing artifact* (the rubric, a `factory/` tier, `AGENTS.md`, this
guide, a spec/prompt, or the skill itself), framed **root-cause → permanent guardrail**, and end
with an explicit **"your call."** Never edit governance silently. If nothing systemic surfaced,
say so explicitly ("no flywheel item this cycle") — don't invent one. This is how each resolved
issue makes the next one less likely.

---

## Step 6 — Verify the setup end-to-end

Before trusting the pipeline, prove each guard fires. In a scratch checkout:

- `scripts/setup-spear.sh` builds and the CLI reports the expected version.
- An **incomplete `SCOPE.md`** fails `spear scope` (nonzero); a complete one passes.
- `spear plan` **fails** while `PLAN.md` is unapproved and **passes** once approved — proving the
  gate is real, not decorative.
- A `loop` round emits structured defects (`--json`) that the assistant can read and act on;
  exit codes match the table above.
- With a passing grader, the loop reaches **exit 0** — and any advisory (non-blocking) items are
  still surfaced to the human, not silently dropped.
- Revert every test mutation.

> **Lesson — a gate you never watched fire is a gate you don't have.** Verifying that an
> *unapproved* plan blocks (not just that an approved one passes) is what tells you the gate is
> load-bearing.

---

## Recurring operational gotchas (generalized)

These bite mid-cycle regardless of repo:

- **Always pass `--name <slug>`.** Auto-resolve works only when exactly one slug exists under
  `.spear/`; more than one is normal, so an un-named command can hit the wrong project. For
  parallel cycles use a separate slug per chat/agent and commit **scoped** (never `git add -A`),
  or isolate each in its own `git worktree` (see *Slugs* in Step 5).
- **Inline invocation only.** `node "$(git rev-parse --show-toplevel)/…/cli.js" <cmd>`; a
  command stuffed in a shell variable fails in shells that don't word-split it (exit 127).
- **Grader wrapper drains stdin.** `cat > /dev/null` before emitting the grade, or the CLI dies
  `EPIPE`. Write the grade via a file/write path the wrapper controls, not a fragile heredoc.
- **Re-entry is sticky.** After a falsified convergence, re-validating scope **rewinds** the
  phase machine — but **approvals persist** across the rewind. Revoke them
  (`approve scope --revoke`, `approve plan --revoke`) so the gates actually re-arm. The
  `<spear-complete/>` stop marker also persists — neutralize the stale one or the next `loop`
  honors the old stop instantly.
- **`resolve --write PR.md` writes to the CWD** (repo root), not the slug dir. Move it into
  `.spear/<slug>/` and hand-amend it feature-first; do **not** re-run `resolve --write` after
  amending — it clobbers your edits.
- **`assess` rewrites `RESOLVE.md`**, clobbering any prior report block and completion marker. So
  order it: run `assess` first, then re-append your report + completion marker, then run `loop`
  last to register the stop.
- **Worktree vs main-repo paths.** In a `git worktree` session, run every grep/read/edit with
  paths **relative to the worktree root**; never `cd` into a sibling checkout of the same repo.
  A shell's working directory persists between calls, and a sibling checkout can hold a
  **different committed version** of the same file — a stray `cd` silently reads the wrong copy
  and you get phantom line-number drift mid-edit. Prefer `git rev-parse --show-toplevel` (which
  resolves to the *current* checkout) over hard-coded paths.
- **Whole-repo adapter noise.** The `code` adapter scans the **entire** repo, so it surfaces
  pre-existing hits in vendored/third-party source and in other agents' in-flight work.
  Document those under an `ASSESS.md` "Known acceptable" section and register the stop with
  `--allow-fast-convergence` — never edit third-party source to force the count to zero.
- **Cross-layer pins move together.** If a rule is pinned at more than one altitude (a unit test
  *and* an integration test asserting the same branch), a behavior change must update every
  altitude in the same commit — grep the rule's name across the whole test tree, not just the
  nearest file.

---

## Concepts behind SPEAR — the software-factory method

SPEAR and the surrounding "software factory" method are Ryan Waliany's. Read these to understand
*why* each piece above exists; the local files distill the *how*.

- **[The Software Factory](https://www.ryanwaliany.com/software-factory/)** — the whole model:
  the four inherited context tiers, the SPEAR loop, the flywheel, and the process/checklist
  discipline. Start here. *"Goal in, tested PR out"* — and *"one source of truth keeps a hundred
  agents pulling in the same direction."*
- **[Introducing SPEAR](https://www.edge.ceo/p/introducing-spear-the-management)** — the five
  phases (Scope → Plan → Execute → Assess → Resolve); the human gates; the assessment rubric is
  **MECE** (mutually exclusive, collectively exhaustive) and gets **stricter every round**, only
  a perfect score passing. The thesis: *"The strong-start-weak-finish problem in AI is a process
  problem, not a model problem."*
- **[The Tools That Scale My Team](https://www.edge.ceo/p/the-tools-that-scale-my-team-now)** —
  behavior scales by codifying identity/values into a layered spec **foundation**, not by
  reviewing every output (which caps velocity at human reading speed). *"The only way to change
  behavior at scale is to change the foundation."* This is why `factory/` is tiered.
- **[Managing AI Is Managing Entropy](https://www.edge.ceo/p/managing-ai-is-managing-entropy)** —
  keep specs at Marr's **L1** (why / computational) and **L2** (how / algorithmic) and push
  **L3** implementation detail back into code, or specs rot on every refactor. The deciding
  question: *"will this still be true after the next refactor?"* And: *"duplication means drift"*
  — *"managing AI is managing entropy."*
- **[Design the Road for the Car](https://www.edge.ceo/p/design-the-road-for-the-car)** —
  *"control the environment, not the model"*: redesign formats and interfaces to be AI-native
  rather than forcing the model to cope with legacy constraints. *"The car did not get
  dramatically better in those decades. The road did."* — *"a cleaner environment lets an
  ordinary model look unusually capable."*
