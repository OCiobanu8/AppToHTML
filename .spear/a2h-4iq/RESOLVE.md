# RESOLVE — Round 2

Convergence: 16 defects open
Timestamp: 2026-08-07T15:25:58.066Z
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

## Report

### Adjudication of the 16 reported defects

**All 16 fall outside this unit of work.** Every one is in `tools\spear-cli\**`, the vendored
SPEAR CLI's own TypeScript source — 1 `any`-type, 14 `console.log`, and 1 subjective LLM finding
against the same files. Breakdown by root, from the round-2 defect list:

| Root | Defects |
|---|---|
| `tools\spear-cli\**` (vendored, read-only) | 16 |
| `app/src/**` (this cycle's Kotlin) | 0 |
| `.claude/skills/capture-screen/**` | 0 |
| `documentation/**` | 0 |

This is the case `ASSESS.md` → "Known acceptable (whole-repo adapter noise)" was written for: the
`code` adapter scans the entire repository, and `factory/FUNCTION.md` makes vendored third-party
source read-only — it is updated by re-vendoring a pinned upstream commit, never by hand-editing.
A `console.log` in a CLI is also not a defect at all; it is how a CLI writes to stdout. Editing
those files to force a zero count would be corrupting a dependency to satisfy a metric.

Registered with `--allow-fast-convergence` rather than by editing vendored code.

### Verification at the final artifact state

Re-run **after** the last edit (the documentation updates), not carried forward from an earlier
round:

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest` | **288 tests, 0 failures, 0 errors** (baseline 220 → +68) |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** |
| New compiler warnings vs. baseline | **0** |
| `capture-screen.ps1` parse check | **0 parse errors** |
| Pre-existing tests modified | **none** |

Evidence: `.spear/a2h-4iq/evidence/00-baseline/`, `01-step1/`, `02-final/`.

### Refactor-fidelity probes (the part a green suite cannot prove)

Phase 1 moved ~180 lines that had **zero** prior coverage, so "the existing suite is still green"
proves nothing about them. Two mutations were injected into the extracted code and the new
characterization tests were confirmed to catch both:

| Mutation | Result |
|---|---|
| `preferredActionIds` filters unsupported preferred ids instead of appending them | **6 tests RED** |
| path candidates attempted in natural order instead of `asReversed()` | **2 tests RED** |

Both probes reverted; absence re-verified by grep before the final run.

### Deviations from PLAN

1. **`LiveActionIds` injected.** Not in the plan. `AccessibilityAction.ACTION_PAGE_DOWN.id` and
   friends resolve through framework statics that throw under the JVM unit-test runtime, so
   `LiveNodeActions` could not have been unit-tested with the ids read inline — and adding
   `unitTests.isReturnDefaultValues` was ruled out by SCOPE forbidding build-file changes.
2. **`liveNodeActions` is `by lazy`.** Same API-29 field: an eager field or companion constant
   would move a possible `NoSuchFieldError` on an API 24–28 device from "first scroll attempt"
   (where it lives today) forward to service construction — a regression the refactor must not
   introduce.
3. **`scroll = false` runs `scan` with refusing scroll lambdas** instead of bypassing `scan` and
   hand-building a `ScreenSnapshot`. Same observable contract (one step, zero gestures — pinned by
   test) while reusing the tested merge/naming path rather than duplicating it.
4. **Rejection markers get an `_unknown` package bucket.** Found by a failing test of my own: a
   `crawl_in_progress` rejection happens before any foreground package is known, so the original
   design wrote no marker anywhere and the host script would have degraded from a one-line
   diagnosis into a full 45 s timeout.
5. **The structural safety guard pins the exact `Host` member set** rather than scanning for banned
   substrings. The substring version produced a false positive on the legitimate `scrollBackward`;
   the exact set additionally fails when anyone *adds* a capability, which is the case that would
   actually erode the guarantee.
6. **`SnapshotFileStore` takes an `ArtifactWriter` seam** so the `.done`-written-last property is
   asserted on real write order. An mtime comparison would pass vacuously — a sub-millisecond
   write burst gives every file the same timestamp.

### Limitations — not claimed as passed

- **Grading was not independent.** The skill requires a fresh adversarial grader each round; the
  session's tool rules bar spawning subagents unqualified, and the user chose to proceed rather
  than authorize one. The mechanical half above is reproducible from the archived commands, but
  the judgment half is self-assessed. Recorded here rather than papered over.
- **All six device criteria remain unverified.** They need a live emulator: foreground retention,
  the disabled-service fast failure, three real screen captures, a snapshot-vs-crawl XML diff,
  two non-colliding captures, and a post-refactor full crawl. These are Gate 3 items for a human
  and the loop has no authority to mark them passed.

<spear-complete/>

```
<spear-report>
ITERATION: 2
PHASE: resolve
COMPLETED: All 16 reported defects adjudicated as out-of-scope vendored-CLI noise (tools\spear-cli\**), per the ASSESS.md "Known acceptable" section; zero defects in app/src, .claude, or documentation. Implementation complete across all 7 plan steps.
FILES_CHANGED: app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt, app/src/main/java/com/example/apptohtml/MainActivity.kt, app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt, app/src/main/java/com/example/apptohtml/crawler/LiveNodeActions.kt, app/src/main/java/com/example/apptohtml/crawler/SnapshotFileStore.kt, app/src/main/java/com/example/apptohtml/crawler/SnapshotCrawlState.kt, app/src/main/java/com/example/apptohtml/crawler/SnapshotCaptureCoordinator.kt, app/src/main/java/com/example/apptohtml/crawler/SnapshotModels.kt, app/src/test/java/com/example/apptohtml/crawler/LiveNodeActionsTest.kt, app/src/test/java/com/example/apptohtml/crawler/SnapshotFileStoreTest.kt, app/src/test/java/com/example/apptohtml/crawler/SnapshotCaptureCoordinatorTest.kt, app/src/test/java/com/example/apptohtml/crawler/SnapshotRequestParserTest.kt, .claude/skills/capture-screen/SKILL.md, .claude/skills/capture-screen/scripts/capture-screen.ps1, documentation/modules.md, documentation/data-and-state.md
TESTS: pass (288 tests, 0 failures, 0 errors; assembleDebug green; re-run at final artifact state)
NEXT: Gate 3 — human ACCEPT/REJECT with device verification
BLOCKERS: None for the machine-checked half. Six device criteria require an emulator and are unverified.
PROGRESS: 16/16 adjudicated out-of-scope, 0 in-scope defects
</spear-report>
```
