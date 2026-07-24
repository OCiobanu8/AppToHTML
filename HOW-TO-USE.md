# How to use: Beads & SPEAR (contributor guide)

This repo ships with two workflow tools. This guide explains them in plain language. You're a
developer, but you don't need any prior context — read top to bottom once and you're set.

**The 10-second version:**

- **Beads (`bd`)** = a shared to-do list with a memory. It's where *all* tasks live. Not
  markdown TODOs, not sticky notes — Beads.
- **SPEAR (`/spear-start`)** = a checklist robot that runs a task through 5 fixed steps and won't
  let you skip any, pausing for a human thumbs-up at 3 points so nothing ships unchecked.

They connect simply: **one Beads issue = one SPEAR run.**

---

## 1. One-time setup (do this once per fresh clone)

You need **Node 20 or newer** and **Git**.

**a) Install Beads** (it's a global CLI you install once, not part of this repo):

```bash
npm install -g @beads/bd
bd version           # should print a version — you're good
```

**b) Build SPEAR** (its code *is* vendored in this repo; you just compile it locally):

```bash
# macOS / Linux / Git Bash:
bash scripts/setup-spear.sh

# Windows PowerShell:
scripts/setup-spear.ps1
```

That's it. You only redo (b) after pulling changes to the tool, or once per new `git worktree`.

> Why build locally instead of committing the built files? Because a fresh clone on any machine
> should just work, without shipping bulky compiled output in git.

---

## 2. Beads — the shared to-do list

Think of Beads as GitHub Issues that live **inside the repo** and that agents can read and write.
Every task, bug, and chore is a "bead" with an id like `a2h-bmw` (our prefix is `a2h`).

### The only 5 commands you need day-to-day

```bash
bd ready                     # What can I work on right now? (nothing blocking it)
bd create "Fix the X bug"    # Make a new task
bd show a2h-bmw              # Read one task's details
bd update a2h-bmw --claim   # "I'm working on this now" (assigns it to you, marks in-progress)
bd close a2h-bmw            # Done!
```

### The golden rules (please follow these)

- ✅ **Every piece of work is a bead.** Before you start coding, there should be a bead for it.
- ❌ **Don't use markdown TODO lists** or scratch `.md` task files. That's what Beads replaces.
- 🧠 **Save lasting knowledge with `bd remember`,** not in random notes files. It's the project's
  memory.
- 🔎 **Lost?** Run `bd prime` — it prints the full command reference and how to wrap up a session.

### How beads are shared (the sync model)

Think of the beads database as **"git, but for your to-do list."** Every contributor has their
own full local copy — and it behaves exactly like a git repo of code:

- **`bd ready` / `bd show` / `bd list` read only *your local copy*.** They're instant and work
  offline. They are **not** a live view of the server.
- **Your changes stay on your machine until you sync.** `bd create`, `bd close`, `bd update` —
  all local until published.
- **Sync = push + pull, just like git.** Pushing publishes *your* changes; pulling fetches
  *teammates'* beads and merges them into your local copy. (Under the hood they ride on a special
  git ref, `refs/dolt/data`, separate from your code commits — the beads are **not** part of a
  PR's diff.)

So a teammate sees your closed bead **only after they pull** — and you see theirs only after you
pull. Same as `git log` not showing a commit until you fetch it. Merging a PR does **not** change
any bead's status; issue state travels on the sync channel, not in your code merge.

**Good news:** this repo installed git hooks that **piggyback the beads sync onto your normal
`git pull` / `git push`**, so in everyday git workflow it mostly stays in sync on its own.

**Rules of thumb:**
- **Pull before you start** so `bd ready` reflects the latest team state.
- If two people edit beads at once, Dolt **merges** them like git (conflicts are possible and
  resolved the git way).
- Unsure whether to push beads? **Ask** — don't force anything.

---

## 3. SPEAR — the "don't skip steps" pipeline

SPEAR stands for the 5 steps every task goes through, in order:

**S**cope → **P**lan → **E**xecute → **A**ssess → **R**esolve.

The idea in one sentence: *AI (and people) start strong and finish weak because they skip the
boring end steps — so SPEAR makes all 5 steps mandatory and fast.*

You never call the raw tool by hand. You run one command in Claude Code:

```
/spear-start <slug> <short description>
```

A **slug** is a short nickname for the task, like `fix-merge-dupes`. **Use the bead id (or a
short name from its title) as the slug — one slug = one bead.**

### What actually happens (and the 3 stop signs 🛑)

1. **Scope** — you write down *what "done" means* (as things you can actually test, not vague
   wishes). 🛑 **A human must approve the scope.**
2. **Plan** — you write down *how* you'll do it. 🛑 **A human must approve the plan.**
3. **Execute + Assess** — you build it, then it gets graded against the scope. If it's not good
   enough, it loops and tries again — **stricter every round.** There's a hard cap on rounds so
   it can't loop forever.
4. **Resolve** — 🛑 **A human gives the final ACCEPT/REJECT** before anything is committed. For
   anything a user can see, this means *actually checking it works*, not just trusting a report.

Those three 🛑 stops are the whole point: **the robot enforces the order; a human enforces the
judgment.** It will never proceed on silence — you must say "yes."

### Two rules that save you pain

- **One task, one slug.** Running two tasks? Give each its own slug so they don't scribble over
  each other's notes.
- **Commit only your task's files.** Never `git add -A` — it sweeps in other people's in-progress
  work and the vendored tool. Add just the paths your task touched.

---

## 4. How they work together (the normal flow)

```
bd ready                         # 1. pick a task
bd update a2h-xyz --claim       # 2. claim it
/spear-start a2h-xyz "short desc"  # 3. run it through the 5 gated steps
#   ... approve scope 🛑 ... approve plan 🛑 ... accept result 🛑 ...
bd close a2h-xyz                # 4. mark the task done
```

That's the entire contributor loop.

---

## 5. Cheat sheet

| I want to… | Do this |
|---|---|
| See what to work on | `bd ready` |
| Start a task | `bd update <id> --claim` |
| Run the full process on it | `/spear-start <slug> <desc>` |
| Finish a task | `bd close <id>` |
| Remember something for the project | `bd remember "<the fact>"` |
| Get un-stuck on beads | `bd prime` |
| Rebuild SPEAR after a pull | `bash scripts/setup-spear.sh` (or `.ps1`) |

**Don'ts:** no markdown TODO lists (use beads) · no `git add -A` (scope your commits) · don't
hand-edit `tools/spear-cli/**` (it's vendored third-party code — re-vendor to update) · never
approve a SPEAR gate you didn't actually check.

---

*Deeper detail:* the exact gated procedure lives in `.claude/commands/spear-start.md`; the
vendoring/attribution notes are in `tools/spear-cli/VENDORED.md` and the tool's own
`tools/spear-cli/README.md`; the agent-facing rules live in `AGENTS.md` / `CLAUDE.md` and the
`factory/` context tiers.
