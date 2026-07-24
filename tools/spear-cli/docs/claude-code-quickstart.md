# Claude Code Quickstart

How to drive `spear` from Claude Code (or any LLM with shell access).

## Install once

```bash
git clone https://github.com/ryanwaliany/spear-cli ~/Projects/spear-cli
cd ~/Projects/spear-cli
npm install && npm run build && npm link
```

Verify:

```bash
spear --version
```

## Pattern: tell Claude what you want, let it run the CLI

In any directory, open Claude Code:

```bash
mkdir my-deck && cd my-deck
claude
```

Then in Claude Code:

> Build me an LP deck for [your fund]. Use SPEAR — run `spear init deck`, then walk me through SCOPE, then iterate until convergence.

Claude does this:

```bash
$ spear init deck                    # scaffolds the project
# Claude reads the placeholder SCOPE.md, asks you for the missing pieces
# (goal, audience, constraints), edits SCOPE.md
$ spear scope                        # exit 0 = ready
$ spear plan                         # fails: PLAN.md not approved
# Claude writes PLAN.md based on SCOPE.md, shows it to you
# You mark [x] User confirmed
$ spear plan                         # exit 0 = ready
# Claude generates .spear/<slug>/workspace/deck/build.js
$ spear loop --json --max-rounds 1   # one round
# CLI returns structured JSON of defects
# Claude reads each Slide N JPEG via the Read tool
# Claude scores against ASSESS.md, writes targeted edits to build.js
$ spear loop --json --max-rounds 1   # re-render + re-assess
# repeat until exit 0
```

## Why exit codes matter

The CLI's exit code tells the LLM what to do next:

| Exit | Meaning | LLM action |
|------|---------|------------|
| 0 | Phase passed / converged | Move to next phase or stop |
| 1 | Phase failed (validation gap) | Fix the gap, re-run |
| 2 | Defects open (assess found things to fix) | Apply fixes, re-run |
| 3 | MAX_ROUNDS exhausted | Escalate to user |

Claude can write a tight loop:

```bash
while ! spear loop --json --max-rounds 1; do
  # fixes happen here via the LLM
done
```

## Reading defects via JSON

```bash
$ spear assess --json
```

```json
{
  "round": 3,
  "totalUnits": 14,
  "perUnitScores": { "Slide 1": 9, "Slide 7": 7 },
  "defects": [
    {
      "unit": "Slide 7",
      "metric": "F (headline-orphan)",
      "severity": "medium",
      "description": "RESPONDED wraps to 2 lines on chamber 4",
      "mechanical": false
    }
  ],
  "converged": false,
  "timestamp": "2026-05-02T03:14:00Z"
}
```

The LLM parses this, prioritizes by severity, and edits `build.js`. No prose, no ambiguity.

## Slash commands (optional)

If you want Claude to recognize `/spear`, add a slash command. Drop this in `~/.claude/commands/spear.md`:

```markdown
---
description: Run SPEAR CLI in current directory
---

Run `spear $ARGUMENTS` (or `spear --help` if no arguments). The output is structured. Read it and act on the exit code.
```

Then in Claude Code: `/spear init deck`, `/spear loop`, etc.

## Multi-loop status

If you have multiple SPEAR projects iterating in parallel:

```bash
cd ~/Projects/multiple-decks
spear runner --interval 60          # 1-minute check-ins
spear runner --once --json | jq     # one snapshot, machine-readable
```

The runner discovers SPEAR projects in subdirectories (looks for `.spear/state.json`) and prints a status table. No LLM needed.

## Common patterns

### "Resume where I left off"

```bash
cd existing-project
spear status                         # what phase am I in?
spear loop                           # continue
```

State is persisted in `.spear/state.json`. New sessions pick up.

### "Just check, don't fix"

```bash
spear assess --json | jq '.defects[] | select(.mechanical == false)'
```

Outputs only the LLM-actionable defects.

### "Fix everything mechanical, then surface the rest"

```bash
spear resolve --apply                # auto-fix mechanical
spear assess --json | jq '.defects'  # what's left = subjective
```

(`--apply` is currently a stub; per-adapter mechanical fixers are roadmap.)

## Troubleshooting

- **`spear: command not found`** — run `npm link` in the cloned repo, or use the full path: `node ~/Projects/spear-cli/dist/cli.js`.
- **`No SPEAR project found`** — run `spear init <type>` first.
- **`PLAN.md not approved`** — open PLAN.md, change `[ ] User confirmed` to `[x] User confirmed`.
- **`Execute failed: libreoffice not available`** — install LibreOffice (`brew install --cask libreoffice` on Mac).
