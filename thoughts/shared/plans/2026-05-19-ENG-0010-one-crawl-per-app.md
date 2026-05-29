# ENG-0010: One Crawl Per App — Per-Screen XML as the Source of Truth

Date: 2026-05-19
Research: `thoughts/shared/research/2026-05-08-one-crawl-per-app-state-source-of-truth.md`
Prior plan context: `thoughts/shared/plans/2026-04-28-ENG-0009-entry-restore-replay-hardening.md`
GitHub issue: #4 (saved crawl per app)

## Overview

Replace today's "one timestamped crawl session per Start Deep Crawl tap" with a
single saved crawl per app, stored under `html/<package>/crawl/`. The per-screen
XML becomes the on-disk source of truth: every `<screen>` gains a `<crawl>`
block at its head carrying identity, route, expansion status, and per-element
`<edge>` lifecycle. The crawler reads those XMLs on launch to rehydrate the
tracker and to seed the BFS frontier. A new picker on `MainActivity` lets the
user Continue, Resume from a specific screen, or Re-expand a screen.

The chosen design is fully specified in the research doc's "Chosen Design"
section (lines 1104-1391) and "Resume UX" section (lines 1392-1446). This plan
sequences the implementation into eight independently shippable phases.

`crawl-index.json` and `crawl-graph.{json,html}` continue to be written
mid-run during this plan — they are no longer load-bearing on resume, but the
write paths stay in place to minimize blast radius. A follow-up will demote
them to end-of-run-only and eventually delete them.

## Current State

Source-of-truth state (in-memory only today):

- `CrawlRunTracker` owns `screens`, `edges`, `screenFingerprintToId`,
  `rootScreenId`, `nextScreenSequence`, `nextEdgeSequence`
  (`app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:3-15`).
  Constructed empty at `DeepCrawlCoordinator.crawl(...):42-46`; no
  manifest-loading constructor.
- `DeepCrawlCoordinator` owns `frontier: ArrayDeque<String>`
  (`DeepCrawlCoordinator.kt:127`), `resolvedLinksByScreenId`
  (`DeepCrawlCoordinator.kt:29`), and `allowedPackageNames`
  (`DeepCrawlCoordinator.kt:33,61-62,379`). Frontier and approvals reset
  every run.

Persistence today:

- `CaptureFileStore.createSession(...)` always mints a fresh
  `html/<pkg>/crawl_<yyyyMMdd_HHmmss>/` directory
  (`CaptureFileStore.kt:16-45`). No per-app stable directory.
- `CrawlManifestStore.toJson(...)` serializes `CrawlManifest` to
  `crawl-index.json` on every material event
  (`CrawlManifestStore.kt:14-98`). Writer only; no reader.
- `AccessibilityXmlSerializer.serialize(snapshot)` writes a per-screen
  `<screen>` with `<merged-elements>` and `<scroll-steps>`
  (`AccessibilityXmlSerializer.kt:4-19`). No `<crawl>` block; elements are
  self-closing.

Edge / screen model today:

- `CrawlEdgeStatus` has six terminal values (`CAPTURED`, `LINKED_EXISTING`,
  `SKIPPED_BLACKLIST`, `SKIPPED_NO_NAVIGATION`, `SKIPPED_EXTERNAL_PACKAGE`,
  `FAILED`) — no `PENDING` / `IN_PROGRESS`
  (`CrawlerModels.kt:418-425`).
- `CrawlScreenRecord` has no `expansionStatus` — screen-level "done vs
  remaining" only exists as deque membership
  (`CrawlerModels.kt:427-442`).
- `CrawlEdgeRecord` does not carry the captured child's screen name or an
  approval marker (`CrawlerModels.kt:444-456`).
- `ScreenNaming.analyzeScreenName(...)` is content-driven; it has no
  short-circuit for a frozen name passed in by the caller
  (`ScreenNaming.kt:140-245`).

UI surface today:

- `MainActivity.kt` has a single "Start Deep Crawl" button per selected app.
  No picker. No surfacing of prior saved state.

## Desired End State

- A single `html/<package>/crawl/` directory per selected app holds the saved
  crawl. Re-running the crawler with the same selection extends the saved
  state; no new directory is minted.
- Each `<screenId>_<slug>.xml` carries a `<crawl schema="v1">` block at its
  head plus a `<edge>` child on each `<element>` in `<merged-elements>`. The
  XMLs together are sufficient to rehydrate `CrawlRunTracker` on the next
  launch with no JSON manifest read.
- `CrawlEdgeStatus` includes `PENDING` and `IN_PROGRESS`. Edges are added at
  `planTraversal` time and mutate in place. `CrawlScreenRecord` carries
  `expansionStatus: NOT_STARTED | IN_PROGRESS | COMPLETE`.
- Frontier on resume is derived: NOT_STARTED screens + the single
  IN_PROGRESS screen (if any). `expandScreen(...)` on an IN_PROGRESS screen
  processes only `PENDING` / `IN_PROGRESS` edges.
- The parent's `<edge child-screen-name="...">` is the authority for the
  child's name on subsequent runs. `ScreenNaming.analyzeScreenName(...)`
  accepts an optional `preferredName` short-circuit.
- External-package approvals are durable: `<edge approval="explicit">` on the
  originating edge; loader unions these into `allowedPackageNames`. Revocation
  flips `approval="revoked"`.
- `FAILED` edges become `PENDING` on every load (retry-by-default), with
  `message` dropped.
- `MainActivity` shows a saved-crawl picker with status badges and three
  resume modes: Continue auto, Resume from X, Re-expand X. A "New Crawl"
  action wipes the per-app `crawl/` directory.
- `CrawlManifestStore` writes continue (preserved for now); no manifest reads
  are added. Legacy `crawl_<timestamp>/` sibling directories are ignored.

## Phase 1: Edge / Screen Lifecycle Data Model

Files:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
- `app/src/test/java/com/example/apptohtml/crawler/CrawlRunTrackerTest.kt`

Changes:

- [x] Add `enum class ScreenExpansionStatus { NOT_STARTED, IN_PROGRESS, COMPLETE }`
  to `CrawlerModels.kt` (near `CrawlRunStatus` at `CrawlerModels.kt:411-416`).
- [x] Extend `CrawlEdgeStatus` (`CrawlerModels.kt:418-425`) with `PENDING` and
  `IN_PROGRESS` at the top of the enum so the order is
  `PENDING, IN_PROGRESS, CAPTURED, LINKED_EXISTING, SKIPPED_BLACKLIST,
  SKIPPED_NO_NAVIGATION, SKIPPED_EXTERNAL_PACKAGE, FAILED`. Lowercase enum
  name flows directly into XML status attribute.
- [x] Add `enum class CrawlEdgeApproval { NONE, EXPLICIT, REVOKED }` and
  store it on `CrawlEdgeRecord` (`CrawlerModels.kt:444-456`) as
  `val approval: CrawlEdgeApproval = CrawlEdgeApproval.NONE`.
- [x] Add `val childScreenName: String? = null` to `CrawlEdgeRecord` —
  authority for child name freeze (see Phase 5).
- [x] Add `val expansionStatus: ScreenExpansionStatus = ScreenExpansionStatus.NOT_STARTED`
  to `CrawlScreenRecord` (`CrawlerModels.kt:427-442`).
- [x] In `CrawlRunTracker.kt`:
  - [x] Make `screens` / `edges` mutable list access available via
    `findScreen` / a new `findEdge(edgeId: String): CrawlEdgeRecord?` and an
    `outboundEdges(screenId: String): List<CrawlEdgeRecord>`.
  - [x] Add `setScreenExpansionStatus(screenId: String, status: ScreenExpansionStatus)`
    that replaces the record in-place.
  - [x] Add `addPendingEdge(parentScreenId, element): String` that mints an
    edge with `status = PENDING`, returns the new edge ID.
  - [x] Add `updateEdgeStatus(edgeId, status, childScreenId? = null, childScreenName? = null, message? = null, approval? = null)`
    — mutates the edge in place; preserves `edgeId`.
  - [x] Add `setEdgeApproval(edgeId, approval)` for revocation flow.
  - [x] Add a secondary constructor (or `fromExistingState(...)` companion
    factory) that takes pre-populated `screens`, `edges`,
    `screenFingerprintToId`, `nextScreenSequence`, `nextEdgeSequence`,
    `rootScreenId`. Used in Phase 4 hydration. Validate `nextScreenSequence
    >= max(parseScreenSequence(screenId)) + 1` defensively.
  - [x] Internal helper `parseScreenSequence(screenId: String): Int` parses
    `screen_NNNNN`. Mirror `screenIdFor(...)` at
    `DeepCrawlCoordinator.kt:1548-1550`.
- [x] Tests in `CrawlRunTrackerTest.kt` (create the file if missing):
  - [x] `addPendingEdge` mints `edge_NNN`-formatted ID and stores `PENDING`.
  - [x] `updateEdgeStatus` preserves `edgeId` across `PENDING → CAPTURED`.
  - [x] `setScreenExpansionStatus` updates the screen record in place.
  - [x] `fromExistingState` exposes the loaded screens via `findScreen` and
    the loaded fingerprint index via `findScreenIdByFingerprint`.
  - [x] `nextScreenSequenceNumber()` after `fromExistingState` returns
    `max+1` of the loaded sequences.

Design notes:

- Edge IDs remain `edge_%03d`. `nextEdgeSequence` is in `CrawlRunTracker.kt:13`
  and is allocated at `kt:60`. The XML edge ID format stays stable; on
  hydration, the existing IDs flow back in as-is.
- Defaulting new enum and field values to NONE / NOT_STARTED preserves
  source compatibility for existing callers; only the tracker / serializer
  / coordinator paths set them.

Verification after Phase 1:

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlRunTrackerTest`
- [x] `.\gradlew.bat assembleDebug` compiles with the new fields.

## Phase 2: XML Serializer — `<crawl>` Block + Per-Element `<edge>`

Files:

- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/test/java/com/example/apptohtml/crawler/AccessibilityXmlSerializerTest.kt`

Changes:

- [x] Add a `data class ScreenCrawlState` in `CrawlerModels.kt`:
  - `screenId: String`, `depth: Int`, `expansionStatus: ScreenExpansionStatus`,
    `isRoot: Boolean`, `screenIdentity: ScreenIdentityFields`,
    `parent: ParentEdgeRef?`, `route: CrawlRoute`,
    `runLevel: RunLevelState?` (non-null when `isRoot`),
    `edgesByElement: Map<PressableElementLinkKey, EdgeXmlView>`.
- [x] Add `data class ScreenIdentityFields(packageName: String, title: String, hints: List<String>)`.
- [x] Add `data class ParentEdgeRef(screenId: String, triggerLabel: String?, triggerResourceId: String?)`.
- [x] Add `data class RunLevelState(sessionId, startedAt: Long, finishedAt: Long?, status: CrawlRunStatus, maxDepthReached: Int)`.
- [x] Add `data class EdgeXmlView(edgeId, status: CrawlEdgeStatus, childScreenId: String?, childScreenName: String?, message: String?, approval: CrawlEdgeApproval, externalPackage: String?)`.
- [x] Extend `AccessibilityXmlSerializer`:
  - [x] Add `fun serialize(snapshot: ScreenSnapshot, crawlState: ScreenCrawlState?): String` —
    when `crawlState` is non-null, emit `<crawl>` between the opening
    `<screen …>` tag and `<merged-elements>`.
  - [x] Keep the original `serialize(snapshot)` as a one-line delegate that
    passes `crawlState = null` (used by ad-hoc captures, fingerprint-stability
    tests).
  - [x] In `appendMergedElements(...)` (`AccessibilityXmlSerializer.kt:89-124`),
    add an optional `edgesByElement: Map<PressableElementLinkKey, EdgeXmlView>`
    parameter. When supplied, for each element look up by
    `element.toLinkKey()` and emit a `<edge …/>` child instead of the current
    self-close at line 118. Format matches the matrix in the research doc
    (`research doc lines 1245-1254`):
    ```
    <edge id="edge_NNN" status="..."
          child-screen-id="..." child-screen-name="..."
          message="..." approval="explicit" external-package="..." />
    ```
    Attributes only included when applicable; `approval` attribute omitted
    when `NONE`.
  - [x] New private helper `appendCrawlBlock(builder, crawlState, depth = 1)`
    that emits the structure shown in the research doc lines 1125-1183:
    - Attributes on `<crawl>`: `schema="v1"`, `screen-id`, `depth`,
      `expansion-status` (lowercased enum name), `is-root`.
    - When `isRoot`, also `session-id`, `started-at`, `finished-at`
      (omit when null), `status` (lowercased `CrawlRunStatus.name`),
      `max-depth-reached`.
    - Child `<screen-identity package="..." title="..." hint-1="..." hint-2="..." />` —
      use up to two hints, omit hint attributes when absent.
    - Child `<parent screen-id="..." trigger-label="..." trigger-resource-id="..." />`
      when `parent != null`. Omit when root.
    - Child `<route>` containing one `<step>` per `CrawlRouteStep`. Each
      `<step>` carries every primitive field as an attribute and two
      child elements: `<expected-destination-identity .../>` decomposed
      from `step.expectedDestinationFingerprint` (parse the
      `v2:pkg:…:title:…:hint:…` string back into the four name=value
      attributes) and `<expected-replay root-class="..." />` containing
      decomposed `<element>` children parsed from
      `step.expectedReplayFingerprint`.
- [x] Add a helper `internal object ScreenIdentityCodec` with:
  - `fun encode(packageName: String, title: String, hints: List<String>): String` —
    matches `ScreenNaming.buildScreenIdentity(...)` format
    (`ScreenNaming.kt:107-138`) so the encoded string equals
    `screenFingerprint`.
  - `fun decode(fingerprint: String): ScreenIdentityFields?` — parses
    `v2:pkg:<pkg>:title:<title>:hint:<h1>|<h2>` back; tolerant of `"none"`
    placeholders.
- [x] Add a helper `internal object ReplayFingerprintCodec` with:
  - `fun decode(fingerprint: String): ReplayFingerprintFields?` returning
    `(rootClass: String, elements: List<ElementFields>)`. Each element has
    seven fields per `ScrollScanCoordinator.buildElementFingerprint(...)`
    (`ScrollScanCoordinator.kt:354-370`). Parsing fixed-width 7-field
    records works around the `||` ambiguity called out in the research
    doc lines 745-750 (split on `||` after the `::`, then split each
    record on `|` and verify exactly 7 fields).
  - `fun encode(rootClass: String, elements: List<ElementFields>): String`
    for round-trip tests.
- [x] Tests in `AccessibilityXmlSerializerTest.kt` (create if missing):
  - [x] Round-trip: serialize a screen with `crawlState` and assert the
    resulting XML contains the expected `<crawl>` / `<screen-identity>` /
    `<parent>` / `<route>` structure with attribute values matching
    the source.
  - [x] Per-element edge emission: an element with a `PENDING` edge emits
    `<edge id="edge_004" status="pending" />`; with `CAPTURED`, emits
    `child-screen-id` and `child-screen-name`.
  - [x] Element with no edge in the map emits a self-closing `<element />`
    (matches today's output).
  - [x] Root screen XML has run-level attributes; non-root XML omits them.
  - [x] `ScreenIdentityCodec.encode/decode` round-trips on a sample with
    two hints and on a sample with zero hints (writes `"none"`).
  - [x] `ReplayFingerprintCodec.decode` correctly handles an element with
    empty `resourceId` (the `||` collision case from research doc line 745).

Design notes:

- Decomposed `<expected-destination-identity>` and `<expected-replay>` are
  human-readable; the in-memory `CrawlRouteStep` still carries the opaque
  fingerprint strings — encode/decode happens only at serializer / reader
  boundaries. No coordinator code changes for route handling in this phase.
- The serializer remains stateless and Android-free; testable on JVM.
- For elements lacking a `PressableElementLinkKey` match in the map (rare
  edge from a TODO future), fall back to self-closing `<element />` and
  surface a logger warning at the coordinator level (out of scope here).

Verification after Phase 2:

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.AccessibilityXmlSerializerTest`
- [x] `.\gradlew.bat assembleDebug`

## Phase 3: Per-App Directory Layout + Per-Screen XML Rewrite Path

Files:

- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/CaptureFileStoreTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorPersistedXmlTest.kt`

Changes:

- [x] Replace the timestamped session subdirectory in
  `CaptureFileStore.createSession(...)` (`CaptureFileStore.kt:16-45`):
  - [x] New fixed directory `File(baseDir, "crawl")`.
  - [x] `sessionId` is now the run start timestamp string but is only used
    for logging/log-file naming, not the directory name.
  - [x] If the caller passes `wipeExisting = true` (default false), delete
    the existing `crawl/` recursively before recreating it. This is the
    "New Crawl" action.
  - [x] Otherwise leave existing files in place. The log file becomes
    `File(crawlDir, "crawl_${timestamp}.log")` so concurrent runs do not
    overwrite. Manifest/graph stay at fixed names
    (`crawl-index.json`, `crawl-graph.json`, `crawl-graph.html`) and get
    overwritten on each save — acceptable since they are derived artifacts.
- [x] Add `CaptureFileStore.wipePackageCrawl(context, packageName)` for the
  New Crawl button (Phase 8 wires it).
- [x] Add `CaptureFileStore.crawlDirectory(context, packageName): File` —
  returns the per-app directory without creating it. Used by Phase 4
  reader.
- [x] In `CaptureFileStore.saveScreen(...)` (`CaptureFileStore.kt:47-69`):
  - [x] Accept a new `crawlState: ScreenCrawlState?` parameter; pass through
    to the serializer.
  - [x] When `crawlState != null`, write the per-screen XML via the
    `ScreenCrawlState`-aware serializer; otherwise keep `snapshot.xmlDump`
    behavior (preserves legacy callers).
  - [x] Continue writing the HTML and merged XML the same way.
- [x] New `CaptureFileStore.rewriteScreenXml(files, snapshot, crawlState)` —
  used by `expandScreen(...)` when an edge mutates without re-scanning.
  Re-uses the serializer; writes via direct `writeText` (tail-truncation
  risk accepted, per decision).
- [x] In `DeepCrawlCoordinator`:
  - [x] Add a helper
    `private fun buildScreenCrawlState(tracker, screenRecord, runStatus): ScreenCrawlState`
    that gathers identity (decoded from `screenRecord.screenFingerprint`
    via `ScreenIdentityCodec.decode`), parent ref, route, expansion status,
    and a `edgesByElement` map of outbound edges keyed by the matching
    `PressableElementLinkKey`. Use the existing `screenRecord` route +
    `tracker.outboundEdges(screenRecord.screenId)`.
  - [x] Hook the XML rewrite at every existing `saveManifest(...)` call site
    inside `expandScreen(...)` — lines 284, 345, 361, 469, 629, 692, and
    826: after `saveManifest`, additionally call
    `CaptureFileStore.rewriteScreenXml(filesFor(parentScreen), parentSnapshot, buildScreenCrawlState(...))`
    for the parent screen, and on CAPTURED, `rewriteScreenXml` for the
    newly captured child too (line 614 area).
  - [x] On `frontier.add(rootScreenId)` (`DeepCrawlCoordinator.kt:128`),
    also write the root screen XML with its `<crawl>` block (currently
    `saveScreen` at line 95 is called without crawl state).
  - [x] Transition the parent's `expansionStatus`:
    - Before the `traversalPlan.eligibleElements.forEachIndexed` loop at
      `DeepCrawlCoordinator.kt:298` → set `IN_PROGRESS` on the parent and
      rewrite parent XML.
    - After the loop completes (just before `expandScreen` returns) → set
      `COMPLETE` and rewrite parent XML.
  - [x] Replace the post-resolution `addEdge` pattern with the new
    pending-then-resolve pattern. For each eligible element:
    1. Before `openChildFromScreen`, `tracker.addPendingEdge(parentScreenId, element)` → returns `edgeId`.
    2. Immediately `tracker.updateEdgeStatus(edgeId, IN_PROGRESS)` + rewrite parent XML.
    3. After resolution, `tracker.updateEdgeStatus(edgeId, <terminal>, childScreenId, childScreenName, message, approval)`.
    4. Rewrite parent XML.
    For `traversalPlan.skippedElements`, mint each as a pending edge then
    immediately update to `SKIPPED_BLACKLIST` (single rewrite cycle).
  - [x] Pass canonical-name string through to the new `childScreenName`
    field on CAPTURED edges (Phase 5 expands on freeze semantics).
- [x] Tests:
  - [ ] `CaptureFileStoreTest`: `createSession` returns the
    `html/<pkg>/crawl/` directory; back-to-back calls reuse it; `wipePackageCrawl`
    deletes the directory contents.
    (Deferred — `CaptureFileStore` Context-dependent helpers need
    Robolectric or instrumented tests; not added in this phase.)
  - [x] `DeepCrawlCoordinatorPersistedXmlTest`: drive an end-to-end synthetic
    crawl using the existing fake host pattern in
    `DeepCrawlCoordinatorTest.kt`; after the crawl, parse the root XML and
    confirm it has `<crawl is-root="true" expansion-status="complete">` and
    the expected `<edge>` entries for each element. (Added as
    `perScreenXml_*` tests inside `DeepCrawlCoordinatorTest`.)

Design notes:

- The order of operations matters for resume safety: rewrite XML *after*
  mutating the tracker, so any read finds either the prior state or the
  current consistent state.
- The XML for any one screen is rewritten on every status transition on
  that screen. The `<scroll-steps>` section is large but stable; an
  optimization (out of scope) is to keep the tail in memory and rewrite
  only the head — defer until profiling shows it matters.
- `CrawlManifestStore.write(...)` writes continue unchanged — JSON manifest
  is preserved for now per decision.

Verification after Phase 3:

- [x] `.\gradlew.bat test` — passes except for the pre-existing flaky
  `discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`
  (introduced in 91c7591, unrelated to Phase 3).
- [x] `.\gradlew.bat assembleDebug`
- [ ] Manual: run a deep crawl on a synthetic app; confirm files land in
  `html/<pkg>/crawl/` (single directory) and each `<screenId>_<slug>.xml`
  has a `<crawl>` block with `<edge>` children.

## Phase 4: Reader + Tracker Hydration

Files:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt` (new)
- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt` (new)
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScreenXmlReaderTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/SavedCrawlLoaderTest.kt`

Changes:

- [x] New `ScreenXmlReader` object:
  - [x] `fun readHead(file: File): ScreenCrawlHead?` — parses just the
    `<crawl>` block + its children (route, identity, parent), stops once
    `</crawl>` is reached. Returns null on malformed input. Uses
    `javax.xml.parsers.DocumentBuilderFactory` (truncating the input at
    `<merged-elements` to avoid parsing the heavy tail).
  - [x] `fun readFull(file: File): ScreenXmlPayload?` — parses head plus
    per-element `<edge>` entries from `<merged-elements>`. Stops before
    `<scroll-steps>` (the heavy section is not needed for resume). Returns
    `(head, edges: List<EdgeXmlView>, elements: List<PressableElement>)`.
    The element list is used to rebuild `PressableElementLinkKey`s for
    `resolvedLinksByScreenId`.
- [x] New `SavedCrawlLoader` object:
  - [x] `fun load(crawlDir: File): LoadedCrawlState?` — scans
    `crawlDir.listFiles().filter { it.name.endsWith(".xml") && !it.name.endsWith("_merged_accessibility.xml") }`,
    reads each via `ScreenXmlReader.readFull`, and assembles:
    - `screens: List<CrawlScreenRecord>` — rebuild from
      `ScreenCrawlHead` + paths via `File(crawlDir, "${screenId}_${slug}.html")` etc.
      `screenFingerprint` re-encoded from `screen-identity` via
      `ScreenIdentityCodec.encode`. `replayFingerprint` is recomputed by the
      *coordinator* at expansion time, not by the loader; the loader leaves
      it as the empty string and the coordinator's existing rescan logic
      handles it.
    - `edges: List<CrawlEdgeRecord>` — every `<edge>` child re-projected.
      Apply the `failed → pending` normalization at this point: any edge
      with `status = FAILED` becomes `PENDING` and its `message` is
      dropped. (The on-disk rewrite of these normalized rows happens on
      the next mutation; loader does not touch disk.)
    - `screenFingerprintToId: LinkedHashMap<String, String>` — populated
      from every screen whose `ScreenIdentityCodec` round-trip succeeds and
      whose identity confidence is STRONG (re-evaluated via
      `ScreenNaming.buildScreenIdentity(name, packageName, root = null)`,
      which can compute confidence from name/hints alone).
    - `nextScreenSequence = max(parseScreenSequence(screenId)) + 1`,
      `nextEdgeSequence = max(parseEdgeSequence(edgeId)) + 1`.
    - `rootScreenId = screens.find { it.depth == 0 }?.screenId`.
    - `runLevelState: RunLevelState?` — read from the root XML's `<crawl is-root="true">`.
    - `allowedPackageNames: Set<String>` — from every `<edge approval="explicit">`,
      look up `edge.childScreenId` in the assembled screens and add its
      `packageName`. Always include `selectedApp.packageName` at the call
      site (Phase 7 handles seeding).
    - `resolvedLinksByScreenId: Map<String, Map<PressableElementLinkKey, String>>` —
      rebuild from `CAPTURED` and `LINKED_EXISTING` edges by looking up
      each child screen's HTML filename in the screens list.
  - [x] Returns `null` when the directory does not exist or contains no
    XMLs. Returns a `LoadedCrawlState` with a possibly-empty `screens` list
    otherwise (caller decides whether to treat empty as "fresh crawl").
- [x] Wire `CrawlRunTracker.fromExistingState(...)` (added in Phase 1) to
  accept the loaded screens, edges, fingerprint index, and sequence
  counters. Validate invariants (`rootScreenId == null` only if no screens;
  sequence counters monotonic; at most one `IN_PROGRESS` screen).
  (Already wired in Phase 1; Phase 4 confirms hydration via
  `SavedCrawlLoaderTest.load_tracker_hydration_round_trips`.)
- [x] Tests:
  - [x] `ScreenXmlReaderTest`: write a known-good XML via Phase 2
    serializer, read back via `readFull`, and assert structural equality.
    Verify malformed XML returns null. Verify head-only mode stops before
    `<merged-elements>`.
  - [x] `SavedCrawlLoaderTest`: prepare a synthetic `crawl/` directory by
    writing several screen XMLs (root + two children, one IN_PROGRESS),
    call `load`, and assert:
    - All screens recovered, with correct `expansionStatus`.
    - `nextScreenSequence` is the loaded max + 1.
    - A `FAILED` edge in the on-disk XML appears as `PENDING` in the
      loaded `CrawlEdgeRecord` (message dropped).
    - An `approval="explicit"` edge contributes its child's package to
      the returned `allowedPackageNames`.
    - `resolvedLinksByScreenId` includes the parent → child HTML mapping
      derived from a `CAPTURED` edge.

Design notes:

- The reader does not validate that the on-disk XML is internally
  consistent (e.g. every edge `child-screen-id` actually exists). It logs
  warnings via `DiagnosticLogger` but returns whatever is parseable.
  Inconsistencies turn into edge re-attempts on next run, which is the
  same recovery story `prepareScreenForExpansion(...)` already implements
  (`DeepCrawlCoordinator.kt:737-788`).
- The reader is read-only. No on-disk rewrites happen at load time. The
  `failed → pending` normalization is in-memory only; disk catches up on
  the next mutation.

Verification after Phase 4:

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScreenXmlReaderTest`
- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.SavedCrawlLoaderTest`
- [x] `.\gradlew.bat assembleDebug`

## Phase 5: Parent-Frozen Child Screen Names

Files:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScreenNamingTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorNameFreezeTest.kt`

Changes:

- [x] Add `preferredName: String? = null` parameter to
  `ScreenNaming.analyzeScreenName(eventClassName, selectedApp, root, preferredName = null)`
  (`ScreenNaming.kt:140-245`). When non-null and non-blank, return a
  `ScreenNameDebugInfo` immediately with `chosenStrategy = "preferred_name"`.
- [x] Where the coordinator calls `analyzeScreenName(...)` to name a child
  screen after capture, do not pass `preferredName` (this is the first
  capture; the canonical name is being chosen now).
- [x] Where the coordinator rescans an already-known child screen
  (`prepareScreenForExpansion(...)` at `DeepCrawlCoordinator.kt:737-788`,
  and the `replayRouteToScreen(...)` per-step rescan at
  `DeepCrawlCoordinator.kt:1158-1299`), pass the parent edge's
  `childScreenName` through as `preferredName` so the rescanned screen
  keeps its original name.
  (`replayRouteToScreen(...)` does not call `scanCurrentScreen` per
  intermediate step today — only the final destination is rescanned via
  `prepareScreenForExpansion(...)`, where the freeze is applied.)
- [x] On first CAPTURED outcome inside `expandScreen(...)`, write the
  canonical `childSnapshot.screenName` back to the new `<edge>` via
  `tracker.updateEdgeStatus(edgeId, CAPTURED, childScreenId, childScreenName = childSnapshot.screenName, ...)`.
  (Already done in Phase 3 at `DeepCrawlCoordinator.kt:614-620`.)
- [x] Tests:
  - [x] `ScreenNamingTest`: `analyzeScreenName` returns the preferred
    name verbatim when provided, regardless of `eventClassName` or
    visible-text scores; falls through to existing logic when null/blank.
  - [x] `DeepCrawlCoordinatorNameFreezeTest`: added as `nameFreeze_*`
    tests inside `DeepCrawlCoordinatorTest`. Verifies that rescan via
    `prepareScreenForExpansion(...)` passes the captured screen name as
    `preferredName` and that BFS reaches deeper screens (which would
    otherwise fail if rescan-time name drift altered the screen
    fingerprint and broke replay validation).

Design notes:

- This phase stabilizes `screenFingerprint` across runs by stabilizing the
  title input — which is the most common reason fingerprints drift
  legitimately. Without it, a re-crawl can produce a "Notifications" screen
  named "Notifications (3 new)" on the next launch and the dedup table
  treats them as different screens.
- The root screen has no parent edge; its name is read from its own
  `<screen name="...">` element on resume (loader path) or chosen at
  first capture (no change for the first run).

Verification after Phase 5:

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScreenNamingTest`
- [x] `.\gradlew.bat testDebugUnitTest --tests "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest.nameFreeze_*"`
  (freeze tests live inside `DeepCrawlCoordinatorTest` rather than a
  separate `DeepCrawlCoordinatorNameFreezeTest` class.)
- [x] `.\gradlew.bat assembleDebug`
- [x] `.\gradlew.bat testDebugUnitTest --tests "com.example.apptohtml.crawler.*"` —
  176 tests pass; the only failure is the pre-existing flaky
  `discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`
  (introduced in 91c7591, unrelated to Phase 5).

## Phase 6: Durable External-Package Approvals

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorExternalApprovalTest.kt`

Changes:

- [x] In the `PauseDecision.CONTINUE` branch
  (`DeepCrawlCoordinator.kt:402-403`), right after `allowedPackageNames += childPackageName`,
  call `tracker.updateEdgeStatus(currentEdgeId, status = IN_PROGRESS, approval = EXPLICIT)`
  + `rewriteScreenXmlFor(...)`. The marker is stamped while the edge is
  still `IN_PROGRESS`; the later terminal `updateEdgeStatus` preserves it
  via the `approval ?: existing.approval` fall-through in the tracker.
- [x] When the resolution lands on `CAPTURED`, `EXPLICIT` is set on that
  CAPTURED edge. When the resolution is `LINKED_EXISTING` (rare but
  possible if a re-encounter happens through this gate), `EXPLICIT` is
  set on the LINKED_EXISTING edge instead — the approval is still bound
  to the edge that paused the user.
  (Implemented implicitly: the approval is stamped before resolution and
  carries through to whichever terminal status the edge reaches.)
- [ ] When the loader (Phase 4) returns a non-empty `allowedPackageNames`
  from saved state, the coordinator seeds it at the top of `crawl(...)`
  instead of clearing on line 61. Phase 7 wires this end-to-end.
  (Deferred to Phase 7; loader already extracts `allowedPackages` from
  EXPLICIT edges, see `SavedCrawlLoader.kt:136-142`.)
- [x] Revocation: `tracker.setEdgeApproval(edgeId, REVOKED)` already
  exists from Phase 1 for the picker (Phase 8). No coordinator changes
  needed here.
- [x] Tests:
  - [ ] Synthetic two-run scenario: run 1 includes a Continue at edge E.
    Persist. Run 2 starts from the same package; assert that the gate at
    `DeepCrawlCoordinator.kt:349` does *not* pause again on the second
    crossing into the previously-approved package.
    (Deferred to Phase 7 — requires the coordinator's resume entry point
    to honor `loaded.allowedPackages`. Phase 6 verifies the loader-level
    end of this pipeline: `SavedCrawlLoader.load(...)` surfaces the
    approved package after CONTINUE.)
  - [x] Revoke scenario: rewrite the saved XML to flip `approval="explicit"`
    to `approval="revoked"`; assert `SavedCrawlLoader.load(...)` no longer
    includes the package in `allowedPackages`.
  - [x] Skip decision still records `SKIPPED_EXTERNAL_PACKAGE` with no
    approval marker (XML carries no `approval="..."` attribute and the
    loader's `allowedPackages` set is empty).

Design notes:

- `linked_existing` edges crossing the same boundary do not carry
  approval markers — they inherit through the loader's union of
  `EXPLICIT` edges' child packages.
- Approval revocation does *not* delete the descendant captured screens —
  it only flips the loaded `allowedPackageNames` set. Next encounter
  re-pauses. Captured XML stays on disk.

Verification after Phase 6:

- [x] `.\gradlew.bat testDebugUnitTest --tests "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest.externalPackageDecision_continue_marks_originating_edge_with_explicit_approval" ...skip_does_not_mark_edge_with_approval ...revokedApprovalOnExternalPackageEdge_is_excluded_on_load`
  (approval tests live inside `DeepCrawlCoordinatorTest` rather than a
  separate `DeepCrawlCoordinatorExternalApprovalTest` class.)
- [x] `.\gradlew.bat assembleDebug`
- [x] `.\gradlew.bat testDebugUnitTest --tests "com.example.apptohtml.crawler.*"` —
  179 tests pass; the only failure is the pre-existing flaky
  `discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`
  (introduced in 91c7591, unrelated to Phase 6).

## Phase 7: Coordinator Resume Entry Point

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorResumeTest.kt`

Changes:

- [x] Signature change at `DeepCrawlCoordinator.crawl(...)`
  (`DeepCrawlCoordinator.kt:35-43`):
  - [x] Add `resumeMode: ResumeMode = ResumeMode.ContinueAuto` (with subtypes
    `ContinueAuto`, `ResumeFromScreen(screenId: String)`,
    `ReExpand(screenId: String)`).
- [x] New `enum class CrawlStartIntent { NEW_CRAWL, RESUME }` exposed via
  `CrawlerSession` so the UI can request a wipe vs. a continue.
- [x] At the top of `crawl(...)`:
  - [x] Compute `crawlDir = CaptureFileStore.crawlDirectory(context, selectedApp.packageName)`.
  - [x] When `NEW_CRAWL`: `CaptureFileStore.wipePackageCrawl(...)`. Continue
    with the existing fresh-tracker code path (`new CrawlRunTracker(...)`).
  - [x] When `RESUME`: call `SavedCrawlLoader.load(crawlDir)`. If null or
    empty, fall through to NEW_CRAWL behavior. Otherwise hydrate the
    tracker via `CrawlRunTracker.fromExistingState(...)`, seed
    `allowedPackageNames = loaded.allowedPackages + selectedApp.packageName`,
    seed `resolvedLinksByScreenId = loaded.resolvedLinks.toMutableMap()`.
- [x] Frontier seeding:
  - [x] For `ContinueAuto`: frontier =
    `loaded.screens.filter { it.expansionStatus == NOT_STARTED }.map { it.screenId }` +
    `loaded.screens.find { it.expansionStatus == IN_PROGRESS }?.screenId`
    (prepended). If empty after filtering, the user gets the existing
    "nothing pending" exit path (return `Completed` with the existing
    summary builder).
  - [x] For `ResumeFromScreen(X)`: frontier = `{X}` plus all
    NOT_STARTED descendants reachable from X via existing
    `CAPTURED`/`LINKED_EXISTING` edges. Compute via BFS over `loaded.edges`.
  - [x] For `ReExpand(X)`: drop all outbound edges from X via
    `tracker.clearOutboundEdges(X)` (new method), set X's
    `expansionStatus = NOT_STARTED`, rewrite X's XML (the edges section
    becomes empty), then seed frontier with `{X}`. Descendants stay
    (orphans handled as a known-shape diagnostic only).
- [x] When `expandScreen(...)` runs on a screen with `expansionStatus
  != NOT_STARTED`, do *not* call `planTraversal` afresh. Instead:
  - [x] Replay route via existing `prepareScreenForExpansion(...)` to get a
    fresh snapshot for the live screen.
  - [x] Read the existing outbound edges from the tracker. For every edge
    with `status in (PENDING, IN_PROGRESS)`, find the matching live
    `PressableElement` (by `PressableElementLinkKey`) in the rescanned
    snapshot; if found, resolve that edge using the existing click path.
    If not found, mark it `FAILED` with message "Element no longer present
    on rescan" (which the next load will flip back to PENDING — accept the
    cycle as the documented retry policy).
  - [x] After the loop, set `expansionStatus = COMPLETE`.
- [x] `CrawlerSession.startCrawl(...)` gains `resumeMode` and forwards.
- [x] Tests:
  - [x] `DeepCrawlCoordinatorResumeTest`: build a saved-state directory
    on a temp dir, invoke `crawl(...)` with `ContinueAuto`, and assert:
    - [x] Only NOT_STARTED screens get re-expanded.
    - [x] An IN_PROGRESS screen with one PENDING edge processes exactly that
      element.
    - `screenFingerprintToId` from saved state is honored — a re-discovery
      of the same fingerprint becomes a `LINKED_EXISTING` edge instead of
      a re-capture.
  - [x] Resume-from-X mode: only X and its NOT_STARTED descendants are
    in the frontier.
  - [x] Re-expand mode: X's prior outbound edges are dropped; the XML is
    rewritten with no edges; X is then re-expanded fresh.

Design notes:

- Idempotent `expandScreen` is the linchpin: existing live tests already
  cover the happy path, so the resume path only adds a fork at the top of
  the function. Keep both paths in one function rather than introducing a
  separate `resumeExpandScreen`.
- The `replayFingerprint` of a loaded screen is recomputed at the moment
  of expansion (because the loader leaves it blank). This is consistent
  with `prepareScreenForExpansion`'s existing behavior of recomputing on
  every visit.

Verification after Phase 7:

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorResumeTest`
  Equivalent targeted run used because resume tests currently live in
  `DeepCrawlCoordinatorTest`: `.\gradlew.bat testDebugUnitTest --tests
  "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest.resume*"`.
  Full `.\gradlew.bat test` was also run; 185 tests executed and only the
  pre-existing flaky
  `discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`
  failed.
- [ ] `.\gradlew.bat test` (full suite — no regression)
- [x] `.\gradlew.bat assembleDebug`

## Phase 8: Saved-Crawl Picker UI

Files:

- `app/src/main/java/com/example/apptohtml/storage/SavedCrawlRepository.kt` (new)
- `app/src/main/java/com/example/apptohtml/MainActivity.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt`

Changes:

- [x] New `SavedCrawlRepository(context)`:
  - [x] `fun snapshot(packageName: String): SavedCrawlSnapshot?` — calls
    `SavedCrawlLoader.load` against `CaptureFileStore.crawlDirectory(...)`,
    but in head-only mode (faster for picker rendering). Returns
    `SavedCrawlSnapshot(screens: List<SavedScreenSummary>, allowedPackages: List<AllowedPackage>)`.
    (Implemented with the existing loader path rather than head-only so
    pending-edge counts and approval provenance come from the same XML pass.)
  - [x] `fun snapshotFlow(packageName: String): Flow<SavedCrawlSnapshot?>` —
    re-emits when `MainActivity` calls `refresh()` (kept simple — no
    `FileObserver` for now; refresh on screen resume + after crawl completion).
- [x] `MainActivity.kt`:
  - [x] Add a section below "Start Deep Crawl" titled "Saved crawl".
  - [x] When `snapshot == null` or `screens.isEmpty()`, show "No saved
    crawl. Tap Start Deep Crawl to begin one."
  - [x] Otherwise render rows. Each row:
    - [x] Indent by `depth` to imply the parent chain.
    - [x] Screen name + muted `screen_NNNNN`.
    - [x] Status label: `complete`, `in progress`, `not started`.
    - [x] Pending-edge count badge when > 0.
    - [x] Trailing overflow menu with `Resume from here` and `Re-expand`.
  - [x] Primary button "Continue auto" — disabled when no NOT_STARTED and
    no IN_PROGRESS screens remain (helper text: "Crawl complete. Re-expand
    a screen to refine.").
  - [x] Secondary button "New Crawl" — opens a confirmation dialog
    ("Wipe the saved crawl for <app name>? This deletes all captured
    screens for this app."), then dispatches `CrawlStartIntent.NEW_CRAWL`.
  - [x] "Allowed external packages" subsection below the screen list,
    derived from `snapshot.allowedPackages`. Each row shows
    `<package>` plus provenance ("approved from <parentScreenName> →
    <triggerLabel>") and a "Revoke" button that calls a new
    `SavedCrawlRepository.revokeApproval(packageName, edgeId)` which
    in turn invokes the rewriter to flip `<edge approval="explicit">`
    to `<edge approval="revoked">` on the originating screen's XML.
  - [x] Wire each resume action to `CrawlerSession.startCrawl(resumeMode)`.
- [x] `CrawlerSession`:
  - [x] `startCrawl` now accepts a `CrawlStartIntent` and a `ResumeMode`.
  - [x] Phase machine
    (`IDLE → LAUNCHING → WAITING → SCANNING → TRAVERSING → …`) is
    unchanged — only the precondition for `TRAVERSING` changes (hydration
    runs in `LAUNCHING`).
- [x] No new unit tests for UI (the existing test surface does not cover
  Compose). The repository layer is JVM-testable; add minimal
  `SavedCrawlRepositoryTest` for the snapshot derivation logic.

Manual verification after Phase 8:

- [ ] Pick an app, run a full crawl, force-kill the app on the second-to-
  last screen. Reopen. Confirm the picker shows the saved crawl with one
  `▶ in progress` row and pending edges.
- [ ] Tap "Continue auto" — confirm the crawl resumes and completes,
  honoring the saved `allowedPackageNames`.
- [ ] Tap "Resume from <some leaf>" — confirm only that subtree is walked.
- [ ] Tap "Re-expand <some interior screen>" — confirm its edges drop and
  it is re-expanded.
- [ ] Tap "New Crawl" — confirm the directory is wiped and the next run
  starts at depth 0.
- [ ] Verify "Allowed external packages" shows previously-approved
  packages with provenance; Revoke flips the on-disk XML and removes the
  package from the loaded set on the next launch.

## Success Criteria

Automated verification:

- [ ] `.\gradlew.bat test` — full unit test suite passes.
- [ ] `.\gradlew.bat assembleDebug` — release build compiles.
- [ ] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.*` —
  every new test class passes.
- [ ] After a full synthetic run, parsing every `*.xml` in `crawl/` via
  `SavedCrawlLoader.load` reconstructs a `CrawlRunTracker` whose
  `findScreenIdByFingerprint` / `findScreen` / outbound-edge views match
  the in-memory tracker at end-of-run.
- [ ] Round-trip: serialize a snapshot with `ScreenCrawlState`, read it
  back via `ScreenXmlReader.readFull`, and assert structural equality
  on every captured field.

Manual verification:

- [ ] Run a crawl on a real app, force-kill the process mid-run, reopen
  the app, and observe the picker. Resume completes the remaining work.
- [ ] On a second deep crawl, the previously-approved external-package
  boundary does not re-prompt.
- [ ] Re-expanding a screen drops only its outbound edges and re-expands
  it; sibling and ancestor screens are untouched.
- [ ] "New Crawl" wipes only the per-app directory; other apps' crawls
  are untouched.
- [ ] Screen names remain stable across runs on a content-shifting app
  (e.g. one whose notification badges change between runs).

## Out of Scope

- Deletion of `CrawlManifestStore`, `CrawlManifest`, and `crawl-graph.{json,html}`
  writers. Follow-up will move these to end-of-run-only or remove them.
- Atomic XML rewrites (tmp + rename). Tail-truncation risk accepted; a
  truncated file just becomes an orphan on next load with a logger warning.
- Chronic-failure retry caps (`retry-count` on `<edge>`).
- `FileObserver`-driven picker auto-refresh — manual refresh on resume is
  sufficient.
- Orphan-screen cleanup UI for screens whose triggering edges were wiped
  by Re-expand. Surfaced as a known-shape diagnostic only.
- Fingerprint-derived screen IDs. Sequence-allocated `screen_NNNNN` is
  preserved per the research decision.
