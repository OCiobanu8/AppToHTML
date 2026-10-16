# SCOPE

Bead: `a2h-c2b.15`. *Stop mid-expansion loses unvisited sibling edges: resume never visits them*.
It is a P1 bug under epic `a2h-c2b`, and it blocks `a2h-c2b.13`.

Branch: `fix/stop-keeps-unvisited-edges`, cut from `epic/structured-screen-identity` at `abf625f`.
The PR targets the epic branch, not `main`.

## Goal

Bug fix: when a crawl is stopped while a screen is part-way through its targets and then resumed,
the resume **visits every target of that screen the first run had not reached yet**. Today the
resume silently drops them and reports the crawl complete.

## Audience

The operator. Since `a2h-c2b.3`, every identity case pauses the crawl. The pause screen tells the
operator to Stop, settle the case offline, and resume. That is now the **normal** path, and today
every such Stop loses the rest of the parent screen's targets with no warning. `a2h-c2b.13` (settle
and resume) builds on that resume and is blocked until it is lossless.

## The defect (from the `a2h-c2b.3` round-2 grader, D2)

Evidence: [`.spear/a2h-c2b-3/evidence/15-round-2-grading/GRADER.md`](../a2h-c2b-3/evidence/15-round-2-grading/GRADER.md),
D2 and probe P1.

- On a fresh expansion, `DeepCrawlCoordinator.expandScreen` records an eligible target's edge
  **only when it reaches that target** in the edge loop.
- A Stop at edge *k* saves the parent as `expansion-status="in_progress"` holding edges 0..k only.
- On resume, `resumeEdgeWorkItems` replays only the **recorded** PENDING or IN_PROGRESS edges. It
  finds none, so it marks the parent complete.
- Probe P1: Home has three children. Stop at the first child's case. The resume logs
  `pendingEdgeCount=0`, `no_pending_edges` and `crawl_complete capturedScreenCount=2`. "Open C" and
  "Open D" are never visited.

The same loss already happened before `a2h-c2b.3` on every other mid-expansion Stop or partial
abort. That covers a Stop at the failed-edge or elapsed-time checkpoint, and an edge failure whose
recovery fails.

## Scope calls for Gate 1. Please confirm or change each.

**A. The fix: record the whole plan before visiting any of it. (Recommended.)** When a screen's
expansion starts, every eligible target is recorded as a PENDING edge, after the blacklisted
targets and in plan order. This happens before the screen's XML and the manifest are first saved
`in_progress`. The edge loop then works through those recorded edges. A saved `in_progress` screen
therefore always holds its full plan on disk, and the existing resume path picks up whatever is
still pending, with no change to resume itself.

*Rejected alternative:* recomputing the traversal plan on resume and adding the missing edges. The
resume's rescan is a different capture of a live screen, so the "missing" set would depend on what
loaded this time. The saved plan is the record of what the first run committed to doing, and
recording it is the durable choice.

**B. Covers every mid-expansion stop, not just identity cases. (Recommended.)** These are a Stop on
an identity case, a Stop at the failed-edge or elapsed-time checkpoint, and a partial abort after a
failed recovery. All of them leave the parent `in_progress`, and the one change in A fixes all of
them. The pins cover the identity-case Stop and the failed-edge checkpoint Stop.

**C. A completed crawl's output is unchanged. (Recommended.)** Today edges get their ids as they
are visited: blacklisted targets first, then eligible targets in plan order. That is the same order
A records them in, so a crawl that runs to completion gives byte-identical XML, HTML and manifest.
The only visible change is mid-expansion. A saved `in_progress` screen, and the resume picker's
pending-edge count, now list the targets not yet visited as `pending`.

**E. A resume re-checks the current blacklist. (Added in round 1 by owner decision, 2026-10-16.)**
The round-1 grader found D1. A resume clicked every recorded pending target without asking the
blacklist in force now. If a rebuild between the Stop and the resume adds a blacklist entry, a
target it now forbids would still be clicked. Under A, that can be every unvisited target, where
before it was at most one. So the resume now checks each pending target's live element against the
current blacklist before visiting it. If the blacklist forbids it, the edge is recorded
`skipped_blacklist` with the reason, and it is never clicked. This touches `resumeEdgeWorkItems`,
which was listed as not modified before this amendment.

**D. No migration. (Decided by `CLAUDE.md`, which requires no backward compatibility.)** A crawl
saved before this fix with a part-expanded screen keeps its lost targets. `ReExpand` on that
screen recovers them, as it does today.

## Affected surface

- **Modified (main):** `DeepCrawlCoordinator.expandScreen`, which records eligible edges before the
  edge loop. Its edge work items then always carry an edge id.
- **Modified (main), scope call E:** `DeepCrawlCoordinator.resumeEdgeWorkItems`, which re-checks
  each pending target against the current blacklist.
- **Not modified, deliberately:** `CrawlRunTracker`, the screen XML writer
  and reader, `SavedCrawlLoader`, the manifest, the traversal planner and blacklist, and every
  identity-case path.
- **Breaking changes:** none for a completed crawl (C). A stopped crawl's `in_progress` screens now
  list their unvisited targets as `pending` edges.

## Inputs

- [x] Bead: `bd show a2h-c2b.15`
- [x] Grader finding: `a2h-c2b.3` round 2, D2 and probe P1 (linked above)
- [x] Governing values: `factory/COMPANY.md` ("correctness over speed", "safety over coverage") and
      `factory/FUNCTION.md` (red→green pins, deterministic transforms)

## Constraints

- **Safety unchanged.** No new click, back or relaunch path. Recording an edge is bookkeeping only.
  Blacklisted targets stay recorded as `skipped_blacklist` and are never visited.
- **Deterministic.** The same screen gives the same edge ids in the same order, whether or not the
  crawl was stopped and resumed.
- **Tests:** unit tier only, through the coordinator's existing `FakeHost` seam, with synthetic
  trees. No Android framework mocks.
- **Pre-existing tests stay unmodified.** A test that fails because of the fix is listed at Assess
  with the behaviour it asserts, and changed only if that behaviour is the defect.
- **Type-check:** `./gradlew assembleDebug` compiles with no new warnings.
- **PR size:** small. Expected well under 300 LOC including tests. Not a graded metric.

## Done means

### Machine-checked (graded in the Assess loop)

**Build**
- [ ] `./gradlew test` green at the final artifact state.
- [ ] `./gradlew assembleDebug` green at the final artifact state.

**The bug, pinned red→green**
- [ ] **Red→green (identity case):** a test reproduces probe P1. The root has three children, and
      a Stop happens on the first child's identity case. The resume then visits the other two, and
      the resumed crawl ends with all four screens captured. It **fails on pre-fix code**. Both runs
      are archived.
- [ ] **Red→green (checkpoint):** a test stops at the failed-edge checkpoint while the root still
      has unvisited targets. The resume visits them. It **fails on pre-fix code**. Both runs are
      archived.
- [ ] A test asserts that the stopped crawl's root XML is `in_progress` and lists **every**
      unvisited eligible target as a `pending` edge. It also asserts that the saved-crawl summary
      (`SavedCrawlRepository`) reports that root's pending-edge count, so the resume picker shows the
      remaining work.

**No duplicates, no drift**
- [ ] A test asserts that after the resume, each of the root's targets has **exactly one** edge,
      that the edge ids recorded before the Stop are kept, and that no new edge ids are minted for
      targets that were already recorded.
- [ ] **Characterization (C):** a test asserts the edge ids and their order for a crawl with both
      blacklisted and eligible targets that runs to completion. It **passes on pre-fix code** and on
      the fix. A mutation that records the eligible edges in a different order turns it red.
- [ ] Blacklisted targets are still recorded `skipped_blacklist` and are never clicked, on a fresh
      expansion and on a resume.
- [ ] **Red→green (E):** a test stops a crawl with targets still pending, then resumes it under a
      blacklist that now forbids one of them. That target is never clicked, and its edge is recorded
      `skipped_blacklist` with the reason. The other pending targets are still visited. It **fails on
      the pre-amendment code**. Both runs are archived.

**Pins bite (each mutation applied, shown red, archived, reverted)**
- [ ] (1) Remove the up-front recording, which is the pre-fix behaviour. (2) Record only the first
      eligible target up front. (3) Resume replays IN_PROGRESS edges only and ignores PENDING ones.

**Hygiene**
- [ ] No commented-out code, no `TODO` added, and no debug output. A changed function's KDoc, if it
      has one, states the "whole plan recorded before the first visit" contract.

### Human-verified at Gate 3 (real device; evidence under `.spear/a2h-c2b-15/evidence/`)

- [ ] A Settings crawl on the rebuilt app, stopped on an identity case while a screen still has
      unvisited targets. The saved XML shows them as `pending`. The resume visits them, and the crawl
      log shows `traversal_resume_plan … pendingEdgeCount=N` with N > 0 for that screen. Because that
      count is taken before the blacklist re-check (round-2 N3), the proof of the visit is the
      `edge_visit_start … resume=true` lines, and those edges ending `captured` or `linked_existing`.

## Non-goals

- `a2h-c2b.13` (settle and resume, closing cases on resume).
- Recovering targets lost by crawls saved before this fix (D).
- `a2h-c2b.18` (the pre-open top check rejecting a churning root).
- The stale known-screen tree cache (`a2h-c2b.3` round-2 D1). It is a separate defect and is not
  touched here.
- Any change to what is eligible, the blacklist, or the order targets are visited in.

`MAX_ROUNDS = 6`

Rounds are Assess/fix iterations. They are a circuit breaker, not a budget. If the same defect
survives two consecutive rounds, stop and escalate, however many rounds remain.
