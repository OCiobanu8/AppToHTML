# RESOLVE — Round 8

Convergence: 16 defects open
Timestamp: 2026-09-14T09:20:04.481Z
Evidence items: 4
⚠ Stuck since round 1 — defect count unchanged across rounds.

## Defects to fix

1. **tools\spear-cli\src\adapters\code.ts / A (any-type)** — `any` type used — use a concrete type
   - Subjective (LLM judgment)
   - Evidence: `code.scan.any-type`

2. **tools\spear-cli\src\adapters\code.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

3. **tools\spear-cli\src\commands\approve.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

4. **tools\spear-cli\src\commands\assess.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

5. **tools\spear-cli\src\commands\config.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

6. **tools\spear-cli\src\commands\execute.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

7. **tools\spear-cli\src\commands\image.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

8. **tools\spear-cli\src\commands\init.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

9. **tools\spear-cli\src\commands\list.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

10. **tools\spear-cli\src\commands\loop.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

11. **tools\spear-cli\src\commands\plan.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

12. **tools\spear-cli\src\commands\resolve.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

13. **tools\spear-cli\src\commands\runner.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

14. **tools\spear-cli\src\commands\scope.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

15. **tools\spear-cli\src\commands\status.ts / I (debug-log)** — `console.log` left in code
   - Fix: remove or replace with proper logger
   - Mechanical (CLI can auto-fix)
   - Evidence: `code.scan.console-log`

16. **all changed files / rubric** — Score against ASSESS.md (contracts, edge cases, race conditions, etc.)
   - Subjective (LLM judgment)
   - Evidence: `code.scan.files-checked`

## Evidence

Mechanical checks (pass/fail with expected vs actual) and subjective pointers (artifacts to read).
Full list in `evidence.json` under the round dir.

- [✗] **code.scan.any-type** — Source files containing `: any` annotations (expected 0, got 1)
- [✗] **code.scan.console-log** — Source files containing console.log() (expected 0, got 14)
- [✓] **code.scan.todo-comment** — Source files containing TODO/FIXME/XXX comments (expected 0, got 0)
- [✓] **code.scan.files-checked** — Source files scanned (expected "> 0", got 27)

## Report (LLM fills this in after applying fixes)

Replace this template with a real <spear-report> block. SPEAR parses it on the next loop call.

```
<spear-report>
ITERATION: 8
PHASE: resolve
COMPLETED: <what you fixed this round>
FILES_CHANGED: <comma-separated paths>
TESTS: <pass/fail/N/A>
NEXT: re-run spear loop
BLOCKERS: None
PROGRESS: <fixed>/16
</spear-report>
```

When the rubric is satisfied, add `<spear-complete/>` on its own line above the report block to stop the loop.

## Report — a2h-c2b.4, screen traits

**Status:** converged under the owner's bounded grading criterion (decided 2026-09-14). Independent
round-7 grader verdict: CONVERGED (evidence 48). Gate 3 pending: human ACCEPT/REJECT.

SPEAR round 8 (the `assess` that wrote this file) is an adapter-only pass: the same 16 vendored
`tools/spear-cli` hits as every round, listed in ASSESS "Known acceptable"; 0 attributable to this cycle.

### What changed
- `ScreenTraits.kt` (new): sealed `Trait` with `HasList(containerResourceId, minRows,
  rowChildResourceIds)`, `HasControl(element)` and `LacksControl(element)`; `TraitEvaluationContext.of(root,
  name?)` built once per observed tree; `TraitEvaluator.verdict` (HOLDS / DOES_NOT_HOLD / UNSETTLED) and
  `TraitEvaluator.matchKnownScreens` (None / One / Ambiguous, each with the sorted unsettled list).
- `ScreenIdentity.kt`: `traits: List<Trait> = emptyList()` and a derived `identifyingElements` (scope
  call C). No other file under `app/src/main` changed (scope call A: not wired into any crawl decision).
- `ScreenTraitsTest.kt` (new): 50 pins over synthetic trees, including characterisation of the real
  scroll merge.

### Decisions taken during the cycle
- Gate 1: scope calls A–E as recommended. Gate 2: plan approved.
- Assess loop, **pending owner confirmation at Gate 3** (SCOPE amendment block): an identity of negations
  alone is UNSETTLED; a missing package never matches; an unnamed known screen can match a named
  observed one; "walked once" means once per tree, never per candidate.
- 2026-09-14: the owner chose a bounded grading criterion from round 5 onward.

### Grading history
- Rounds 1–4 (unbounded, ratcheting): 8, 9, 12 and 10 defects — every one a missing pin or a wording
  error, never a wrong answer. All fixed; each fix proven RED-first or by a killed mutant.
- Round 5 (bounded: 156 archived + 112 operator mutants): 4 Low. Round 6: 1 Low. Round 7: CONVERGED.
- Evidence (`.spear/a2h-c2b-4/evidence/`): 00–01 baseline, 297 tests · 12, 19 pre-fix RED runs · author
  mutation runs 03–06, 13, 21, 27, 33, 39, every mutant killed · grader reports 11, 18, 25, 31, 37, 43,
  48 · final state 44–47: ScreenTraitsTest 50/50, full suite 347/347, assembleDebug green, 0 warnings,
  comment-only diff since round 6, tracked diff = `ScreenIdentity.kt` +17 lines plus the two new files.

### Follow-ups filed
- a2h-c2b.10 normalize trait order in identity equality before settled identities are persisted
- a2h-c2b.11 the scroll merge reshapes lists (collapses identical rows, may copy a wrapped list)
- a2h-c2b.12 trait follow-ups surfaced by the bounded round

<spear-complete/>

<spear-report>
ITERATION: 8
PHASE: resolve
COMPLETED: Added a sealed Trait layer to ScreenIdentity (HasList, HasControl, LacksControl) evaluated against the live accessibility tree, with a tri-state verdict and a zero/one/ambiguous match over every known screen; seven rounds of independent adversarial grading (four unbounded, three bounded), every finding fixed and mutation-proven.
FILES_CHANGED: app/src/main/java/com/example/apptohtml/crawler/ScreenTraits.kt (new), app/src/main/java/com/example/apptohtml/crawler/ScreenIdentity.kt, app/src/test/java/com/example/apptohtml/crawler/ScreenTraitsTest.kt (new)
TESTS: pass (347 tests, 0 failures, 0 errors); assembleDebug BUILD SUCCESSFUL; 0 compiler warnings in cycle files
NEXT: Gate 3 - human ACCEPT/REJECT
BLOCKERS: None for the code.
PROGRESS: 16/16 adapter hits are vendored tools/spear-cli noise (Known acceptable); 0 attributable to this cycle
</spear-report>