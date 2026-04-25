# Deep Crawl Graph - Continuation Implementation Plan

Date: 2026-04-21
Branch: `codex/deep-crawl-graph`
Research basis: [deep-crawl-graph-pr1-research-2026-04-21.md](deep-crawl-graph-pr1-research-2026-04-21.md)
Plan of record: [deep-crawl-graph-plan.md](deep-crawl-graph-plan.md)
Scope: all five remaining workstreams (A-E) delivered on the current `codex/deep-crawl-graph` branch and current draft PR, without merging to `main` until the full feature is complete.

## Iteration Focus

This revision keeps the overall delivery shape from the earlier continuation draft, but tightens the implementation details in three places:

- Replace brittle file:line-heavy references with file/symbol anchors that better match the current branch and will age more gracefully while the work is in flight.
- Make the pause/resume flow explicit about persistence before prompting, so a pause dialog never appears without the current crawl state already saved.
- Add a dedicated per-pause decision token instead of reusing `requestId`, so stale UI taps cannot accidentally resolve a later pause in the same crawl.

## Overview

The remaining implementation stays on the current branch and current draft PR, but is still organized into three major delivery blocks so the work can stay incremental and reviewable:

- **Block 1 - Safety Rules** (workstreams A + C): editable-field capture, `skipEditable`, and the UI/log rename from "one-layer crawl" to "deep crawl".
- **Block 2 - Pause/Resume + Package Boundary Decisions** (workstreams B + D): soft checkpoints, paused-for-decision session state, decision dialog, resume/stop/skip actions, and explicit user approval when traversal leaves the current package.
- **Block 3 - Graph Export** (workstream E): `crawl-graph.json` plus a self-contained offline `crawl-graph.html` emitted alongside every manifest save.

These blocks are sequencing units, not merge units. Nothing in this plan requires merging to `main` between them.

## Current State

Traversal core is already in place: BFS frontier traversal, route replay, fingerprint dedup, `LINKED_EXISTING` edges, weak-title guardrails, recovery/relaunch logic, and incremental manifest saves. The remaining gaps on the current branch are:

| Gap | Current anchor |
|---|---|
| No editable capture on snapshots or pressable elements | `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt` (`AccessibilityNodeSnapshot`, `PressableElement`, `CrawlRouteStep`) and `AccessibilityTreeSnapshotter.kt` |
| No blacklist support for `skipEditable` | `app/src/main/java/com/example/apptohtml/crawler/CrawlBlacklist.kt` and `app/src/main/res/raw/crawl_blacklist.json` |
| UI and session copy still say "one-layer" / "first-screen capture" | `app/src/main/java/com/example/apptohtml/MainActivity.kt` and `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt` |
| No paused-for-decision phase or pause metadata in UI state | `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt` (`CrawlerPhase`, `CrawlerUiState`) |
| No pause/resume/stop/skip API in the session layer | `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt` |
| No checkpoint tracker or pause decision hook in the coordinator | `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt` |
| Replay and restoration still assume the selected package after discovery | `DeepCrawlCoordinator.kt` plus `CrawlerModels.kt` (`CrawlRouteStep`, `CrawlScreenRecord`) |
| No explicit external-package skip status | `CrawlerModels.kt` (`CrawlEdgeStatus`) and `CrawlRunTracker.kt` |
| No graph export artifacts in the crawl session directory | `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt` and `CaptureFileStore.kt` |
| No graph JSON/HTML builder or renderer | new files under `app/src/main/java/com/example/apptohtml/crawler/` |

## Desired End State

- Every safe, non-blacklisted reachable edge is either captured, linked to an existing screen, skipped with a specific reason, or failed with a recorded error.
- Editable targets are skipped by default when `skipEditable` is enabled in `crawl_blacklist.json`.
- When a child screen opens in a different package than the current screen, the crawl pauses, persists current state, returns AppToHTML to the foreground, and asks the user whether to skip that edge or continue across the boundary.
- A soft time or failed-edge threshold also pauses the crawl only after the latest manifest and graph artifacts have been written.
- Continuing after a pause advances only the checkpoint budget that actually triggered, so an early failure spike does not silently defer the first elapsed-time warning, and vice versa.
- Every paused state is represented by a unique decision token so UI actions resolve only the active pause request.
- Each crawl session directory contains `crawl-index.json`, `crawl-graph.json`, and `crawl-graph.html`, with the graph artifacts refreshed whenever the manifest is refreshed.
- Each workstream has targeted unit coverage that fails before the change and passes after.

---

## Block 1 - Safety Rules (workstreams A + C)

Status as of 2026-04-21:

- Completed: Phases 2.1 through 2.4
- Completed: all automated success criteria for Block 1
- Completed: the manual verification checks below

### Phase 2.1 - Editable capture in the snapshot/model pipeline

Status: Completed on 2026-04-21

#### Changes

1. **`CrawlerModels.kt`**
   - Add `val editable: Boolean = false` to `AccessibilityNodeSnapshot`.
   - Add `val editable: Boolean = false` to `PressableElement`.
   - Add `val editable: Boolean` to `CrawlRouteStep`.
   - Add `editable` to `PressableElementLinkKey`.
   - Propagate `editable` through `PressableElement.toLinkKey()`, `toRouteStep()`, and `CrawlRouteStep.toPressableElement()`.
2. **`AccessibilityTreeSnapshotter.kt`**
   - Read `node.isEditable` into `AccessibilityNodeSnapshot.editable`.
   - Copy `node.editable` into `PressableElement.editable` inside `collectPressableElements(...)`.
3. **`SyntheticAccessibilityTreeBuilder.kt`**
   - OR-merge the editable flag in the same way the synthetic merge path already combines `clickable`, `scrollable`, and `checkable`.

### Phase 2.2 - Blacklist `skipEditable`

Status: Completed on 2026-04-21

#### Changes

1. **`CrawlBlacklist.kt`**
   - Add `val skipEditable: Boolean = true` to `CrawlBlacklist`.
   - Insert an editable guard after the existing `skipCheckable` logic:
   ```kotlin
   if (skipEditable && element.editable) {
       return "blacklist-editable"
   }
   ```
2. **`CrawlBlacklistLoader.parse(...)`**
   - Parse `skipEditable = readBoolean(json, "skipEditable", true)`.
3. **`app/src/main/res/raw/crawl_blacklist.json`**
   - Add `"skipEditable": true` beside `"skipCheckable": true`.

### Phase 2.3 - UI and log rename

Status: Completed on 2026-04-21

#### Changes

1. **`MainActivity.kt`**
   - Rename the button text from `Start One-Layer Crawl` to `Start Deep Crawl`.
   - Replace the explainer copy so it describes breadth-first deep traversal, skip/link outcomes, and the eventual graph viewer.
2. **`CrawlerSession.kt`**
   - Change the diagnostic message `Starting first-screen capture ...` to `Starting deep crawl ...`.

### Phase 2.4 - Tests for PR 2

Status: Completed on 2026-04-21

Add or extend tests under `app/src/test/java/com/example/apptohtml/crawler/`:

1. **`CrawlerTraversalTest.kt`**
   - `blacklist_parser_marks_editable_matches`
   - `default_blacklist_blocks_editable_elements`
   - `crawl_blacklist_json_declares_skip_editable_true`
2. **`DeepCrawlCoordinatorTest.kt`**
   - `bfsTraversal_skips_editable_elements_when_skipEditable_true`
3. **Fixture updates**
   - Extend the fake screen-element builder so tests can mark specific labels as editable without changing unrelated fixtures.

### Success Criteria - Block 1

#### Automated

- [x] `./gradlew test --tests "com.example.apptohtml.crawler.CrawlerTraversalTest"` passes with the new editable-blacklist coverage.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest"` passes with the editable skip case.
- [x] `./gradlew assembleDebug` compiles with all new `editable` fields wired through constructors and serializers.
- [x] `./gradlew test` passes.

#### Manual

- [x] Run a deep crawl against an app with a reachable text input and confirm `crawl.log` records `edge_skipped_blacklist` with `reason="blacklist-editable"`.
- [x] Launch the app and confirm the UI says `Start Deep Crawl` and the explainer no longer promises a one-layer crawl.

---

## Block 2 - Pause/Resume + Package Boundary Decisions (workstreams B + D)

Status as of 2026-04-21:

- Completed: Phase 3.1
- Completed: Phase 3.2
- Completed: Phase 3.3
- Completed: Phase 3.4
- Completed: Phase 3.5
- Completed: Phase 3.6
- Completed: all automated success criteria for Block 2
- Completed: the manual verification checks below

### Phase 3.1 - Configuration and decision models

Status: Completed on 2026-04-21

#### New files

1. **`app/src/main/java/com/example/apptohtml/crawler/PauseCheckpointConfig.kt`**
   ```kotlin
   data class PauseCheckpointConfig(
       val initialTimeThresholdMs: Long = 30L * 60 * 1_000,
       val subsequentTimeThresholdMs: Long = 15L * 60 * 1_000,
       val initialFailedEdgeThreshold: Int = 30,
       val subsequentFailedEdgeThreshold: Int = 15,
   )

   enum class PauseReason {
       ELAPSED_TIME_EXCEEDED,
       FAILED_EDGE_COUNT_EXCEEDED,
       EXTERNAL_PACKAGE_BOUNDARY,
   }

   data class PauseProgressSnapshot(
       val elapsedTimeMs: Long,
       val capturedScreenCount: Int,
       val capturedChildScreenCount: Int,
       val failedEdgeCount: Int,
   )

   data class ExternalPackageDecisionContext(
       val currentPackageName: String,
       val nextPackageName: String,
       val parentScreenId: String,
       val parentScreenName: String,
       val triggerLabel: String,
   )

   enum class PauseDecision {
       CONTINUE,
       STOP,
       SKIP_EDGE,
   }
   ```
2. **`app/src/main/java/com/example/apptohtml/crawler/PauseCheckpointTracker.kt`**
   - Owns the rolling checkpoint state.
   - Inputs: `config`, `startedAtMs`, `timeProvider`.
   - State: `nextTimeBudgetMs`, `nextFailedEdgeBudget`, `failedEdgeCount`.
   - API:
     - `recordFailedEdge()`
     - `nextTriggeredReason(): PauseReason?`
     - `rollForwardAfterContinue(reason: PauseReason)` so only the triggered budget advances.

### Phase 3.2 - Coordinator checkpoint wiring

Status: Completed on 2026-04-21

#### Changes

1. **`DeepCrawlCoordinator.kt`**
   - Add `pauseConfig: PauseCheckpointConfig = PauseCheckpointConfig()` to the constructor.
   - Create a `PauseCheckpointTracker` at the start of `crawl(...)`.
2. At the top of the frontier loop, call a helper that:
   - checks whether a checkpoint has fired,
   - builds a `PauseProgressSnapshot`,
   - persists the current manifest and graph artifacts with `IN_PROGRESS`,
   - logs the pause event,
   - delegates to `host.awaitPauseDecision(...)`.
3. After every `FAILED` edge is recorded, call `pauseTracker.recordFailedEdge()` and immediately re-check the checkpoint so failure spikes pause during the same expansion, not one screen later.
4. Use the returned decision as follows:
   - `CONTINUE`: call `pauseTracker.rollForwardAfterContinue(reason)` and continue.
   - `STOP`: route into the existing partial-abort path.
5. Extend the `Host` interface with:
   ```kotlin
   suspend fun awaitPauseDecision(
       reason: PauseReason,
       snapshot: PauseProgressSnapshot,
       externalPackageContext: ExternalPackageDecisionContext? = null,
   ): PauseDecision
   ```

### Phase 3.3 - Package-aware cross-boundary continuation

Status: Completed on 2026-04-21

#### Changes

1. **`CrawlerModels.kt`**
   - Extend `CrawlRouteStep` with `val expectedPackageName: String? = null`.
   - Extend `CrawlScreenRecord` with `val packageName: String`.
   - Add `SKIPPED_EXTERNAL_PACKAGE` to `CrawlEdgeStatus`.
2. **`CrawlRunTracker.kt`**
   - Persist `snapshot.packageName` into `CrawlScreenRecord.packageName`.
   - Count `SKIPPED_EXTERNAL_PACKAGE` inside `skippedElementCount()`.
3. **`DeepCrawlCoordinator.kt`**
   - After `childInitialRoot` is captured and before dedup/fingerprint lookup, compare the child package to the current screen package.
   - If the package differs:
     - build `ExternalPackageDecisionContext`,
     - persist current state before prompting,
     - call `host.awaitPauseDecision(PauseReason.EXTERNAL_PACKAGE_BOUNDARY, ...)`.
   - On `SKIP_EDGE`, write a `SKIPPED_EXTERNAL_PACKAGE` edge and continue without enqueuing.
   - On `CONTINUE`, keep scanning using the child screen's actual package.
   - On `STOP`, reuse the partial-abort path.
4. **Replay / recovery logic**
   - Route replay uses `routeStep.expectedPackageName`.
   - Screen restoration uses `screenRecord.packageName`.
   - Entry restoration still starts from the originally selected app, but once replay begins, each step is captured under the package recorded for that step/screen.
5. **Manifest fixtures and serializers**
   - Update any route/screen expectations to include `expectedPackageName` and `packageName`.

### Phase 3.4 - Session state and decision-token protocol

Status: Completed on 2026-04-21

This is the main design hardening in this revision. Reusing `requestId` alone is not enough because a single crawl can pause multiple times. The active pause must therefore be identified by a distinct token.

#### Changes

1. **`CrawlerModels.kt`**
   - Add `PAUSED_FOR_DECISION` to `CrawlerPhase`.
   - Extend `CrawlerUiState` with:
   ```kotlin
   val pauseDecisionId: Long? = null,
   val pauseReason: PauseReason? = null,
   val pauseElapsedTimeMs: Long? = null,
   val pauseFailedEdgeCount: Int? = null,
   val pausedCapturedScreenCount: Int? = null,
   val pausedCapturedChildScreenCount: Int? = null,
   val pauseCurrentPackageName: String? = null,
   val pauseNextPackageName: String? = null,
   val pauseTriggerLabel: String? = null,
   ```
   - Add:
   ```kotlin
   fun withPausedForDecision(
       decisionId: Long,
       reason: PauseReason,
       snapshot: PauseProgressSnapshot,
       externalPackageContext: ExternalPackageDecisionContext? = null,
   ): CrawlerUiState = ...

   fun withResumedFromDecision(): CrawlerUiState = ...
   ```
2. **`CrawlerSession.kt`**
   - Store:
     - `private var pendingPauseDecision: CompletableDeferred<PauseDecision>? = null`
     - `private var pendingPauseDecisionId: Long? = null`
   - `pauseForDecision(...)` should:
     - create the deferred first,
     - create a new `decisionId` (for example `SystemClock.elapsedRealtimeNanos()` or a simple incrementing counter),
     - publish `withPausedForDecision(decisionId, ...)`,
     - call `returnToApp()`,
     - await the deferred.
   - `resumeCrawl(requestId: Long, decisionId: Long)`
   - `skipExternalEdge(requestId: Long, decisionId: Long)`
   - `stopAndSave(requestId: Long, decisionId: Long)`
   - Each action must no-op unless both `requestId` and `decisionId` still match the active pause.
   - After resolving, clear the pending deferred and token before the next pause.
3. **`AppToHtmlAccessibilityService.kt`**
   - Implement `Host.awaitPauseDecision(...)` by delegating to `CrawlerSession.pauseForDecision(...)`.

### Phase 3.5 - UI dialog

Status: Completed on 2026-04-21

#### Changes

1. **`MainActivity.kt`**
   - Add a `CrawlerPhase.PAUSED_FOR_DECISION` branch.
   - Show a second `AlertDialog` whenever the phase is paused.
2. The dialog uses `pauseDecisionId`, not just `requestId`:
   ```kotlin
   val requestId = crawlerState.requestId ?: return@Column
   val decisionId = crawlerState.pauseDecisionId ?: return@Column
   ```
3. Dialog actions:
   - Threshold pauses:
     - `Continue` -> `CrawlerSession.resumeCrawl(requestId, decisionId)`
     - `Stop and save` -> `CrawlerSession.stopAndSave(requestId, decisionId)`
   - External-package pauses:
     - `Continue outside package` -> `CrawlerSession.resumeCrawl(requestId, decisionId)`
     - `Skip edge` -> `CrawlerSession.skipExternalEdge(requestId, decisionId)`
4. Dialog body:
   - Show the pause reason text.
   - Show progress counters from the saved snapshot.
   - For external-package pauses, show the current package, next package, and triggering label.

### Phase 3.6 - Tests for PR 3

Status: Completed on 2026-04-21

1. **`DeepCrawlCoordinatorTest.kt`**
   - `pauseCheckpoint_fires_on_elapsed_time_and_continues_after_user_approval`
   - `pauseCheckpoint_fires_on_failed_edge_count_and_continues_after_user_approval`
   - `pauseCheckpoint_stops_and_saves_partial_when_user_chooses_stop`
   - `pauseCheckpoint_rolls_forward_only_the_triggered_budget`
   - `externalPackageDecision_skips_edge_when_user_selects_skip`
   - `externalPackageDecision_continues_and_captures_cross_package_child_when_user_selects_continue`
   - `externalPackageDecision_replays_through_recorded_package_context`
2. **`PauseCheckpointTrackerTest.kt`** (new)
   - `nextTriggeredReason_returns_elapsed_time_when_time_budget_exceeded`
   - `nextTriggeredReason_returns_failed_edge_count_when_edge_budget_exceeded`
   - `rollForwardAfterContinue_advances_only_the_relevant_budget`
3. **`CrawlerSessionTest.kt`**
   - `pauseForDecision_switches_phase_and_awaits_decision`
   - `stopAndSave_completes_deferred_with_stop`
   - `skipExternalEdge_completes_deferred_with_skip_edge`
   - `stale_pause_decision_id_is_ignored`

### Success Criteria - Block 2

#### Automated

- [x] `./gradlew test --tests "com.example.apptohtml.crawler.PauseCheckpointTrackerTest"` passes.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest"` passes with the checkpoint and package-boundary cases.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.CrawlerSessionTest"` passes with decision-token coverage.
- [x] `./gradlew test` passes.

#### Manual

- [x] Run a deep crawl with a temporary low checkpoint threshold and confirm the app returns to AppToHTML only after the latest `crawl-index.json` has been updated.
- [x] Confirm `Continue` resumes from the pause and `Stop and save` produces a partial-abort summary.
- [x] Run a crawl against an app that opens an external package and confirm `Skip edge` records `skipped_external_package`, while `Continue outside package` captures the external child and allows replay through that package on later frontier restores.

---

## Block 3 - Desktop Graph Export (workstream E)

Status as of 2026-04-21:

- Completed: Phase 4.1
- Completed: Phase 4.2
- Completed: Phase 4.3
- Completed: Phase 4.4
- Completed: Phase 4.5
- Completed: all automated success criteria for Block 3
- Completed: the manual verification checks below

### Phase 4.1 - Graph data model and JSON serializer

Status: Completed on 2026-04-21

#### New files

1. **`app/src/main/java/com/example/apptohtml/crawler/CrawlGraph.kt`**
   ```kotlin
   data class CrawlGraphNode(
       val screenId: String,
       val screenName: String,
       val fingerprint: String,
       val packageName: String,
       val depth: Int,
       val discoveryIndex: Int,
       val htmlFileName: String?,
       val xmlFileName: String?,
       val mergedXmlFileName: String?,
   )

   data class CrawlGraphEdge(
       val edgeId: String,
       val fromScreenId: String,
       val toScreenId: String?,
       val label: String,
       val status: CrawlEdgeStatus,
       val message: String?,
   )

   data class CrawlGraph(
       val sessionId: String,
       val packageName: String,
       val generatedAtMs: Long,
       val rootScreenId: String?,
       val maxDepthReached: Int,
       val nodes: List<CrawlGraphNode>,
       val edges: List<CrawlGraphEdge>,
   )
   ```
2. **`CrawlGraphBuilder.kt`**
   - Build graph nodes from `manifest.screens`.
   - Store only artifact basenames, not absolute paths.
3. **`CrawlGraphJsonWriter.kt`**
   - Mirror the lightweight manual JSON-writing style already used by `CrawlManifestStore`.

### Phase 4.2 - Offline HTML viewer renderer

Status: Completed on 2026-04-21

#### New file

1. **`CrawlGraphHtmlRenderer.kt`**
   - Render a single self-contained HTML document.
   - Inline CSS and JS only; no remote assets or network fetches.
   - Inline serialized graph data in a `<script type="application/json">` tag.
   - Layout nodes by depth column and discovery-order row.
   - Style edges by `CrawlEdgeStatus`:
     - `captured`: solid blue
     - `linked_existing`: dashed blue
     - `skipped_blacklist`: dotted gray
     - `skipped_no_navigation`: thin dotted gray
     - `skipped_external_package`: dashed orange
     - `failed`: solid red
   - Support pan, wheel zoom, status filtering, and neighbor highlighting.

### Phase 4.3 - Incremental emission

Status: Completed on 2026-04-21

#### Changes

1. **`CrawlManifestStore.kt` / `CrawlSessionDirectory`**
   - Add `graphJsonFile: File` and `graphHtmlFile: File`.
2. **`CaptureFileStore.kt`**
   - Initialize those files in `createSession(...)`.
   - Add `saveGraph(session, graph)` to write both graph artifacts.
3. **`DeepCrawlCoordinator.kt`**
   - Extend the private `saveManifest(...)` helper so it:
     - builds one manifest snapshot with a single timestamp,
     - writes that manifest,
     - builds the graph from the same manifest snapshot,
     - writes `crawl-graph.json` and `crawl-graph.html`.
   - Keep the change centralized in the helper so every existing save site picks it up automatically.

### Phase 4.4 - Surface graph paths in the summary and UI

Status: Completed on 2026-04-21

#### Changes

1. **`CrawlerModels.kt`**
   - Extend `CrawlRunSummary` with `graphJsonPath` and `graphHtmlPath`.
   - Extend `CrawlerUiState` with nullable graph-path fields.
2. **`DeepCrawlCoordinator.buildSummary(...)`**
   - Populate the graph paths from `session.graphJsonFile` and `session.graphHtmlFile`.
3. **`MainActivity.kt`**
   - Render the graph JSON and graph viewer paths in both the captured and aborted branches.
   - Keep `maxDepthReached` visible in the final summary.

### Phase 4.5 - Tests for PR 4

Status: Completed on 2026-04-21

1. **`CrawlGraphBuilderTest.kt`** (new)
   - `build_preserves_node_order_by_screen_record_index`
   - `build_maps_edges_one_to_one_from_manifest`
   - `build_uses_file_basenames_not_absolute_paths`
2. **`CrawlGraphJsonWriterTest.kt`** (new)
   - `write_produces_valid_parseable_json_with_all_node_and_edge_fields`
   - `write_escapes_quote_and_newline_characters_in_labels`
3. **`CrawlGraphHtmlRendererTest.kt`** (new)
   - `render_inlines_json_in_script_tag`
   - `render_has_no_http_or_https_references`
   - `render_includes_status_filter_checkbox_for_every_crawl_edge_status`
4. **`DeepCrawlCoordinatorTest.kt`**
   - Extend the deep-chain traversal test to assert graph artifacts exist and contain the expected screen IDs.
   - Add `bfsTraversal_emits_graph_on_every_manifest_save`.

### Success Criteria - Block 3

#### Automated

- [x] `./gradlew test --tests "com.example.apptohtml.crawler.CrawlGraphBuilderTest"` passes.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.CrawlGraphJsonWriterTest"` passes.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.CrawlGraphHtmlRendererTest"` passes.
- [x] `./gradlew test --tests "com.example.apptohtml.crawler.DeepCrawlCoordinatorTest"` passes with graph assertions.
- [x] `./gradlew test` passes.

#### Manual

- [x] Run a crawl, copy the session directory to a desktop, open `crawl-graph.html` with networking disabled, and confirm the graph renders and remains fully interactive offline.
- [x] Confirm node links open the sibling HTML artifacts from the same folder.
- [x] Confirm filtering and hover highlighting behave deterministically across repeated opens.

---

## Documentation Updates

- **Block 1**
  - Update `documentation/crawler-module.md` and `documentation/data-and-state.md` for the new `editable` field and `skipEditable` config.
  - Remove remaining "one-layer" terminology.
- **Block 2**
  - Add `documentation/pause-resume-flow.md` describing:
    - checkpoint thresholds,
    - persistence-before-prompt behavior,
    - the decision-token protocol between `CrawlerSession`, `MainActivity`, and `DeepCrawlCoordinator`,
    - package-aware replay metadata.
- **Block 3**
  - Add `documentation/crawl-graph-export.md` describing the JSON shape, offline viewer controls, and desktop usage.
  - Update `documentation/modules.md` to list `DeepCrawlCoordinator`, `CrawlGraphBuilder`, `CrawlGraphJsonWriter`, and `CrawlGraphHtmlRenderer`.
- **Checklist maintenance**
  - As each block is completed on the branch, mark the corresponding lines in `documentation/deep-crawl-graph-plan.md` as complete, but keep the branch and PR open until the entire feature set is finished.

## Risks and Mitigations

- **Editable false positives** - some apps expose container nodes as editable. Defaulting `skipEditable = true` still matches the safety posture, and the JSON flag preserves an escape hatch for targeted runs.
- **Checkpoint persistence gaps** - if prompting happens before state is saved, a killed service can strand the UI with stale progress. This revision explicitly saves before every pause prompt.
- **Stale dialog actions** - a single crawl can pause more than once, so `requestId` alone is unsafe. The new `pauseDecisionId` prevents old button taps from resolving a newer pause.
- **Budget rollover drift** - advancing both budgets after every continue could unintentionally delay the other warning type. The tracker now rolls forward only the budget that actually triggered.
- **Cross-package continuation is invasive** - replay, restore, and manifest expectations all need package-aware data once traversal leaves the originally selected app.
- **Offline viewer size** - inlining the graph JSON will grow the HTML file on large crawls, but this is still acceptable for a desktop-only artifact. If it becomes painful, a later change can split JSON and viewer while staying offline.

## Sequencing

1. Complete **Block 1** first. It is small, isolated, and closes the remaining Safety Rules work without changing package-boundary behavior yet.
2. Complete **Block 2** next. It is the riskiest slice because it crosses coordinator, session, service, and UI state.
3. Complete **Block 3** after or partly alongside Block 2 on the same branch if that proves convenient. It is mostly additive and should stay low-conflict with the pause/resume work.
4. Keep the current PR open across all three blocks, updating its description/checklist as implementation progresses.
5. Merge to `main` only after all blocks, verification, and follow-up documentation are complete.
