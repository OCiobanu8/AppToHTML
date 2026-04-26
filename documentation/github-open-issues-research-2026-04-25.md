# GitHub Open Issues Research

Date: 2026-04-25
Repository: OCiobanu8/AppToHTML
Branch inspected: codex/deep-crawl-graph

## Scope

This document researches the current codebase state behind these open GitHub issues:

- Issue #2: https://github.com/OCiobanu8/AppToHTML/issues/2
- Issue #3: https://github.com/OCiobanu8/AppToHTML/issues/3
- Issue #4: https://github.com/OCiobanu8/AppToHTML/issues/4

The goal is to map what exists today, where each issue touches the code, how the issues overlap, and which test surfaces currently cover or miss the behavior. This is research, not an implementation plan.

## High-Level Current State

The crawler is currently a deep crawl engine that starts a new timestamped crawl session, captures a graph of screens, persists per-screen HTML/XML artifacts, writes `crawl-index.json`, writes graph exports, and supports live pause decisions while a crawl is in progress.

The code does not currently model saved-crawl resume as a product flow. `Resume` in the codebase is currently live pause continuation through `CrawlerSession.resumeCrawl(...)`, not "resume from a previously saved screen." There is no manifest reader, no persisted `lastVisitedScreenId`, no screen picker model, and no backend `ResumeFromScreen(screenId)` equivalent.

The three issues all meet around the same persistent crawl model:

- Issue #2 concerns correctness when continuing into an external package after AppToHTML has foregrounded itself for the decision UI.
- Issue #3 concerns canonical per-screen identifiers and artifact names.
- Issue #4 concerns treating a crawl as one saved app-level graph that can be replaced or resumed from any existing screen.

## Current Crawl Flow

The accessibility service constructs `DeepCrawlCoordinator` and injects host callbacks plus a `createSession` factory. The factory currently delegates directly to `CaptureFileStore.createSession(...)` with the target package and crawl start time (`AppToHtmlAccessibilityService.kt:213-219`).

`DeepCrawlCoordinator.crawl(...)` always creates a fresh tracker and fresh session at crawl start (`DeepCrawlCoordinator.kt:35-43`). It restores/relaunches the target app entry screen, scans the root, creates `screen_000`, saves root artifacts, adds the root record, then processes a breadth-first frontier (`DeepCrawlCoordinator.kt:70-128`).

Root and child screens are tracked through `CrawlRunTracker`. The tracker owns in-memory `screens`, `edges`, fingerprint deduplication, `rootScreenId`, and sequence counters (`CrawlRunTracker.kt:3-15`). It can build a manifest, but it has no constructor or loader from an existing manifest (`CrawlRunTracker.kt:74-89`).

Manifest persistence is write-only today. `CrawlManifestStore.write(...)` and `toJson(...)` serialize the in-memory manifest (`CrawlManifestStore.kt:16-19`). There is no read API or JSON parser for restoring a saved crawl.

The manifest model stores session metadata, root screen ID, max depth, screens, and edges (`CrawlerModels.kt:446-456`). Screen records already contain several fields useful for resume selection: `screenId`, `screenName`, `packageName`, artifact paths, `parentScreenId`, trigger metadata, route, and depth (`CrawlerModels.kt:416-430`). It does not contain last-visited state, child-subtree validity metadata, or a per-screen "last visited" marker.

## Issue #2: External-Package Continue Resumes From AppToHTML Foreground

### Issue Summary

Issue #2 says that when the crawler pauses at an external-package boundary and the user taps `Continue outside package`, the coordinator can continue scanning while AppToHTML is foregrounded rather than the external destination screen.

The issue comment points to:

- `DeepCrawlCoordinator.expandScreen`
- `CrawlerSession.pauseForDecision`

### Current Code Path

When expanding a screen, the coordinator restores the parent screen, scrolls to the target element, clicks it, then captures the immediate child root with `expectedPackageName = null` (`DeepCrawlCoordinator.kt:300-367`).

It computes:

- `currentPackageName` from the parent screen record.
- `childPackageName` from the captured child root or current package.
- an after-click fingerprint for no-navigation detection.

If `childPackageName != currentPackageName`, the coordinator builds an `ExternalPackageDecisionContext`, saves the manifest, logs the pause, and calls `host.awaitPauseDecision(...)` (`DeepCrawlCoordinator.kt:402-429`).

For `PauseDecision.CONTINUE`, the current implementation only logs the decision (`DeepCrawlCoordinator.kt:431-437`). It does not restore, relaunch, or replay back to the external child destination before continuing.

Immediately after the pause decision block, the coordinator calls `scanCurrentScreen(...)` using the earlier `childInitialRoot` (`DeepCrawlCoordinator.kt:479-485`). This is the core mismatch: the initial root may describe the external package, while live host callbacks inside scanning may now observe AppToHTML.

The reason AppToHTML may be foregrounded is explicit. `CrawlerSession.pauseForDecision(...)` sets the paused UI state, then calls `returnToApp()` before awaiting the deferred decision (`CrawlerSession.kt:145-183`). That is the right behavior for showing the in-app decision UI, but it means the crawler thread resumes after the decision while AppToHTML may still be the active window.

### Live Capture Risk

`scanCurrentScreen(...)` can use the supplied root but also delegates to live host operations during the real scan path. Those operations include forward/backward scrolling and current-root capture. The issue is therefore not only "the wrong root object is passed." It is that subsequent live callbacks can observe the wrong foreground app.

This is especially important for scrollable external screens. The scanner can begin with a stale external `childInitialRoot`, then merge scroll artifacts or viewport roots from AppToHTML, producing inconsistent HTML/XML output.

### Existing Tests

`DeepCrawlCoordinatorTest.externalPackageDecision_continues_and_captures_cross_package_child_when_user_selects_continue` covers the happy path where the pause decision returns `CONTINUE` and the external package child is captured (`DeepCrawlCoordinatorTest.kt:465-518`).

`DeepCrawlCoordinatorTest.externalPackageDecision_replays_through_recorded_package_context` verifies that the route records external package context and later captures expect the external package (`DeepCrawlCoordinatorTest.kt:521-578`).

Those tests use `scanScreenOverride = { _, initialRoot, _, _ -> host.snapshotForRoot(initialRoot) }` in the test coordinator (`DeepCrawlCoordinatorTest.kt:1041-1043`). That means they do not exercise the real scanner's live callbacks after a pause decision. This matches the issue: tests can pass while the real app still scans from the wrong foreground surface.

### Current Acceptance-State Assessment

Current code detects the boundary, pauses, supports skip/stop/continue decisions, and records `expectedPackageName` in the child route after capture. Current code does not perform a post-continue foreground restoration before scanning the external child.

The current test suite has coverage for skip, continue, and package-aware replay metadata, but not for the foreground mismatch caused by `CrawlerSession.pauseForDecision()` returning AppToHTML to the foreground.

## Issue #3: Screen-ID-Based Artifact Filenames

### Issue Summary

Issue #3 requests replacing `root` / `child` artifact filenames with canonical screen-ID filenames:

- Current examples: `000_root_settings_homepage.html`, `001_child_network_internet.html`
- Desired examples: `screen_00000_settings_homepage.html`, `screen_00001_network_internet.html`

It also requests changing screen IDs from 3 digits to 5 digits.

### Current Code Path

Screen IDs are generated in `DeepCrawlCoordinator.screenIdFor(...)` as 3-digit IDs: `screen_%03d` (`DeepCrawlCoordinator.kt:1104-1106`).

Root screen artifacts are saved with `screenPrefix = "root"` (`DeepCrawlCoordinator.kt:87-91`).

Child screen artifacts are saved with `screenPrefix = "child"` (`DeepCrawlCoordinator.kt:546-551`).

`CaptureFileStore.saveScreen(...)` currently requires `sequenceNumber` and `screenPrefix`, then constructs a 3-digit filename with prefix and slug: `%03d_%s_%s` (`CaptureFileStore.kt:47-59`). That produces names like `000_root_screen_a.html` and `001_child_screen_b.html`.

HTML links between captured screens are filename-based. When a child screen is captured, the parent resolved-link map stores `childFiles.htmlFile.name` (`DeepCrawlCoordinator.kt:540-542` and subsequent child capture flow). `CaptureFileStore.rewriteScreenHtml(...)` then rerenders the parent HTML with the resolved relative filename (`CaptureFileStore.kt:77-83`). The graph builder also derives node artifact filenames from persisted absolute artifact paths (`CrawlGraphBuilder.kt:8-24`).

### Export Surfaces Affected

The filename model reaches several outputs:

- Per-screen `.html`, `.xml`, and `_merged_accessibility.xml` files from `CaptureFileStore.saveScreen(...)`.
- Parent HTML links from `HtmlRenderer.render(...)`, where `resolvedChildLinks` becomes the anchor `href` (`HtmlRenderer.kt:53-78`).
- `crawl-index.json`, because `CrawlManifestStore.toJson(...)` writes absolute `htmlPath`, `xmlPath`, and `mergedXmlPath` (`CrawlManifestStore.kt:33-40`).
- `crawl-graph.json`, because `CrawlGraphBuilder` extracts basenames into graph nodes (`CrawlGraphBuilder.kt:8-24`).
- `crawl-graph.html`, because the graph renderer links to node HTML/XML filenames (`CrawlGraphHtmlRenderer.kt:798-819` and `CrawlGraphHtmlRenderer.kt:916`).

### Existing Tests

Current graph tests are anchored to the old ID and filename shape. `CrawlGraphJsonWriterTest` uses `screen_000` IDs and `000_home.html` style filenames (`CrawlGraphJsonWriterTest.kt:16-59`). `CrawlGraphHtmlRendererTest` similarly expects `screen_000` and `000_home.html` in the inline graph JSON (`CrawlGraphHtmlRendererTest.kt:8-18` and `CrawlGraphHtmlRendererTest.kt:36-75`).

`CrawlerExportTest.htmlRenderer_resolves_child_links_by_element_key` asserts a link to `001_child_details_screen.html` (`CrawlerExportTest.kt:347-369`).

`DeepCrawlCoordinatorTest.bfsTraversal_writes_crawl_log_with_frontier_and_link_entries` asserts `frontier_dequeue screenId=screen_000` (`DeepCrawlCoordinatorTest.kt:398-399`).

### Current Acceptance-State Assessment

Current code still uses 3-digit screen IDs and root/child artifact prefixes. The graph and HTML link pipeline already routes through manifest paths and file basenames, so the artifact rename is centralized enough to be tractable. Tests currently encode the old naming in multiple places and would need to move with the model.

## Issue #4: One Saved Crawl Per App and Unified Resume-From-Screen

### Issue Summary

Issue #4 asks for an app-level saved crawl model:

- one saved crawl per target app
- `New Crawl` replaces existing saved crawl data for that app
- `Resume Crawl` is available only when a saved crawl exists
- resume choices use a unified `ResumeFromScreen(screenId)` path
- "last visited" is just the first picker option using the same selected-screen flow
- resuming reloads manifest, replays route to the selected screen, rescans it, prunes stale descendants, and continues deeper

### Current Storage Behavior

`CaptureFileStore.createSession(...)` creates a base directory under `html/<packageName>`, then always creates a timestamped `crawl_yyyyMMdd_HHmmss` subdirectory. If a directory with that timestamp already exists, it appends a numeric suffix (`CaptureFileStore.kt:16-35`). This means multiple saved sessions per package are intentionally supported today.

The old single-capture `CaptureFileStore.save(...)` path does clear a package directory via `preparePackageDirectory(...)` (`CaptureFileStore.kt:101-124`). The deep-crawl session path does not call `preparePackageDirectory(...)`.

`preparePackageDirectory(...)` deletes direct children of a directory one by one (`CaptureFileStore.kt:126-137`). Because deep crawls store artifacts inside session subdirectories, any replacement logic for deep crawls must account for directories as well as files. The current helper calls `file.delete()`, which will not delete non-empty session directories.

### Current UI Behavior

The main UI currently has a single crawler action button: `Start Deep Crawl` (`MainActivity.kt:168-179`). It starts `CrawlerSession.startCapture(...)` for the selected app. There is no `New Crawl` / `Resume Crawl` split, no saved-crawl availability check, and no screen picker.

`canStartCapture` only checks selected app, accessibility enabled, and avoiding self-selection (`MainActivity.kt:83`). It does not inspect saved crawl state.

### Current Resume Meaning

`CrawlerSession.resumeCrawl(...)` currently resolves a live pause decision with `PauseDecision.CONTINUE` (`CrawlerSession.kt:186-194`). This is not saved-crawl resume. It takes a request ID and decision ID, not a saved screen ID.

The model type `CrawlerPhase.PAUSED_FOR_DECISION` and `CrawlerUiState.withResumedFromDecision()` are also tied to live pause decisions (`CrawlerModels.kt:1-16`, `CrawlerModels.kt:148-159`). They do not represent saved resume choices.

### Current Manifest and Route Fitness for Saved Resume

The manifest already contains useful route information. Each `CrawlScreenRecord` stores a `CrawlRoute`, and each route step stores click path, bounds, resource ID, class name, label, checkable state, editable state, first seen scroll step, and `expectedPackageName` (`CrawlerModels.kt:277-313`, `CrawlerModels.kt:416-430`). The manifest writer serializes route steps including `expectedPackageName` (`CrawlManifestStore.kt:44-58`).

The coordinator already knows how to replay a route from entry screen to a known screen. `prepareScreenForExpansion(...)` calls `replayRouteToScreen(...)`, then rescans and validates the screen fingerprint (`DeepCrawlCoordinator.kt:709-760`). `restoreLiveScreenForEdge(...)` also uses route replay when expanding non-root screens (`DeepCrawlCoordinator.kt:804-830`). `replayRouteToScreen(...)` restores entry, iterates route steps, scrolls to the recorded first-seen step, clicks the element, and captures the next root with the route step's expected package (`DeepCrawlCoordinator.kt:833-947`).

That means much of the route-replay machinery needed for resume-from-screen exists, but it is private to a fresh crawl run and tied to the in-memory `CrawlRunTracker`.

### Missing Saved Resume Pieces

Current code does not provide:

- a manifest reader or model migration path from existing JSON
- a way to initialize `CrawlRunTracker` from saved screens and edges
- a persisted `lastVisitedScreenId`
- a saved-crawl repository or discovery API per package
- a UI model for saved screen list entries
- a `New Crawl` replacement flow for deep crawl session directories
- a `ResumeFromScreen(screenId)` command path
- child-subtree pruning when a selected screen is refreshed
- route replay entry points exposed for a saved manifest rather than only current in-memory records

### Current Acceptance-State Assessment

Issue #4 is mostly not implemented yet. The codebase has strong ingredients for it: route metadata, graph records, replay logic, and manifest writing. The missing layer is saved-crawl lifecycle and resume orchestration.

## Cross-Issue Intersections

### Screen IDs Are Becoming Product API

Issue #3 and Issue #4 both make screen IDs more important. Today, `screenId` is already used in manifests, graph nodes, graph edges, logs, and in-memory frontier management. Issue #4 would expose screen IDs to UI resume choices and persisted saved-crawl commands. Issue #3 would also put the screen ID into artifact filenames.

This means the ID format change from `screen_%03d` to `screen_%05d` is not cosmetic. It affects persisted references, tests, graph output, logs, and future resume picker identity.

### Artifact Replacement and Resume Pruning Must Agree

Issue #4 says a resumed selected screen should be rescanned and its old child subtree replaced. Issue #3 says artifact filenames should represent canonical screen nodes. Those requirements interact:

- If a resumed screen keeps the same `screenId`, its artifact filenames should remain stable and be overwritten for the refreshed screen.
- If descendants are pruned, their old artifact files and graph/manifest records need to be removed or made unreachable in a clearly defined way.
- Parent HTML links and graph node links need to point to the refreshed filenames after pruning and recrawling.

The current code only appends new screens to an in-memory tracker and rewrites parent HTML links as edges resolve. It does not currently remove screen records, remove edges, or delete old artifact files for a subtree.

### External Packages Affect Resume Routes

Issue #2 and Issue #4 intersect through route replay. Current route steps already persist `expectedPackageName`, and the replay function uses that package when capturing each next root (`DeepCrawlCoordinator.kt:919-924`). That is useful for saved resume through external package routes.

However, Issue #2 shows that a route or root snapshot is not enough if AppToHTML is foregrounded at decision time. Saved resume from an external screen will need the same foreground correctness as live continue: before scanning a selected external-package screen, live host callbacks must be observing that external screen.

### One Saved Crawl Per App Changes Session Semantics

Issue #4's "one saved crawl per target app" conflicts with current `createSession` semantics. Today, `sessionId` is timestamped and maps directly to a directory. Graph and manifest include that session ID. If there is only one saved crawl per package, the code needs a stable app-level crawl location or a replacement strategy that deletes the prior timestamped session before creating a new one.

Issue #3's artifact filenames are per-screen and do not include session IDs. That aligns with a stable single saved crawl, but current paths still live under timestamped session directories.

## Test Surface Summary

Existing coverage that helps:

- BFS graph traversal, screen/edge manifest creation, cycle linking, graph export presence (`DeepCrawlCoordinatorTest.kt:1-100` area).
- External package skip and continue decisions (`DeepCrawlCoordinatorTest.kt:408-518`).
- Package-aware route replay through external screens (`DeepCrawlCoordinatorTest.kt:521-578`).
- HTML resolved child links (`CrawlerExportTest.kt:347-369`).
- Graph JSON and graph HTML artifact filenames (`CrawlGraphJsonWriterTest.kt`, `CrawlGraphHtmlRendererTest.kt`).
- Package directory cleanup helper for direct files (`CrawlerExportTest.kt:1279-1290`).

Important missing coverage relative to the issues:

- No test fails when `PauseDecision.CONTINUE` resumes while the live foreground package is AppToHTML.
- No test exercises real `scanCurrentScreen(...)` callbacks after external-package continue because coordinator tests use `scanScreenOverride`.
- No test asserts 5-digit screen IDs.
- No test asserts root/child-free artifact filenames.
- No test covers manifest readback, because there is no manifest read API.
- No test covers single saved crawl replacement for deep crawl session directories.
- No test covers saved resume UI choices or duplicate-free last visited ordering.
- No test covers pruning a refreshed screen's descendant subtree.

## Current-State File Map

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
  - wires accessibility host callbacks into `DeepCrawlCoordinator`
  - currently creates new deep crawl sessions through `CaptureFileStore.createSession(...)`

- `app/src/main/java/com/example/apptohtml/MainActivity.kt`
  - exposes selected app, accessibility readiness, and one `Start Deep Crawl` button
  - contains live pause decision UI
  - does not expose saved crawl discovery or resume picker UI

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt`
  - owns current in-memory UI/session state and live pause decisions
  - returns AppToHTML to foreground when pausing for decisions
  - `resumeCrawl(...)` means "continue live pause", not saved crawl resume

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
  - owns crawl start, BFS frontier, route replay, edge expansion, external boundary decisions, screen ID creation, manifest saves
  - currently logs external continue but does not restore the external destination after AppToHTML decision UI
  - currently creates `screen_%03d` IDs

- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt`
  - owns session directory creation, screen artifact names, manifest save, graph save
  - currently creates timestamped session directories under `html/<packageName>`
  - currently names deep crawl artifacts as `%03d_<root|child>_<slug>`

- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
  - owns in-memory screens, edges, fingerprint map, root screen ID, and sequence counters
  - cannot currently hydrate from a saved manifest

- `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt`
  - writes manifest JSON only
  - no manifest read API

- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphBuilder.kt`
  - turns manifest records into graph nodes and basenames for graph artifact links

## Bottom Line

Issue #2 is a focused correctness bug in the live external-package pause/continue path. The boundary is detected and represented, but continuing does not restore the external destination before scanning.

Issue #3 is a naming-model migration. The current ID and artifact conventions are still the old 3-digit root/child model, and several tests encode that model.

Issue #4 is a larger saved-crawl lifecycle feature. The existing manifest, route metadata, and route replay logic are useful foundations, but current code has no saved manifest reader, no single-crawl repository, no last-visited persistence, no screen picker, and no subtree replacement behavior.

The issues are coupled enough that the screen ID and saved-crawl model should be researched together during implementation. In particular, `screenId` is becoming the canonical bridge between manifests, files, graph exports, UI screen selection, and resume commands.
