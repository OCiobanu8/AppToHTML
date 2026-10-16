# ASSESS: Code (a2h-c2b.15)

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Builds | yes | `./gradlew assembleDebug` is green, with no new warnings in touched files. |
| 2 | Tests pass | yes | `./gradlew test` is green at the final artifact state, re-run after the last edit. |
| 3 | Red→green archived | yes | Both resume tests fail on pre-fix code and pass on the fix. The scope call E test (`resume_skipsAPendingTargetTheCurrentBlacklistNowForbids`) fails on the pre-amendment code and passes after. The round-1 D2 test fails without the resume sort. All runs are under `evidence/`. |
| 4 | Pins bite | yes | Each mutation turns at least one new test red. They are mutations 1–4 from PLAN Step 4, plus: no blacklist re-check on resume, a skip recorded but the target still clicked, no resume sort, and duplicate minting on resume. The authoritative runs are the grader's, against the final artifact (`evidence/11-round-2-grading/`). |
| 5 | Characterization holds | yes | The edge-order test is green on pre-fix code and on the fix (scope call C). |
| 6 | No duplicate edges | yes | After the resume, every root target has exactly one edge, the ids from before the Stop are kept, and no id is minted twice. |
| 7 | Pending is visible | yes | The stopped root XML lists each unvisited eligible target as `pending`, and `SavedCrawlRepository` reports the count. |
| 8 | Safety unchanged | yes | Blacklisted targets are recorded `skipped_blacklist` and never clicked, in the first run and in the resume. There is no new click, back or relaunch path in the diff. |
| 9 | Scope fidelity | no | Only `expandScreen` (edge minting and the resume order) and `resumeEdgeWorkItems` (the blacklist re-check, scope call E) changed. The tracker, the XML I/O, the loader and the planner are untouched (SCOPE "Not modified"). |
| 10 | Hygiene | yes | No commented-out code, no added `TODO`, and no debug output. The contract comment is present. |

## Lettered failure modes

A. **Fixed only the identity-case path.** A Stop at a checkpoint, or a failed recovery, still loses
   siblings.
B. **Never-red pin.** A "red→green" test that was not actually red on pre-fix code.
C. **Duplicate or reshuffled edge ids.** A completed crawl's ids or order drift from pre-fix code, or
   the resume mints a second edge for an already-recorded target.
D. **Partial save window.** A save point exists after the screen goes `in_progress` but before the
   whole plan is minted.
E. **Blacklisted target visited.** The up-front minting or the resume clicks a `skipped_blacklist`
   target.
F. **Stale green.** Evidence produced before the last edit to the artifact.
G. **Off-spec deviation.** A change outside SCOPE's affected surface, or a non-goal pulled in.
H. **Mocked framework.** A test that mocks Android classes instead of using `FakeHost` and synthetic
   trees.
I. **Silent drop on resume.** A pending edge whose element no longer matches the rescan is dropped
   without being recorded `failed` and logged.

## Known acceptable

- Whole-repo adapter noise under `tools/spear-cli/**`, `tmp/`, `.codex-temp/` and other in-flight,
  untracked work. None of it was touched by this cycle.

## Convergence

PASS when metrics 1–8 and 10 are fully met, metric 9 is judged met by an independent grader, no
failure mode is open, and the headline checks (metrics 1–2) have been re-run at the final state.
