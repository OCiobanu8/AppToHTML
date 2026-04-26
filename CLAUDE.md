# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Philosophy

This service is not deployed in production. There are no backward compatibility requirements — make the best long-term architectural decisions without being constrained by existing interfaces or data formats.

## What This Project Does

AppToHTML is an Android app that uses AccessibilityService to capture a target app's UI as merged HTML and XML. It scrolls through screens, deduplicates merged elements, and optionally follows clickable elements one level deep ("deep crawl") to capture child screens.

## Build & Test Commands

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.apptohtml.CrawlerSessionTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean
./gradlew clean
```

## Architecture

The project is organized into 9 modules described in `documentation/modules.md`. The key data flow:

1. **User selects a target app** → `SelectedAppRepository` (DataStore Preferences) persists the choice
2. **Capture starts** → `AppLaunchHelper` launches the target app; `AppToHtmlAccessibilityService` waits for the window
3. **Scroll scan** → `ScrollScanCoordinator` scrolls down, calling `AccessibilityTreeSnapshotter` at each scroll stop to snapshot `AccessibilityNodeInfo` into serializable `AccessibilityNodeSnapshot` trees. Viewports are merged by deduplicating on label + resource ID + class name + list-item flag
4. **Deep crawl (optional)** → `DeepCrawlCoordinator` passes candidate `PressableElement`s to `TraversalPlanner`, which filters via `CrawlBlacklist`. `PathReplayResolver` replays the path to each screen. `CrawlRunTracker` tracks state
5. **Export** → `HtmlRenderer` + `AccessibilityXmlSerializer` write to per-session directories managed by `CaptureFileStore`. `CrawlManifestStore` writes the crawl index JSON
6. **Session state** → `CrawlerSession` holds in-memory `StateFlow` with states: `IDLE → LAUNCHING → WAITING → SCANNING → TRAVERSING → CAPTURED/ABORTED/FAILED`

### Core Domain Models

- `AccessibilityNodeSnapshot` — serializable tree node (replaces live `AccessibilityNodeInfo`)
- `ScreenSnapshot` — full merged screen with its list of `PressableElement`s
- `PressableElement` — detected clickable element with path for replay

### Design Constraints

- **Crawl sessions are intentionally not persisted** — only `SelectedAppRef` (the chosen target app) is stored
- Unit tests use synthetic `AccessibilityNodeSnapshot` trees; no mocking of Android framework classes
- `CrawlBlacklist` guards against navigating dangerous system elements (back buttons, system UI)
- Capture timeout: 15 s; scroll debounce: 350 ms

## Key Source Locations

| Concern | Location |
|---|---|
| UI (Compose) | `MainActivity.kt` |
| A11y service entry point | `AppToHtmlAccessibilityService.kt` |
| Session state machine | `CrawlerSession.kt` |
| Scroll + merge logic | `ScrollScanCoordinator.kt` |
| Deep crawl orchestration | `DeepCrawlCoordinator.kt` |
| Element safety filtering | `CrawlBlacklist.kt`, `TraversalPlanner.kt` |
| HTML/XML export | `HtmlRenderer.kt`, `AccessibilityXmlSerializer.kt` |
| Screen name heuristics | `ScreenNaming.kt` |
| Diagnostics | `DiagnosticLogger.kt` |

All source lives under `app/src/main/java/com/example/apptohtml/`.

## Documentation

`documentation/` contains detailed module breakdowns, crawler flow diagrams, and investigation notes:
- `modules.md` — module responsibilities and boundaries
- `crawler-module.md` — merge strategy, scroll flow, known tradeoffs
- `data-and-state.md` — what is persisted vs. runtime-only
- `.drawio` files — editable architecture diagrams
