# ASSESS — Code (a2h-c2b.3: propose, validate, settle)

Kotlin/Android. Grading is **independent and ratcheting**: each round uses a fresh adversarial grader
that gathers its own evidence and attacks a dimension the previous round did not.

This unit of work carries three risks, ranked:

1. **A silent merge.** Two different screens become one graph node with no case. The epic exists to
   end this, and the WEAK-name amendment exists because traits alone cannot prevent it. Metrics 6–9.
2. **A settled identity silently replaced or bypassed.** A fresh proposal overwrites what was settled,
   or a settled screen gets linked by name alone. Metrics 10–11.
3. **A case that does not stop the crawl.** A non-holding identity proceeds without a pause, or
   aborts without one. Metrics 12–14.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exits 0, with no new warnings against the Step-0 baseline. |
| 2 | Full suite green | yes | `./gradlew test` exits 0. |
| 3 | No unexplained test loss | yes | Test count is at least the baseline, or every removed test is listed in the Step-9 catalogue with the behaviour it pinned and why that behaviour is gone. |
| 4 | Criterion → test map complete | yes | Every machine-checked SCOPE line maps to a named test that exists and runs. An unmapped line is a defect. |
| 5 | **Holding is decided by `TraitEvaluator` only** | yes | Settle, recognition and replay all trace to `TraitEvaluator.verdict` or `matchKnownScreens`. Grep the new code for `all {`, `containsAll`, `intersect` and `==` over fingerprints or traits outside the proposer, and justify every hit. The proposer generates and never judges. |
| 6 | **Ambiguous is never a link** | yes | Every `Ambiguous` path ends in a `UNIQUENESS_COLLISION` case with all ids listed. Mutation (2) is shown red. |
| 7 | **WEAK never links** | yes | A WEAK observed name never yields `Link`, through either `via`. Mutation (6) is shown red, and the WEAK lookalike red→green pair is archived. |
| 8 | **STRONG same-name lookalikes are separated** | yes | The red→green pair is archived. The pre-change run shows the merge; the post-change run shows two screens. |
| 9 | **Two-way check is real** | yes | Check 2(d) runs `matchKnownScreens` with roles reversed over the step-0 trees of known screens. Mutation (1) is shown red. An unreadable tree is logged and never counted as a non-collision. |
| 10 | **Name fallback is unsettled-only** | yes | The fallback reads `ScreenTraitMatch.unsettledScreenIds` and nothing else. Mutation (4) is shown red. |
| 11 | **Never replace a settled identity** | yes | No assignment of a proposal to a record that already has traits. A re-scan whose proposal differs leaves the record's identity byte-identical in memory, XML and manifest. |
| 12 | **Every case pauses, after its files exist** | yes | For all three kinds, `awaitIdentityCaseDecision` is called. At that moment the screen's XML exists, the case is in `identity-cases.xml`, and every involved screen's XML carries the pointer. |
| 13 | **Continue and Stop per scope call B** | yes | The table is pinned per kind. Mutation (5) is shown red. Stop gives `PARTIAL_ABORT`, with the case in the case file and pointers on every involved screen. |
| 14 | **Replay trait failure pauses** | yes | The red→green pair is archived: pre-change goes straight to recovery, post-change pauses first. |
| 15 | **Proposal rule** | yes | The cart pin, the id-less pin and the child-order pin pass. Mutation (3) is shown red. The real `network_internet` fixture proposes a `has-list` that holds on itself. |
| 16 | **Cases persist, with one home** | yes | The case file and the pointers both go write → load → rewrite byte-identical. Pointers are derived from the tracker's open cases and never stored separately. Closing a case (test-only `closeCase`) removes every pointer and keeps the case `closed` in the file. With no case there is no file and no pointer, and pre-existing XML pins stay byte-identical. There is no manifest copy. |
| 16a | **No case lost silently** | yes | Resume fails loudly, naming the file, on a malformed case file or a dangling pointer. The case file is never scanned as a screen. |
| 17 | **Name map gone** | yes | `dedupKeyToScreenId` is absent from `app/src/main`, and `DedupPolicy.keyFor` has no caller on the dedup path except scope call C's fallback. |
| 18 | **Pre-change crawls behave as today** | yes | An all-unsettled loaded crawl takes no identity-case pause and makes the same link decisions as the name rule. |
| 19 | **Determinism** | yes | Reordered known screens and reordered child nodes give identical proposals, decisions, cases, link targets and bytes. |
| 20 | **Order-free equality (G)** | yes | The red→green pair is archived for `[A,B] == [B,A]` and `[A] == [A,A]`, with matching hash codes. |
| 21 | **Pause reaches the operator** | yes | `CrawlerSession` exposes the case in UI state. Continue and Stop resolve the pending decision. Stale decisions are ignored as today. |
| 22 | Pre-existing test changes justified | no | Every modified pre-existing test appears in the Step-9 catalogue, found by the behaviour it asserts, with a scope reference. |
| 23 | Docs by reference | no | `crawler-module.md` and `data-and-state.md` point to the code. Neither restates thresholds or rules. |
| 24 | No leftover debug | yes | No `println`, no commented-out code, no added `TODO`, and no absolute paths in shipped code. Every new public type and function has a KDoc contract. |
| 25 | Evidence at final state | yes | The headline checks are re-run after the last artifact edit. Earlier green evidence is void after any edit. |

## Lettered failure modes

**Merges and identity integrity (highest severity):**

A. **Silent merge.** Any path that links on `Ambiguous`, links a WEAK observed name, or links a
   settled screen by name.
B. **First-match collapse.** `Ambiguous` narrowed to one id anywhere: in a log, the manifest, a case,
   or the UI.
C. **One-way uniqueness disguised as two-way.** Check 2(d) skipped when trees are missing, or run
   against the merged tree instead of step 0.
D. **Settled identity overwritten.** A re-scan, a resume or a Continue writes a new proposal over
   existing traits.
E. **Second predicate.** Holding re-derived outside `TraitEvaluator`, or "rows of a container"
   re-walked outside the context index.

**Cases and pauses:**

F. **Case without a pause.** A non-holding identity proceeds with only a log line.
G. **Pause before evidence.** The case is raised before the screen's files are written, so the
   operator has nothing to look at.
H. **Continue semantics drift.** Continue on a collision links, or Continue on a proposal failure
   drops the screen.
I. **Case lost, or its pointers wrong.** A pointer is missing on an involved screen, a stale pointer
   outlives its case, the case file is not rewritten when a case opens, or the loader does not restore
   it, so Stop and resume erase it. Pointers are stored rather than derived from the open cases.
J. **Uninformative case.** The detail does not name the failing traits or the colliding screens, which
   repeats `a2h-c2b.6`'s `Expected X but found X`.

**Proposal:**

K. **Volatile material in a proposal.** `has-control` from an id-less pressable, a back affordance or
   a list row, or `minRows` copied from the observed row count.
L. **Non-deterministic proposal.** Trait order depends on tree order or hash iteration.

**Process:**

M. **Pin never red.** A red→green criterion with no archived red run.
N. **Test bent to fit.** A pre-existing test edited to pass without a catalogue entry naming the
   behaviour change and its scope call.
O. **Off-spec deviation.** The implementation diverges from SCOPE, including its Gate 2 amendment,
   with no note.

## Known acceptable

- The whole-repo adapter reports pre-existing hits in `tools/spear-cli/**` and in untracked working
  files (`.codex-temp/`, `.obsidian/`, `tmp/`, `thoughts/`). These belong to other work and stay
  untouched. Register the stop with `--allow-fast-convergence` if they keep counts non-zero.

## Convergence

PASS when metrics 1–25 are all satisfied, no lettered failure mode is open, the final-state build and
suite are green, and a grader who did not write the code agrees, each round stricter than the last.
