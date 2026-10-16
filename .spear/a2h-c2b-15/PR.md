# A Stop part-way through a screen keeps the targets it hadn't reached, and the resume visits them

Bead `a2h-c2b.15` · epic `a2h-c2b` · targets `epic/structured-screen-identity` (not `main`).
Unblocks `a2h-c2b.13` (settle and resume).

## Summary

Since `a2h-c2b.3`, every identity case pauses the crawl. The normal operator path is to Stop, settle
the case offline, and resume. That resume was lossy. A screen's targets got their edges only when the
crawl reached them, so a Stop at target *k* saved only targets 0..k. The resume found nothing pending,
marked the screen complete, and every target after *k* was silently never visited. A Stop at the
failed-edge or elapsed-time checkpoint, or a partial abort after a failed recovery, lost targets the
same way.

With this change:

- **The whole plan is saved before the first visit.** When a screen starts expanding, every eligible
  target is recorded as a `pending` edge, after the blacklisted ones and in plan order, before the
  screen is first saved `in_progress`. A Stop at any point leaves the remaining targets on disk, and the
  resume picker's pending count shows them.
- **A resume visits them in plan order.** A stopped-and-resumed crawl therefore numbers its child
  screens the same way an uninterrupted crawl would.
- **A resume re-checks the blacklist in force now.** If a rebuild between the Stop and the resume
  forbids a saved target, that target is recorded `skipped_blacklist` with the reason, and it is never
  clicked. This is scope call E, which the owner added after the round-1 grader found the gap.

A crawl that runs to completion produces byte-identical output, with the same edge ids in the same
order. A characterization test pins this. It is green on the old code and on the new.

## What changed

- `DeepCrawlCoordinator.expandScreen`: eligible edges are minted up front, `EdgeWorkItem.edgeId` is
  no longer nullable, and resume work items are sorted by edge sequence.
- `DeepCrawlCoordinator.resumeEdgeWorkItems`: each pending target's live element is re-checked
  against the current blacklist.
- `DeepCrawlCoordinatorTest`: six new tests.
  - Resume after a Stop on an identity case (the grader's probe P1).
  - Resume after a Stop at the failed-edge checkpoint.
  - Plan order and child-screen numbering on a resume.
  - A target the current blacklist now forbids is skipped on resume.
  - Edge-id characterization for a completed crawl.
  - Duplicate edges, checked at manifest level inside the P1 test.
- `.claude/commands/spear-start.md`: the Gate 3 flywheel change. Every gate is now briefed with a
  summary and links to its reading material before the approval buttons.

## Evidence

The evidence is under `.spear/a2h-c2b-15/evidence/`. It is gitignored, so it is local to the author's
checkout.

- **Red→green:** both resume tests fail on `abf625f` and pass on the fix (`01-red`, `02-green`). The
  scope call E test fails on the pre-amendment code (`09-scope-e`). The plan-order test fails without
  the sort (`08-round-1-fixes`).
- **Mutations:** nine mutations, each turning at least one new test red, re-run by the round-2 grader
  against the final code (`11-round-2-grading`). They include the pre-fix code, minting only the first
  target, a resume that ignores pending edges, a reversed order, no blacklist re-check, and duplicate
  minting.
- **Independent grading:** round 1 failed (D1–D4, all fixed). Round 2 passed.
- **Final state:** `./gradlew assembleDebug test --rerun-tasks` passes, with **545 tests, 0
  failures** and no new warnings.

## Test plan

- [x] `./gradlew test`: 545 tests, 0 failed.
- [x] `./gradlew assembleDebug`: green.
- [x] **Device (emulator, Settings).** The owner ran a fresh crawl, stopped on the first identity
  case, and resumed with Continue auto.
  - The root was saved `in_progress` with 8 `pending` edges, `edge_013`–`edge_020`.
  - The resume logged `pendingEdgeCount=8` and visited all eight: 6 captured, 1 skipped as an external
    package, and 1 failed because the target app was lost after the click, which is pre-existing.
  - The root then ended `complete`.
  - A second Stop showed the same on a non-root screen, Network & internet, with 5 pending.

## Rollback

Revert the commit. No data migration is involved. Crawls saved with this change load on the old code,
which treats the extra `pending` edges as ordinary pending work.

## Follow-ups (filed)

The three pre-existing issues found during grading are filed under the epic.

- `a2h-c2b.20` (P2): route replay on resume ignores the current blacklist.
- `a2h-c2b.21` (P3): a pending edge marked `failed` on resume is dropped from the parent XML.
- `a2h-c2b.22` (P3, unconfirmed): a ContinueAuto resume may queue only the first `in_progress`
  screen.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
