# PLAN

Bead: `a2h-c2b.15`. Branch `fix/stop-keeps-unvisited-edges`, cut from
`epic/structured-screen-identity` at `abf625f`. The PR targets the epic branch.

- [x] User confirmed

## Load-bearing facts

These were verified against the source at `abf625f`. The plan depends on each of them.

1. **Eligible edges are minted lazily, inside the loop.**
   `workItem.edgeId ?: tracker.addPendingEdge(…)` in `DeepCrawlCoordinator.expandScreen`. On a fresh
   expansion every `EdgeWorkItem` has `edgeId = null`.
2. **Blacklisted edges are minted first, then eligible ones.** The skipped edges are added before the
   first `rewriteScreenXmlFor` and `saveManifest` that follow `setScreenExpansionStatus(IN_PROGRESS)`.
   Eligible edges are minted in plan order as the loop reaches them. During one screen's expansion,
   only that screen's edges are minted, because children are expanded later from the frontier. So
   minting all eligible edges right after the skipped ones gives **the same ids in the same order**
   (scope call C).
3. **Resume already does the right thing with recorded edges.** `resumeEdgeWorkItems` replays every
   PENDING or IN_PROGRESS edge, matched to the live rescan by `matchesElement`. An edge whose element
   is gone is marked FAILED and logged `resume_edge_missing`. `SavedCrawlLoader` loads every edge the
   screen XML records, and turns FAILED back into PENDING. So the fix needs no change on the resume
   side.
4. **Every mid-expansion Stop goes through `abortPartialCapture`.** This covers the identity case, the
   checkpoint pause and a failed recovery. It saves the manifest as `PARTIAL_ABORT` and throws. The
   parent's XML was last written `in_progress` before the edge being visited. Pending edges minted
   before the first save are therefore on disk.
5. **The pending count already flows to the picker.** `SavedCrawlRepository.snapshot` counts
   PENDING and IN_PROGRESS edges per parent.

## Steps

1. **Red tests first, on pre-fix code.** These are added to `DeepCrawlCoordinatorTest`, through
   `FakeHost`.
   - `resume_afterStopOnAnIdentityCaseMidExpansion_visitsTheUnvisitedSiblings`. This is probe P1:
     Home → {About (id-less, so its case pauses), Orders, Profile}. The first run is stopped on the
     case. The test checks the root XML and the `SavedCrawlRepository` pending count, then resumes
     with `ContinueAuto`. It asserts that the resume is `Completed`, that 4 screens are captured,
     that "Open C" and "Open D" are clicked in the resume, that each of the root's three targets has
     exactly one edge, and that the edge ids from before the Stop are kept.
   - `resume_afterStopAtTheFailedEdgeCheckpointMidExpansion_visitsTheUnvisitedSiblings`. The root
     has {Broken (click fails), Open B, Open C}. The failed-edge threshold is 1, and Stop is chosen
     at the checkpoint. The resume host lets Broken fail again and keeps the threshold high. The test
     asserts that B and C are captured in the resume.
   - `edgeIds_forACompletedCrawl_followTheBlacklistedThenPlanOrder`. This is the characterization
     test. It uses a root with a blacklisted editable plus three eligible targets, and runs to
     completion. It asserts the exact `(edgeId, label, status)` sequence from the manifest. It must
     be **green on pre-fix code**.
   - The blacklist-on-resume assertion is folded into the P1 test. Its root also carries an editable
     target. That target is `skipped_blacklist` before the Stop, and it is never clicked in either
     run.

   Run the tests on pre-fix code. The two resume tests must be red and the characterization test
   green. Archive the run as `evidence/01-red/`.
2. **The fix.** In `expandScreen`'s fresh-expansion branch, build the `EdgeWorkItem`s by minting each
   eligible element's PENDING edge right after the skipped edges. This happens before the existing
   `rewriteScreenXmlFor` and `saveManifest`. `EdgeWorkItem.edgeId` becomes non-null, and the loop's
   lazy `?: addPendingEdge` goes. A one-line comment states the contract: the whole plan is on disk
   before the first visit, so a Stop at any edge leaves the rest resumable.
3. **Green.** Run the new tests and the full `./gradlew test`, and archive both as `evidence/02-green/`.
   Any pre-existing test that turns red is catalogued by the behaviour it asserts
   (`evidence/03-fallout/`) and changed only if that behaviour is the defect.
4. **Mutations.** Apply each of these, run, archive as `evidence/04-mutations/`, and revert.
   (1) Revert to lazy minting. (2) Mint only the first eligible edge up front. (3) Make
   `resumeEdgeWorkItems` ignore PENDING. (4) Mint the eligible edges in reverse order, which must turn
   the characterization test red.
5. **Final state.** Run `./gradlew test` and `./gradlew assembleDebug` at the final artifact, and
   archive as `evidence/05-final-state/`.
6. **Assess loop.** Each round uses a fresh adversarial grader subagent.
7. **Gate 3 device run.** A Settings crawl with a Stop on a case mid-expansion, then a resume. Pull
   it, and record the XML and the `traversal_resume_plan pendingEdgeCount` under `evidence/06-device/`.

## Amendments during the Assess loop

- **Round 1, D1 → scope call E (owner decision, 2026-10-16).** `resumeEdgeWorkItems` re-checks each
  pending target's live element against the current blacklist. A target the blacklist now forbids
  is recorded `skipped_blacklist` with the reason and never clicked. Pinned red→green in
  `evidence/09-scope-e/`.
- **Round 1, D2.** A resume visits its kept targets in edge-sequence order, which is plan order. A
  stopped-and-resumed crawl therefore numbers its child screens like an uninterrupted crawl. Pinned
  red→green in `evidence/08-round-1-fixes/`.
- **Round 1, D3 and D4.** The P1 test also checks edge ids in the manifest, which catches
  duplicates. On the resume path, the save stays before `resumeEdgeWorkItems`, as before the fix.

## Risks

- **A test that pins a mid-crawl XML or manifest with no `pending` edges.** Fact 2 says completed
  output is unchanged, but a test that inspects a partial abort may now see extra `pending` edges.
  If one turns red, it is catalogued in Step 3.
- **An eligible element that is equal to another under `matchesElement`.** Two pending edges could
  resolve to the same live element on resume. That is the same today for recorded IN_PROGRESS
  edges, and is not changed here.
