# ASSESS — Code (a2h-4iq: single-screen capture via ADB broadcast)

Retargeted from the generic TS/Python template to Kotlin/Android + PowerShell. Grading is
**independent and ratcheting**: a fresh adversarial grader each round, gathering its own evidence,
attacking a dimension the previous round did not.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exit 0, no new warnings vs. baseline. |
| 2 | Full suite green | yes | `./gradlew test` exit 0. |
| 3 | No test count regression | yes | Test count ≥ Step-0 baseline. Catches a "fix" that deletes a test. |
| 4 | New behavior covered | yes | Every SCOPE "machine-checked" line maps to a named test that exists and runs. |
| 5 | Refactor fidelity | yes | `LiveNodeActionsTest` pins the two ordering tripwires (unsupported-preferred append; path-reversed vs. fallback-plain order). |
| 6 | XML structural parity | yes | Snapshot XML vs. crawl-root XML diff shows only the deliberately-differing fields. |
| 7 | `crawl/` untouched | yes | Test asserts a pre-existing sibling `crawl/` is byte-identical after a snapshot write. |
| 8 | Safety guarantees structural | no | Host interface has **no** launch / back member; fakes fail on unexpected calls. |
| 9 | Script parses | yes | `[ScriptBlock]::Create()` over `capture-screen.ps1` exit 0. |
| 10 | Docs updated | no | `modules.md` + `data-and-state.md` describe the snapshot path and `snapshots/`. |
| 11 | Diff within cap | yes | ≤2000 LOC per SCOPE. |
| 12 | No leftover debug | yes | No stray `println`, no commented-out code, no hard-coded absolute paths. |
| 13 | Evidence at final state | yes | Headline checks re-run **after** the last artifact edit. |

## Lettered failure modes

Generic ones dropped as inapplicable (`any` types, snapshot churn, migrations). These are the
failure modes this feature can actually exhibit:

A. **Vacuous ordering test** — a `.done`-is-last assertion via mtime comparison, which
   Windows timestamp granularity makes pass regardless of order.
B. **Framework mocking** — mocking `AccessibilityNodeInfo` instead of using synthetic nodes.
   Forbidden by `factory/FUNCTION.md`.
C. **Silent behavior change in the "pure" refactor** — extracted logic subtly reordered or
   "cleaned up"; the pre-existing suite cannot catch it because it never covered these lines.
D. **XML parity asserted by spot-check** — asserting `depth="0"` exists rather than diffing
   structure, so a serializer that dropped attributes still passes.
E. **Launch / back reachable on the snapshot path** — any call to `AppLaunchHelper`,
   `GLOBAL_ACTION_BACK`, `restoreToEntryScreenOrRelaunch`, or `returnToApp` from snapshot code.
F. **Snapshot leaks into crawl state** — snapshot writes inside `crawl/`, mutates `CrawlerPhase`,
   or claims a `crawl_`-prefixed session id.
G. **Stale live root** — a `rootInActiveWindow` cached across a scroll instead of re-read per
   lambda call.
H. **Half-written directory observable** — any ordering where `.done` can be seen before the
   artifacts are closed, or where both `.done` and `.failed` exist.
I. **Receiver leak** — registered in `onServiceConnected` but not unregistered in `onDestroy`.
J. **Unvalidated intent extras** — malformed/absent `token`/`scroll` crashing the service rather
   than defaulting.
K. **Coordinator bound to the `CrawlerSession` singleton** — making the tests order-dependent or
   requiring singleton mutation.
L. **Convergence on superseded evidence** — declaring green from a run predating the final edit.
M. **Device-only criterion graded as automated** — treating a Gate-3 human check as machine-passed.

## Known acceptable (whole-repo adapter noise)

The `code` adapter scans the whole repo and will surface pre-existing hits unrelated to this
cycle. These are **not** defects of this unit of work and must not be "fixed":

- `tools/spear-cli/**` — vendored third-party, read-only per `factory/FUNCTION.md`.
- `.beads/`, `.obsidian/`, `.codex-temp/`, `tmp/` — tracker state, editor config, scratch.
- `thoughts/shared/**` and `documentation/**` pre-existing files not touched by this cycle.
- Other in-flight uncommitted work present in the tree at cycle start (see Step-0 `git status`).

Register the stop with `--allow-fast-convergence` rather than editing any of the above.

## Convergence

PASS when: metrics 1–13 all pass, zero lettered failure modes open, and the headline checks
(`./gradlew test`, `./gradlew assembleDebug`) were re-run **at the final artifact state**.

Device-dependent criteria are explicitly **out of the loop's authority** — they are graded by a
human at Gate 3 against evidence in `.spear/a2h-4iq/evidence/`. The loop may not mark them passed.
