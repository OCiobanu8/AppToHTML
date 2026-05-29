# AGENTS.md

This file provides guidance to Codex and other coding agents when working with
code in this repository.

## Development Philosophy

This service is not deployed in production. There are no backward compatibility
requirements. Make the best long-term architectural decisions without being
constrained by existing interfaces or data formats.

## What This Project Does

AppToHTML is an Android app that uses `AccessibilityService` to launch a
selected target app, capture its UI as merged HTML and XML, and perform a safe
deep crawl across reachable in-app targets. The crawler scrolls through screens,
deduplicates merged elements, optionally follows clickable elements, pauses for
elapsed-time or failed-edge checkpoints, and asks before crossing external
package boundaries.

Each deep-crawl session writes per-screen artifacts plus crawl-level outputs:
`crawl-index.json`, `crawl-graph.json`, and a self-contained offline
`crawl-graph.html` viewer.

## Build & Test Commands

Unix-style shell examples:

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.apptohtml.crawler.CrawlerSessionTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean
./gradlew clean
```

PowerShell examples for this Windows workspace:

```powershell
# Build
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease

# Run unit tests
.\gradlew.bat test

# Run a single test class
.\gradlew.bat test --tests "com.example.apptohtml.crawler.CrawlerSessionTest"

# Run instrumented tests (requires connected device/emulator)
.\gradlew.bat connectedAndroidTest

# Clean
.\gradlew.bat clean
```

Prefer targeted unit test runs while iterating. Useful targets include:

- `com.example.apptohtml.crawler.CrawlerTraversalTest`
- `com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`
- `com.example.apptohtml.crawler.PathReplayResolverTest`
- `com.example.apptohtml.crawler.PauseCheckpointTrackerTest`
- `com.example.apptohtml.crawler.CrawlGraphBuilderTest`
- `com.example.apptohtml.crawler.CrawlGraphJsonWriterTest`
- `com.example.apptohtml.crawler.CrawlGraphHtmlRendererTest`
- `com.example.apptohtml.SelectedAppRefCodecTest`

## Architecture

The project is organized into 9 modules described in
`documentation/modules.md`. The key data flow:

1. User selects a target app -> `SelectedAppRepository` (DataStore Preferences)
   persists the choice.
2. Capture starts -> `AppLaunchHelper` launches the target app;
   `AppToHtmlAccessibilityService` waits for the window.
3. Scroll scan -> `ScrollScanCoordinator` rewinds to the entry surface, scrolls
   through viewports, and calls `AccessibilityTreeSnapshotter` to snapshot
   `AccessibilityNodeInfo` into serializable `AccessibilityNodeSnapshot` trees.
4. Deep crawl -> `DeepCrawlCoordinator` passes candidate `PressableElement`s to
   `TraversalPlanner`, filters via `CrawlBlacklist`, and uses
   `PathReplayResolver` to replay routes breadth-first.
5. Pause and boundary handling -> `PauseCheckpointTracker` and
   `CrawlerSession` coordinate elapsed-time checkpoints, failed-edge
   checkpoints, and external-package decisions.
6. Export -> `HtmlRenderer`, `AccessibilityXmlSerializer`, `CrawlManifestStore`,
   and graph renderers write per-session files managed by `CaptureFileStore`.
7. Session state -> `CrawlerSession` holds in-memory `StateFlow` with states:
   `IDLE -> LAUNCHING -> WAITING -> SCANNING -> TRAVERSING ->
   PAUSED_FOR_DECISION -> CAPTURED/ABORTED/FAILED`.

### Core Domain Models

- `AccessibilityNodeSnapshot` - serializable tree node that replaces live
  `AccessibilityNodeInfo`.
- `ScreenSnapshot` - full merged screen with its list of `PressableElement`s.
- `PressableElement` - detected clickable element with path and safety-relevant
  flags for replay.
- `CrawlGraph` - normalized session graph used by JSON and offline HTML graph
  exports.

### Design Constraints

- Crawl sessions are intentionally not persisted. Only `SelectedAppRef` (the
  chosen target app) is stored.
- Unit tests use synthetic `AccessibilityNodeSnapshot` trees; do not mock
  Android framework classes.
- `CrawlBlacklist` guards against navigating dangerous system elements, such as
  back buttons and system UI.
- Capture timeout: 15 s; scroll debounce: 350 ms.
- Graph artifacts should use sibling artifact basenames, not absolute paths, so
  exported session folders remain portable.

## Agent Workflow Notes

- Treat `documentation/` as part of the implementation. Update it when behavior,
  data flow, artifacts, or module boundaries change.
- Prefer adding focused synthetic snapshot tests for crawler behavior instead of
  introducing Android framework mocks.
- Inspect `DiagnosticLogger` output, `crawl.log`, manifests, and graph artifacts
  before changing crawler heuristics for live-device behavior.
- Keep Android framework entrypoints thin. Move deterministic crawler logic into
  domain classes under `crawler/`.
- Avoid persisting crawl session state unless there is a clear product need.
  Persist user intent and output artifacts instead.
- Keep blacklist-backed safety flags explicit in models and manifests so risky
  element handling remains auditable.

## Key Source Locations

| Concern | Location |
|---|---|
| UI (Compose) | `MainActivity.kt` |
| Accessibility service entry point | `AppToHtmlAccessibilityService.kt` |
| Application bootstrap | `AppToHtmlApplication.kt` |
| Capture generation gate | `WaitingCaptureGenerationGate.kt` |
| Session state machine | `crawler/CrawlerSession.kt` |
| Scroll + merge logic | `crawler/ScrollScanCoordinator.kt` |
| Deep crawl orchestration | `crawler/DeepCrawlCoordinator.kt` |
| Route replay | `crawler/PathReplayResolver.kt` |
| Pause checkpoints | `crawler/PauseCheckpointTracker.kt`, `crawler/PauseCheckpointConfig.kt` |
| App launch/navigation | `crawler/AppLaunchHelper.kt`, `crawler/AppToHtmlNavigator.kt` |
| Element safety filtering | `crawler/CrawlBlacklist.kt`, `crawler/TraversalPlanner.kt` |
| HTML/XML export | `crawler/HtmlRenderer.kt`, `crawler/AccessibilityXmlSerializer.kt` |
| Manifest storage | `crawler/CrawlManifestStore.kt` |
| Graph export | `crawler/CrawlGraphBuilder.kt`, `crawler/CrawlGraphJsonWriter.kt`, `crawler/CrawlGraphHtmlRenderer.kt` |
| Screen name heuristics | `crawler/ScreenNaming.kt` |
| Diagnostics | `diagnostics/DiagnosticLogger.kt`, `crawler/CrawlLogger.kt` |

All source lives under `app/src/main/java/com/example/apptohtml/`.

## Documentation

`documentation/` contains detailed module breakdowns, crawler flow diagrams, and
investigation notes:

- `modules.md` - module responsibilities and boundaries
- `crawler-module.md` - merge strategy, scroll flow, known tradeoffs
- `data-and-state.md` - what is persisted vs. runtime-only
- `.drawio` files - editable architecture diagrams
