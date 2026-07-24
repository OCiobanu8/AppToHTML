# SPEAR

> **Five checkpoints, not five months.** A deterministic CLI that turns AI assistants from strong-start, weak-finish demos into systems that actually finish what they start.

SPEAR is **Scope → Plan → Execute → Assess → Resolve** — the five phases of project management, compressed to seconds and applied to AI execution loops. This CLI enforces phase transitions, runs deterministic rubric checks, and surfaces structured defects for an LLM (or human) to fix.

The methodology is from [this blog post](https://ryanwaliany.com/posts/09-spear). This CLI distills it into a runtime any LLM (Claude Code, Cursor, Copilot, Aider) can call via Bash.

```
┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
│  SCOPE   │──▶│   PLAN   │──▶│ EXECUTE  │──▶│  ASSESS  │──▶│ RESOLVE  │──┐
└──────────┘   └──────────┘   └──────────┘   └──────────┘   └──────────┘  │
     ▲                                                                     │
     └─────────────────── iterate up to MAX_ROUNDS ───────────────────────┘
```

## Why a CLI, not a skill or prompt?

Claude Code skills (markdown files Claude reads) are **probabilistic** — Claude decides which steps to follow and how strictly. A CLI is **deterministic** — the same inputs produce the same outputs every time.

SPEAR has two kinds of work:
- **Mechanical work** that should be enforced (run the build, validate SCOPE.md is filled, check for `console.log` leftovers, render a deck to JPEGs, count words). This belongs in the CLI.
- **Subjective work** that needs judgment (does the headline IS the punchline? does the voice match the brand? does this paragraph cut filler?). This belongs to the LLM.

The CLI runs deterministically. The LLM does the creative work. The combination enforces the protocol while keeping the parts that need judgment fast and flexible.

## Install

```bash
git clone https://github.com/ryanwaliany/spear-cli ~/Projects/spear-cli
cd ~/Projects/spear-cli
npm install
npm run build
npm link    # registers `spear` binary globally
```

Or via npm (once published):

```bash
npm install -g @waliany/spear
```

## Project layout — `.spear/<slug>/`

A SPEAR project lives in a slug-named subdirectory under `.spear/`. One repo can host many projects (e.g., a deck + a blog + the code itself), each with its own scope, plan, evidence, and rounds — no top-level pollution.

```
my-repo/
├── src/                       ← your actual project
├── .spear/
│   ├── deck/                  ← slug 1
│   │   ├── SCOPE.md  PLAN.md  ASSESS.md  RESOLVE.md   ← tracked
│   │   ├── state.json  rounds/                         ← gitignored (runtime)
│   │   ├── workspace/         ← deck source (build.js, etc.)
│   │   └── output/            ← deck.pptx
│   └── post-launch/           ← slug 2 (a blog)
│       ├── SCOPE.md  ...
│       ├── workspace/draft.md   ← (rooted at .spear/post-launch/workspace/)
│       └── ...
```

Single-slug repos auto-resolve. Multi-slug repos require `--name <slug>` (or `SPEAR_PROJECT=<slug>`).

## 60-second quickstart

```bash
cd my-repo
spear init blog post-launch        # creates .spear/post-launch/
# edit .spear/post-launch/SCOPE.md (goal, audience, constraints, done means)
spear scope --name post-launch     # validates SCOPE.md (auto-resolves if it's the only slug)
# Have your LLM draft the post in .spear/post-launch/workspace/draft.md
spear loop                         # full pipeline: execute + assess
# CLI surfaces defects + evidence in RESOLVE.md; LLM applies fixes; re-run
# Repeat until exit 0 (converged) or LLM marks <spear-complete/>
spear resolve --write              # close out: write CLOSEOUT.md (or PR.md)
```

## Commands

```
spear init <type> [name]   Scaffold .spear/<name>/ (name defaults to type)
spear list                 Enumerate every SPEAR project in this repo
spear scope                Validate SCOPE.md (exits 1 with gaps)
spear plan                 Validate PLAN.md exists + is approved (exits 1 if not)
spear execute              Run the artifact build (deck → pptx, code → tests, etc.)
spear assess               Run rubric checks, write RESOLVE.md, exit nonzero if defects
spear loop                 Orchestrate full pipeline: execute → assess → loop
spear resolve              Closing phase — render PR / closeout report
spear status               Detailed status for one project
spear runner               Multi-loop status reporter for parallel projects
spear image                Generate a single image via OpenAI
spear config               Manage user config (~/.spear/config.json)
```

Every phase command takes `--name <slug>` to disambiguate when there are multiple projects in `.spear/`. Most support `--json` for piping.

Most commands support `--json` for piping to other tools.

## How an LLM uses it (Claude Code example)

The LLM never invents progress. Every phase transition goes through the CLI:

```bash
$ spear init deck             # Bash via Claude Code
✓ scaffolded

# Claude edits SCOPE.md with the user's input
$ spear scope                 # exit 0 = ready to plan
✓ SCOPE.md is valid.

# Claude writes PLAN.md based on SCOPE.md
# user types `[x] User confirmed` in PLAN.md
$ spear plan                  # exit 0 = ready to execute
✓ PLAN.md is valid.

# Claude generates .spear/<slug>/workspace/deck/build.js
$ spear execute               # node build.js + LibreOffice render
✓ Execute complete.
  ✓ .spear/<slug>/workspace/deck/build.js exists
  ✓ npm install
  ✓ node build.js
  ✓ .spear/<slug>/output/deck.pptx exists
  ✓ libreoffice available
  ✓ pptx → pdf
  ✓ pdf → jpegs

$ spear assess --json
# CLI emits structured JSON defect list:
{
  "round": 1,
  "defects": [
    { "unit": "Slide 1", "metric": "rubric", "mechanical": false,
      "description": "Score against ASSESS.md (read .spear/<slug>/workspace/qa/v-01.jpg)" },
    ...
  ],
  "converged": false
}

# Claude reads each defect, opens the JPEG, scores it, picks fixes,
# edits build.js, then:
$ spear loop                  # re-execute + re-assess
# exit 0 = converged. exit 2 = defects remain.
```

The LLM does what LLMs are good at: reading rendered output, judging visual quality, writing code. The CLI does what LLMs are bad at: enforcing phase gates, running shell commands consistently, structuring defect reports.

## Evidence, reports, and the stop signal

Every assess pass emits **evidence** — verifiable trace rows that prove what was checked. Mechanical evidence has `expected`, `actual`, and `pass`; subjective evidence points at the artifact (with hash + size) the LLM must read. The principle: *verify computed values, not just claims.* An assess without evidence is not an assess.

```bash
$ ls .spear/rounds/3/
assess.json     # full AssessResult for the round
evidence.json   # all evidence rows (with cwd-relative artifact paths)
evidence/       # copies of every referenced artifact (deck JPEGs, drafts...)
RESOLVE.md      # snapshot of RESOLVE.md as written this round
```

After applying fixes, the LLM appends a structured report block to `RESOLVE.md`. The fields are strict so the CLI can parse them deterministically:

```
<spear-report>
ITERATION: 3
PHASE: resolve
COMPLETED: fixed slide 7 RESPONDED wrap, slide 11 squish
FILES_CHANGED: deck/build.js
TESTS: N/A
NEXT: re-run spear loop
BLOCKERS: None
PROGRESS: 8/10
</spear-report>
```

`spear loop` parses the block on the next call and persists its data into `state.json` (`lastAssess.fixed`, `lastAssess.progress`, `state.blockers`). Adapter-specific fields (`DEFECTS_FIXED`, `COVERAGE_AFTER`, ...) land in `extras`.

When the rubric is satisfied — even if mechanical defects remain — the LLM puts `<spear-complete/>` on its own line in `RESOLVE.md`. `spear loop` honors it as an explicit stop signal. Convergence is a decision the LLM declares, not a side-effect of zero defects. No bonus polish past the close.

Two more durability features ride along:

- **Stuck-loop detection** — if `defectCount` doesn't change across rounds, `spear assess` flags `stuck: true` with `stuckSince: <round>` so the LLM (or a reviewer) notices oscillation instead of grinding to MAX_ROUNDS.
- **Atomic state writes** — `.spear/state.json` is written via temp + rename. Ctrl-C mid-write can't corrupt it.

## Resolve — the Closing phase

Resolve is SPEAR's close-out phase. Its deliverable is a project-closure report — highlights, lowlights, what to test, warnings, next steps — sourced from `state.json`, `<spear-report>` blocks, and per-round evidence.

```bash
spear resolve                       # render to stdout (universal)
spear resolve --write               # write CLOSEOUT.md
spear resolve --write PR.md         # PR body for `gh pr create -F -`
spear resolve --json                # structured PRContext
spear resolve --template my.md      # custom layout
```

The same document fits two delivery channels: in a git repo it becomes the PR description; outside a repo it's the standalone handoff doc. Customize via `.spear/pr-template.md` with `{{var}}` placeholders — variables: `title`, `summary`, `highlights`, `lowlights`, `whatToTest`, `warnings`, `nextSteps`, `rounds`, `evidenceCount`, `defectsRemaining`, `defectsFixed`, `generatedAt`, `type`, `status`.

`spear resolve --next` and `--apply` remain as legacy helpers for use *during* the assess loop (showing the next defect to fix, dispatching mechanical fixers). The default action (no flags) is the close-out.

## Multi-loop runner

For parallel SPEAR loops (multiple deck variants, multiple code modules iterating in parallel), `spear runner` prints a structured status table every N seconds:

```
=== SPEAR check-in — 2026-05-02T02:35:37Z ===

Loop      S   P   E              A    R     Counts                                                Cursor    Last commit                                                  Action
--------  --- --- -------------  ---  ----  ----------------------------------------------------  --------  -----------------------------------------------------------  ------------------
deck-a    ✅  ✅  🟡 12/41         ⏸    ⏸     —                                                     —         —                                                              continue execute (round 12)
deck-b    ✅  ✅  ✅              🟡   ⏸     7 defects @ r4                                        a1b2c3d   round 4 fixes for slides 7, 9, 11                              LLM applies fixes for 7 defect(s)
blog-x    ✅  ⏸                                                                                              —                                                              have LLM write PLAN.md
```

Run with `--once` for CI snapshots; otherwise it loops every 5 minutes (configurable with `--interval`).

## Artifact templates

Each `init` type ships with a custom rubric tuned to that artifact:

- **`deck`** — pyramid headlines, MECE cards, native PPT diagrams, gpt-image-2 illustrations. **36 lettered failure modes (A–JJ)** distilled from a 30-round LP-deck case study.
- **`blog`** — single-thesis discipline, lead-with-anecdote, image cadence every 600 words, voice consistency. 11 lettered failure modes.
- **`code`** — type-check, tests, lint, no `any`, no `console.log`, contract documentation. 12 lettered failure modes.
- **`generic`** — write your own rubric in ASSESS.md.

Add your own template by dropping a folder under `templates/<type>/` with `SCOPE.md`, `PLAN.md`, `ASSESS.md`, `RESOLVE.md`. Implement `Adapter.execute` and `Adapter.assess` in `src/adapters/<type>.ts` for the deterministic checks.

## Project layout

```
spear-cli/
├── README.md                       ← you are here
├── docs/
│   ├── methodology.md              ← long-form essay
│   ├── design-principles.md        ← five principles for SPEAR systems
│   ├── claude-code-quickstart.md   ← LLM-native usage walkthrough
│   └── extending.md                ← write your own adapter
├── src/                            ← TypeScript CLI source
│   ├── cli.ts                      ← entry point (commander)
│   ├── state.ts                    ← read/write SCOPE/PLAN/ASSESS/RESOLVE.md + .spear/state.json
│   ├── types.ts                    ← shared zod schemas (Defect, AssessResult, Status)
│   ├── commands/                   ← one file per CLI subcommand
│   └── adapters/                   ← per-type build + assess (deck, blog, code, generic)
├── templates/                      ← scaffolds copied by `spear init`
├── package.json
└── tsconfig.json
```

## Five design principles (the why)

1. **Every action equals an API call.** Vague actions invite invented progress; concrete operations don't.
2. **Structured I/O.** JSON in, JSON out. Assess is comparing structs, not parsing prose.
3. **Bounded blast radius.** 4KB chunks, 10-item batches. Recoverable failures, not messy ones.
4. **Make the plan visible.** PLAN.md exists on disk before Execute runs.
5. **Cap iterations.** `MAX_ROUNDS` enforced by the CLI, not promised by the LLM.

These map directly to the design choices in this codebase: deterministic file copies, JSON-emitting commands, exit codes that mean things, MAX_ROUNDS in `.spear/state.json`.

Read `docs/design-principles.md` for the long version.

## Roadmap

- [x] Deterministic CLI (init, scope, plan, execute, assess, resolve, loop, status, runner)
- [x] Templates: deck, blog, code, generic
- [x] Mechanical rubric checks per adapter (orphan-wrap, image-text overlap, type-check, etc.)
- [ ] Per-adapter mechanical fixers (`spear resolve --apply` actually patches things)
- [ ] Plugin system: `spear add-template <git-url>`
- [ ] Telemetry: opt-in metrics on convergence rounds, defect categories per artifact
- [ ] Web dashboard for `spear runner` (parses --json into a live UI)

## License

Apache 2.0. Use freely with attribution to Ryan Waliany. See [LICENSE](./LICENSE).

## Citation

- Methodology: https://ryanwaliany.com/posts/09-spear

---

**Five checkpoints, not five months.**
