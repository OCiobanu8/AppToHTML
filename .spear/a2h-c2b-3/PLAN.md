# PLAN

Bead: `a2h-c2b.3` — *Propose, validate, settle*. Branch `feat/propose-and-settle-screen-identity` off
`epic/structured-screen-identity` at `0c99d87`; the PR targets the epic branch.

- [x] User confirmed

## Shape

The order is forced: **the crawler cannot decide anything until it has a proposal, a settle rule and
a recognition rule to call, and it cannot pause until a case has a shape.** So the pure decisions are
built and pinned first (Steps 1–4) with no crawl wiring. The persistence and pause plumbing comes next
(Steps 5–7). The coordinator is rewired last (Step 8), against seams that are already proven.

```
1  order-free trait equality (G)                        pure, pinned red→green
2  proposer: tree → traits (A)                          pure
3  settle: proposal + known screens → settled | why (2) pure, TraitEvaluator only
4  recognise: observed → link | new | collision (C, D)  pure, TraitEvaluator only
5  IdentityCase: case file + screen pointers (F)        persistence
6  known screens: identities + step-0 trees (D, E)      tracker/loader, name map deleted
7  pause plumbing: reason, host, session, UI (1)        UI path
8  coordinator wiring: root, child, replay (B)          the only crawl-behaviour step
9  pre-existing test fallout, catalogued by behaviour
10 red→green archives, mutations, docs, final state
```

## Load-bearing facts

These were verified against the source at `0c99d87`. The plan depends on each of them.

1. **The dedup site is one call.** At
   [DeepCrawlCoordinator.kt:594](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:594),
   `tracker.findScreenIdByIdentity(childFullIdentity)` resolves through `DedupPolicy.keyFor` and the
   `dedupKeyToScreenId` map
   ([CrawlRunTracker.kt:62](../../app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:62),
   [:164](../../app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:164)). The loader
   fills the same map
   ([SavedCrawlLoader.kt:105](../../app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:105)).
   `putIfAbsent` is why two screens with the same STRONG name merge today. The first one wins.
2. **The child's files are written before anything is linked.** `CaptureFileStore.saveScreen`
   ([:647](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:647)) runs
   only in the `else` branch, so for a new screen "write the files, then pause" needs no reordering.
   A collision case needs the observed child written as a new screen *before* the pause. That is also
   what Continue keeps (scope call B).
3. **Root capture is a separate path.** It is a straight-line capture
   ([:137–184](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:137)).
   On resume the root is re-scanned, but its **record keeps the persisted identity**. Only
   `entryScreenIdentity` is rebuilt live. That is the "never replace a settled identity" property,
   and it already holds there. Step 8 pins it rather than building it.
4. **Replay arrival already returns a typed trait failure.** `ReplayArrivalCheck.Verdict.TraitsDoNotHold`
   ([ReplayArrivalCheck.kt:24](../../app/src/main/java/com/example/apptohtml/crawler/ReplayArrivalCheck.kt:24))
   becomes `ScreenPreparationResult.Failure`
   ([DeepCrawlCoordinator.kt:897–916](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:897)).
   Today no production identity has traits, so this branch never runs. After Step 8 it runs for
   every settled screen, which is why it must pause rather than go straight to recovery.
5. **There is one pause channel, and it carries no detail.** `Host.awaitPauseDecision(reason, snapshot)`
   ([:2104](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:2104)) is
   implemented in
   [AppToHtmlAccessibilityService.kt:391](../../app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:391)
   → `CrawlerSession.pauseForDecision`
   ([CrawlerSession.kt:212](../../app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt:212)),
   and rendered in `MainActivity` (`pauseReasonText`, [MainActivity.kt:630](../../app/src/main/java/com/example/apptohtml/MainActivity.kt:630)).
   Nine test overrides implement the two-parameter signature, so changing it breaks all nine.
   **Decision:** add a separate `awaitIdentityCaseDecision(case, snapshot)` to `Host`. The existing
   method and its nine overrides stay untouched. One override is added to the shared `FakeHost`.
6. **"Rows of a container" already has one home.** `TraitEvaluationContext.indexContainers`
   ([ScreenTraits.kt:200](../../app/src/main/java/com/example/apptohtml/crawler/ScreenTraits.kt:200))
   builds container id → rows → ids beneath each row. The proposer needs exactly that, so it reads
   the context's index (exposed `internal`) instead of walking the tree a second way.
7. **`matchKnownScreens` already reports the unsettled screens.** `ScreenTraitMatch.unsettledScreenIds`
   is the list scope call C's fallback needs. No new "is this settled?" rule is written; the fallback
   reads that list.
8. **A known screen's step-0 tree can be read back.** `CaptureTreeReader.readFirstViewport(xmlFile)`
   ([CaptureTreeReader.kt:31](../../app/src/main/java/com/example/apptohtml/crawler/CaptureTreeReader.kt:31))
   exists and is round-trip pinned by `a2h-u68`. That is scope call E with no new reader.
9. **The test fakes give every element a resource id.** `fakeElement` builds
   `com.example.target:id/<label>`
   ([DeepCrawlCoordinatorTest.kt:2368](../../app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:2368)),
   so existing coordinator scenarios will propose `has-control` traits and settle. Expected
   consequence: most existing scenarios keep their outcomes. Where they don't, Step 9 is the
   catalogue.
10. **The fixture source exists.** `E:\Logs\com.android.settings\crawl-20260922_114748\screen_00001_network_internet.{xml,html}`
    is the `a2h-u68` final device crawl, so it carries an identity block. It has `recycler_view`
    rows carrying `android:id/title` and `android:id/summary` (16 of each).

## Steps

### Step 0 — Baseline

Archive `git status`, `./gradlew test` (with the test count) and `./gradlew assembleDebug` (with its
warnings) to `evidence/00-baseline/`.

### Step 1 — Order-free trait equality (G, folds `a2h-c2b.10`)

`ScreenIdentity` gets an explicit `equals`/`hashCode` that compares `traits` as a set, while every
other field is unchanged. `traits` stays a `List`, so the writer keeps its order and its bytes.
**Red→green:** a pin asserts that `[A,B] == [B,A]`, that `[A] == [A,A]`, and that equal hash codes
follow. Run it on pre-change code (red) and archive, then implement (green) and archive.

### Step 2 — The proposer (A)

New `TraitProposer.propose(root): List<Trait>`, a pure function.
- **Lists.** From the context's container index (fact 6): a container qualifies when at least
  `MIN_PROPOSED_ROWS = 2` of its rows carry a non-empty common id set. The schema is the
  intersection over those rows, and the trait is `HasList(id, minRows = 1, schema)`. If two containers
  share an id, the trait is proposed once.
- **Controls.** From `AccessibilityTreeSnapshotter.collectPressableElements`, deduplicated by
  `ElementFingerprint` exactly as `ScreenIdentity.fromRoot` does. Keep an element when it has a
  non-blank resource id, `BackAffordanceTokens` says it is not a back affordance, and its
  `childIndexPath` is not under a proposed list container's path.
- **Order.** Lists sorted by container id, then controls sorted by `encoded`.
- **Tests** (`TraitProposerTest`): the cart shape and its four exclusions; an all-id-less tree
  proposing nothing; child-order invariance giving byte-identical traits; a container with one
  qualifying row not being proposed; an empty intersection not being proposed.

### Step 3 — The settle decision (scope call 2)

New `IdentitySettlement.decide(proposal, ownContext, others)`. `others` is the known screens' ids,
identities and step-0 trees. It returns `Settled`, or `NotSettled(reason)` with the reason one of:
- `AssertsNothing`: `verdict == UNSETTLED`.
- `FailsOnOwnScreen(failing traits)`: `verdict == DOES_NOT_HOLD`.
- `AlsoHolds(screenIds, sorted)`: check 2(d). For each other screen, run
  `matchKnownScreens(contextOf(otherTree, otherName), mapOf(CANDIDATE to proposal))`; the screen
  collides when that answers `One`. This is roles reversed, so package and name take part exactly as
  in dedup.

Only `TraitEvaluator` decides. The settle step composes it and adds no predicate. Tests cover each
reason. The different-name and same-name pair proves the roles are reversed. `FailsOnOwnScreen` uses
an injected proposal, because the real proposer cannot produce one that fails on its own tree.

### Step 4 — Recognition (C, D, WEAK amendment)

New `ScreenRecognition.recognize(observedContext, observedName, known)`. `known` maps screen ids to
identities. It returns one of:
- `Link(screenId, via = TRAITS)`: `matchKnownScreens` gives `One`, and the observed name is STRONG.
- `Collision(screenIds)`: `matchKnownScreens` gives `Ambiguous`, **or** it gives `One` with a WEAK
  observed name (amendment).
- `Link(screenId, via = UNSETTLED_NAME)`: `None`, the observed name is STRONG, and the first id, in
  sorted order, of `unsettledScreenIds` whose `DedupPolicy.keyFor` equals the observed key (scope
  call C).
- `New`: anything else.

Only the unsettled list from fact 7 is ever name-matched, so a settled screen can never be linked by
name. Tests cover every branch, WEAK on `One`, WEAK on `None` with an unsettled same-name screen (a
WEAK name gets no fallback), and invariance to known-screen order.

### Step 5 — `IdentityCase` and where it lives (F)

This is option B, decided at Gate 2: one case file plus pointers on the screens.

- **Model.**
  - `IdentityCase(id, status, kind, raisedOn, relatedScreenIds, detail, rejectedProposal: List<Trait>)`.
  - `kind` is `PROPOSAL_DOES_NOT_HOLD`, `UNIQUENESS_COLLISION` or `SETTLED_DOES_NOT_HOLD`, and
    `status` is `OPEN` or `CLOSED`.
  - `id` is `case_%03d` from a sequence the tracker owns, and `involvedScreenIds` is `raisedOn` plus
    the related ids, sorted.
  - `CrawlRunTracker` owns the case list (`openCase`, `cases()`, `openCaseIdsFor(screenId)`), plus an
    `internal closeCase` used only by tests to pin the `a2h-c2b.13` contract.
- **The case file.** New `IdentityCaseStore` writes and reads `identity-cases.xml` in the session
  directory.
  - The root is `<identity-cases schema="v1">`, with one
    `<case id status kind raised-on detail>` per case, sorted by id.
  - Each case has `<screen screen-id role="raised-on|related"/>` children and the rejected proposal as
    `<traits>`, written by `ScreenIdentityXml.appendTraits` so there is one trait syntax.
  - The file is written atomically (temporary file, then rename) every time a case is opened, and is
    absent when the crawl has none.
- **Pointers.** `ScreenCrawlState` gains `openCaseIds: List<String>`. `AccessibilityXmlSerializer`
  writes `<identity-case ref="…"/>` for each one inside `<crawl>`, after `<screen-identity>`, and
  writes nothing when the list is empty. `rewriteScreenXmlFor` fills the list from
  `tracker.openCaseIdsFor(screenId)`, so pointers are always derived and a closed case's pointer
  disappears on the next rewrite. `ScreenXmlReader` reads them, for the consistency check only.
- **Rewrite every involved screen.** Opening a case rewrites the XML of **every** involved screen,
  not only the one it was raised on, so a related screen shows its pointer at once.
- **Loader.**
  - `SavedCrawlLoader` reads `identity-cases.xml` into `LoadedCrawlState.cases`, and the tracker is
    seeded from it.
  - `isCrawlScreenXml` excludes `identity-cases.xml`. Today it would be scanned as a screen and logged
    as malformed.
  - Resume **fails loudly, naming the file**, on a malformed case file or a pointer whose id is not in
    it. `MalformedScreenIdentity`-style errors propagate; nothing is skipped.
- **No manifest field.**
- **Tests (`IdentityCaseStoreTest`, plus loader and serializer pins):**
  - write → read → equal, and two writes byte-identical;
  - a multi-screen case with pointers on each screen, and two cases on one screen;
  - loader → rewrite byte-identical for both the case file and the pointers;
  - `closeCase` drops the pointers and keeps the case `closed` in the file;
  - the two loud failures;
  - the case file is not loaded as a screen;
  - with no case, no pointer and no file, and pre-existing XML pins stay byte-identical.

### Step 6 — Known screens and their trees (D, E)

- **Delete the name map.** `dedupKeyToScreenId` and `findScreenIdByIdentity` go from
  `CrawlRunTracker`, from `LoadedCrawlState` and the loader, and from the coordinator's
  `fromExistingState` call.
- **Add the known-screen view.** `tracker.knownIdentities(): Map<String, ScreenIdentity>`, in
  insertion order, which `matchKnownScreens` then sorts.
- **Add the tree cache.** New `KnownScreenTrees`: `treeFor(record)` reads
  `CaptureTreeReader.readFirstViewport(File(record.xmlPath))` once and caches it per screen id for the
  run. A fresh screen's XML has just been written, so a fresh crawl and a resumed crawl read the same
  bytes (E). A record whose tree is unreadable is excluded from 2(d) and logged
  `known_tree_unreadable`. It is never treated as a non-collision silently.
- **Tests:** tracker exposure and the loader dropping the map. The cache test reads a written screen,
  hits the cache on the second read (proven by deleting the file), and logs the unreadable case.

### Step 7 — Pause plumbing (1)

- **Reason.** `PauseReason.IDENTITY_CASE`, with `Host.awaitIdentityCaseDecision(case, snapshot)`
  (fact 5).
- **Production host.** `AppToHtmlAccessibilityService` routes it to a new
  `CrawlerSession.pauseForIdentityCase(case, snapshot)`. That shares the existing pending-decision
  machinery, so Continue and Stop resolve as today. `CrawlerUiState` gains `identityCase`.
- **UI.** `MainActivity`'s pause card adds the kind, the screen and the detail to `pauseReasonText`,
  plus the related screens.
- **Stop message.** `stopMessageForPauseReason` gets the new reason.
- **Tests:** `CrawlerSessionTest` covers the pause → Continue and pause → Stop state transitions, and
  that the case shows in the UI state.

### Step 8 — Coordinator wiring (B) — the crawl-behaviour step

Every case calls one private `raiseIdentityCase(...)`. In this order, it:
1. opens the case in the tracker;
2. writes `identity-cases.xml`;
3. rewrites the XML of every involved screen, so the pointers appear;
4. saves the manifest;
5. logs `identity_case_opened` with the case id;
6. awaits the decision. On **Stop**
it calls `abortPartialCapture` with the case's message. On **Continue** it returns to the call site,
which acts per scope call B.

- **Root (new crawl).** Propose → settle against no known screens. On `Settled`, the record carries
  the traits. Otherwise the record is captured unsettled and the case is raised after the files are
  written.
- **Child.** Build the observed context from the child's step-0 tree and its name, then `recognize`:
  - `Link` → `LINKED_EXISTING` as today. The `UNSETTLED_NAME` path logs `linked_existing_unsettled`.
  - `New` → propose, then settle against every known screen. On `Settled`, capture with traits.
    Otherwise capture unsettled, raise `PROPOSAL_DOES_NOT_HOLD`, and on Continue enqueue it as today.
  - `Collision` → capture as a **new, unsettled** screen first, then raise `UNIQUENESS_COLLISION`
    naming every match. On Continue it stays a new screen and is enqueued. Nothing is linked.
- **Replay arrival.** `TraitsDoNotHold` raises `SETTLED_DOES_NOT_HOLD` on that screen's record. On
  Continue it takes `handlePreparationFailure` as today. `NameDiverged` is unchanged.
- **Never replace.** No path assigns a fresh proposal to a record that already has traits. Re-scans
  (`prepareScreenForExpansion` and the resumed root) only read the record's identity.
- **Tests** (`DeepCrawlCoordinatorTest`, FakeHost scenarios):
  - each case kind pauses after its files exist;
  - Continue and Stop per kind;
  - two STRONG same-name screens with different id controls are not merged (red→green);
  - two WEAK lookalikes raise a case and are not merged (red→green);
  - a replay-arrival trait failure pauses (red→green);
  - an all-unsettled loaded crawl links by name with no case pause;
  - a settled record is kept across a re-scan whose proposal differs;
  - two known-screen orders give identical decisions.

### Step 9 — Pre-existing test fallout

Run the full suite and list every red pre-existing test **by the behaviour it asserts**, not by name
(`factory/FUNCTION.md`). For each one, the change is one of:
- an expected scope change (name map gone, XML bytes, WEAK merges now cases) — adjusted, and the
  justification archived; or
- a genuine regression, which gets fixed in code, not in the test.

The `FakeHost` gains `awaitIdentityCaseDecision` (default `CONTINUE`, recorded). That is the one
mechanical edit to shared test code.

### Step 10 — Archives, mutations, docs, final state

- **Red→green.** Archive the four red→green pairs (Steps 1 and 8) under `evidence/`.
- **Mutations.** Apply SCOPE mutations (1)–(6) one at a time. Each must show at least one pin red;
  archive the output and revert.
- **Docs.** Add pointers in `documentation/crawler-module.md` and `documentation/data-and-state.md`
  to the lifecycle and `identity-cases.xml`, by reference to the code and not by restatement.
- **`a2h-c2b.13`.** Record the closing contract in its notes: set `status="closed"`, keep the case in
  the file as history, and the pointers disappear on rewrite.
- **Beads.** File the carry-over bead (scope call 3).
- **Final state.** Re-run `./gradlew test` and `./gradlew assembleDebug` after the last edit.
- **Gate 3 on device.** Rebuild and install, crawl Settings and one dense third-party app, and record
  the case rate, the settled count and the WEAK re-pause count under `evidence/`.

## Risks and how the plan meets them

| Risk | Guard |
|---|---|
| Most screens on an id-poor app pause | Measured at Gate 3; Continue keeps the crawl moving (scope calls 1, B) |
| Machine traits make replay arrival newly fail on churn | `minRows = 1`; controls only from ids, which had 0% churn in `a2h-u68`'s evidence; every failure is now a visible case, not an abort |
| O(N²) tree reads on large crawls | Each tree is read once and cached (Step 6); 2(d) is set lookups per known screen |
| The fixture leaks personal data (Wi-Fi names) | The fixture is reviewed before commit, and labels are redacted in a copy if needed; evidence notes what was changed |
| Sibling collapse via WEAK names | Amendment: WEAK never links; the pin is red→green plus mutation (6) |

## Approval

`[x] User confirmed`
