# Application Modules

## Overview

AppToHTML is an Android app that launches a selected target app, observes its
accessibility tree, performs a safe deep crawl across reachable in-app targets,
and exports both per-screen artifacts and crawl-level graph artifacts. The
codebase is intentionally small, with most logic grouped by responsibility.

## Modules

### 1. Application bootstrap

**Main files**

- `app/src/main/java/com/example/apptohtml/AppToHtmlApplication.kt`

**Responsibility**

- Initializes app-wide services during process startup.
- Currently sets up the diagnostic logging pipeline.

**Notes**

- This module should stay light.
- Cross-cutting services that must exist before UI launch belong here.

### 2. UI and user flow

**Main files**

- `app/src/main/java/com/example/apptohtml/MainActivity.kt`

**Responsibility**

- Presents the Compose UI.
- Shows accessibility readiness.
- Lets the user pick the target app.
- Starts the crawler.
- Displays crawler progress and output paths.

**Dependencies**

- `AppDiscovery`
- `SelectedAppRepository`
- `CrawlerSession`

**Boundary**

- The UI reads state and triggers workflows.
- It should not contain crawler traversal logic.

### 3. App discovery

**Main files**

- `app/src/main/java/com/example/apptohtml/AppDiscovery.kt`

**Responsibility**

- Queries launchable apps from the package manager.
- Filters out AppToHTML itself.
- Checks whether the accessibility service is enabled.

**Boundary**

- This module is about Android platform discovery only.
- It should not own persistence or crawler execution.

### 4. Accessibility service and live capture orchestration

**Main files**

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt`
- `app/src/main/java/com/example/apptohtml/MainActivity.kt`

**Responsibility**

- Receives accessibility events while the target app is active.
- Coordinates crawl start, progress, pause, completion, and failure.
- Publishes pause metadata and graph artifact paths to the UI.
- Resolves user decisions for checkpoint pauses and external-package boundaries.

**Boundary**

- `AppToHtmlAccessibilityService` is the Android runtime entrypoint.
- `CrawlerSession` is the in-memory workflow state holder shared with the UI.

#### 4a. Live node actions

**Main file**

- `app/src/main/java/com/example/apptohtml/crawler/LiveNodeActions.kt`

Scroll and click gestures against a *live* accessibility tree, extracted out of the service so
both the crawl path and the snapshot path share one implementation and so the logic is unit
testable. The tree arrives as lambdas (the shape `PathReplayResolver` uses) and the action id
vocabulary (`LiveActionIds`) is injected rather than read from `AccessibilityNodeInfo`, because
`AccessibilityAction.ACTION_PAGE_DOWN` and friends resolve through framework statics that are not
available under the JVM unit-test runtime.

Two ordering rules in here are load-bearing and pinned by tests: `preferredActionIds` **appends**
preferred action ids the node never advertised rather than filtering them out (some views honor an
action they do not list), and path candidates are attempted deepest-first while fallback candidates
are attempted in score order.

#### 4b. Single-screen snapshot path

**Main files**

- `app/src/main/java/com/example/apptohtml/crawler/SnapshotCaptureCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/SnapshotFileStore.kt`
- `app/src/main/java/com/example/apptohtml/crawler/SnapshotCrawlState.kt`
- `app/src/main/java/com/example/apptohtml/crawler/SnapshotModels.kt`

**Responsibility**

- Captures the currently-visible screen on a broadcast
  (`com.example.apptohtml.CAPTURE_SCREEN`), without launching the target app, back-navigating to
  its entry screen, or bringing AppToHTML to the foreground.
- Rejects a capture while a crawl is active, and rejects our own UI or SystemUI in the foreground.
- Writes crawl-shaped artifacts into `snapshots/`, a sibling of `crawl/`.

**Boundary**

- The no-launch / no-back-navigation guarantee is **structural**: `SnapshotCaptureCoordinator.Host`
  exposes no method that could launch an app, press global back, or foreground this app. There is
  nothing to call, so the guarantee does not depend on a reviewer noticing. A test pins the exact
  member set, so adding one fails the build.
- Snapshot progress is published on `CrawlerSession.snapshotState`, a flow **separate** from
  `CrawlerUiState`. Folding it into `CrawlerPhase` would make the crawl state machine look busy and
  would make the coordinator's own "reject while a crawl is active" guard reject valid captures.
- The host-side trigger is the `capture-screen` skill under `.claude/skills/`.

### 5. Crawler domain logic

**Main files**

- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityTreeSnapshotter.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt`
- `app/src/main/java/com/example/apptohtml/crawler/PauseCheckpointTracker.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ClickFallbackMatcher.kt`
- `app/src/main/java/com/example/apptohtml/crawler/AppLaunchHelper.kt`
- `app/src/main/java/com/example/apptohtml/crawler/AppToHtmlNavigator.kt`

**Responsibility**

- Converts live accessibility nodes into stable snapshots.
- Detects pressable elements.
- Filters risky or unwanted targets before traversal.
- Ranks scrollable containers.
- Drives scroll scanning and route replay, validating each replay step against
  the screen identity it should reach.
- Verifies entry restore against an expected logical fingerprint before
  treating the current root as replay-ready.
- Settles clicked destinations and external-package boundaries.
- Gates click fallback candidates by meaningful eligibility (resource id,
  label, class plus bounds, or strong bounds for icon-only controls).
- Expands the breadth-first crawl frontier.
- Handles pause checkpoints and external-package boundaries.
- Names the captured screen.
- Launches the target app and returns to AppToHTML.

**Boundary**

- This is the core behavior layer.
- It should remain UI-agnostic and mostly deterministic where possible.

### 6. Export and file output

**Main files**

- `app/src/main/java/com/example/apptohtml/crawler/HtmlRenderer.kt`
- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphBuilder.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphJsonWriter.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphHtmlRenderer.kt`

**Responsibility**

- Renders merged HTML output.
- Builds synthetic XML output for scroll scans.
- Builds normalized graph data from crawl manifests.
- Writes capture and graph artifacts to app storage.
- Produces the offline desktop graph viewer.

**Boundary**

- Export formatting lives here.
- Capture traversal and Android event handling should stay outside this layer.

### 7. Persistence and data models

**Main files**

- `app/src/main/java/com/example/apptohtml/model/SelectedAppRef.kt`
- `app/src/main/java/com/example/apptohtml/storage/SelectedAppRepository.kt`
- `app/src/main/java/com/example/apptohtml/storage/SelectedAppRefCodec.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraph.kt`

**Responsibility**

- Defines the persisted target-app reference.
- Stores and restores the selected app from DataStore.
- Defines runtime crawl session, manifest, and graph models.

**Boundary**

- Only long-lived user selection state is stored here.
- Crawl session state is intentionally not persisted across process death, but crawl artifacts and manifests are persisted to files during execution.

### 8. Diagnostics

**Main files**

- `app/src/main/java/com/example/apptohtml/diagnostics/DiagnosticLogger.kt`

**Responsibility**

- Writes runtime logs and crash details to app-local files.
- Supports debugging crawler behavior on real devices.

**Boundary**

- Logging should remain side-effect focused.
- Business logic should not depend on diagnostics output.

### 9. Tests

**Main files**

- `app/src/test/java/com/example/apptohtml/crawler/CrawlerExportTest.kt`
- `app/src/test/java/com/example/apptohtml/SelectedAppRefCodecTest.kt`

**Responsibility**

- Covers naming, export, snapshot aggregation, and crawler stop conditions.
- Protects regression-prone logic in the crawler domain.

## Suggested evolution path

- Keep Android framework code thin and move logic into testable crawler classes.
- Expand crawler coverage with new modules before broadening `MainActivity`.
- Treat `documentation/` as part of the implementation, not an afterthought.
