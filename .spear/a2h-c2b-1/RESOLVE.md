# RESOLVE — Round 2

Convergence: 16 defects open
Timestamp: 2026-09-08T09:42:47.075Z
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

---

## Author report — round 2 (final)

### Adapter defect count: 16 open, 0 attributable to this cycle

All 16 belong to the pre-registered "Known acceptable" set in `ASSESS.md`:

- **15** are `console.log` / `any` findings in `tools/spear-cli/**` — vendored, read-only
  third-party source per `factory/FUNCTION.md`. Editing them to force a zero count is explicitly
  forbidden.
- **1** (`all changed files / rubric`) is the adapter's placeholder telling the author to grade the
  rubric by judgment. That grading was done — twice, independently, by adversarial graders that did
  not write the code (see below).

Zero defects land in `app/src`. Registered with `--allow-fast-convergence` for exactly this reason.

### Independent grading (never self-graded, ratcheting)

**Round 1 grader** (fresh subagent, own evidence) returned DEFECTS-OPEN with three findings, all
accepted and fixed:

1. `expectedChildPackageName` left as a constant-`null` parameter chain after the post-approval
   re-open was deleted (failure mode A). Removed from `openChildFromScreen` and
   `captureChildDestinationAfterClick`; kept on the shared log helpers, because the route-replay
   path still passes a real value.
2. The guard compared `childPackageName` against `screenRecord.packageName` rather than
   `selectedApp.packageName`. Fixed. The guard now references the same anchor as the recovery probe
   in `restoreToEntryScreenOrRelaunch`; before the fix, the two disagreed.
3. A half-updated sentence in `documentation/crawler-module.md` still listing "captured, linked, and
   already-allowed" as possible cross-package outcomes. Rewritten.

**Round 2 grader** (a second fresh subagent, told to attack what round 1 did not and to verify
round 1's fixes) confirmed all four edits correct and found one further real defect, fixed:

4. `CrawlGraphHtmlRenderer.kt:623` still described the skip to the operator as *"User chose to stay
   inside the selected app."* — a claim about a choice that no longer exists, embedded in every
   generated `crawl-graph.html`. Rewritten, and the two graph test fixtures were aligned with the
   message production can now actually emit.

### A pin that was withdrawn rather than shipped

Round 1's defect 2 came with a suggested regression pin: resume a saved crawl seeded with a
foreign-package screen record. I built it. It passed against the **unfixed** gate — so it was
vacuous. Instrumenting it showed why: replay reaches the foreign screen, but the hand-written
fingerprint in the fixture fails `replay_validation`, so the edge never reaches the guard at all.

A pin that passes for the wrong reason is failure mode **B** in this cycle's own rubric, so it was
removed along with the test-helper parameterisation it needed. The fix ships **unpinned**, and that
is recorded here rather than papered over. It is unpinnable by construction: for any fresh crawl
`screenRecord.packageName == selectedApp.packageName` always holds, so no fresh-crawl test can
distinguish the two expressions; only a legacy saved crawl can, and `CLAUDE.md` waives
backward compatibility for saved crawls. The change is still correct and strictly safer — it makes
the code state the invariant the SCOPE actually asserts.

### Evidence, all gathered at the final artifact state

| Artifact | Result |
|---|---|
| `evidence/00-baseline-test.txt` | 289 tests, 0 failures (pre-change) |
| `evidence/01-red-new-pins.txt` | 3 new pins **RED** on pre-fix code, with extracted `AssertionError` messages |
| `evidence/02-green-new-pins.txt` | suite green after the change |
| `evidence/03-mutation-guard.txt` | guard neutralised → **4 pins RED**; restored → green |
| `evidence/04-final-test.txt` | **263 tests, 0 failures, 0 errors** |
| `evidence/05-final-assemble.txt` | `assembleDebug` BUILD SUCCESSFUL |

Deletion grep across `app/src`: `EXTERNAL_PACKAGE_BOUNDARY`, `SKIP_EDGE`,
`ExternalPackageDecisionContext`, `DestinationCompatibilityReason`, `DestinationCompatibilityResult`,
`CrawlEdgeApproval`, `allowedPackages`, `AllowedPackage`, `revokeApproval`, `.compatibility(` — **all
zero**. Production diff **+33 / −595 (net −562)**.

### Two things the human must rule on at Gate 3

- **Metric 8 (`≤900 LOC diff`) is exceeded** on a literal reading: `app/src` + `documentation` churn
  is ~2304 changed lines. Both graders flagged it rather than reinterpreting a ratified number. The
  overrun is entirely deleted test code that `PLAN.md` Step 4 enumerated by name and that `SCOPE.md`
  anticipated ("predominantly deletion — the approval path plus the compatibility matcher **plus
  their tests**"). Metric 7 (net production LOC down) passes at −562. Needs ratification or a
  recorded deviation — not a code change.
- **The behavior change is user-visible and has had no device check.** Everything above is unit-level.

### Plan fidelity — two deviations, both recorded

- `PLAN.md` Step 1 predicted pin 3 could not be RED naturally. It **was** RED: the fixture grants
  `CONTINUE`, so pre-fix code genuinely captured the foreign screen. Stronger than planned. The
  Step 5 mutation was run anyway, since `ASSESS.md` metric 5 names the artifact.
- `PLAN.md` Step 4's deletion list missed `bfsTraversal_allows_cross_package_child_screens` — its
  name carries no "external". Caught by the suite, not by the plan; deleted with a stated rationale
  (it asserts a foreign screen *is* captured). Root cause and proposed guardrail go to the Gate 3
  flywheel item.

<spear-complete/>

<spear-report>
ITERATION: 2
PHASE: resolve
COMPLETED: Removed the external-package approval pause, the allow-list, the post-approval re-open and the destination-compatibility matcher; a click leaving the target app is now recorded SKIPPED_EXTERNAL_PACKAGE automatically. Fixed all 4 defects raised by two independent adversarial graders.
FILES_CHANGED: app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt, app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt, app/src/main/java/com/example/apptohtml/crawler/PauseCheckpointConfig.kt, app/src/main/java/com/example/apptohtml/crawler/PauseCheckpointTracker.kt, app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt, app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt, app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt, app/src/main/java/com/example/apptohtml/crawler/CrawlGraphHtmlRenderer.kt, app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt, app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt, app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt, app/src/main/java/com/example/apptohtml/storage/SavedCrawlRepository.kt, app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt, app/src/main/java/com/example/apptohtml/MainActivity.kt, app/src/test (7 files), documentation/crawler-module.md, documentation/modules.md
TESTS: pass (263 tests, 0 failures, 0 errors); assembleDebug BUILD SUCCESSFUL
NEXT: Gate 3 - human ACCEPT/REJECT, device check, metric 8 ruling
BLOCKERS: None for the code. Two items need a human ruling: metric 8 diff-size cap, and a real-device check of the user-visible behavior change.
PROGRESS: 16/16 (0 attributable to this cycle; 15 vendored tools/spear-cli noise, 1 rubric placeholder now graded twice independently)
</spear-report>
