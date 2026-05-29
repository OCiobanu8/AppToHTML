# ENG-0010 Phase 7 Coordinator Resume Status

Date: 2026-05-19
Branch: `codex/issue-4-one-crawl-per-app`
Commit: `d7e09c206982c438a366c68b1ba3c9a6fa6cf266`
Plan: `thoughts/shared/plans/2026-05-19-ENG-0010-one-crawl-per-app.md`

## Research Question

Check the implementation of phase 7, "Coordinator Resume Entry Point", and identify what is left.

## Summary

Phase 7 is partially implemented in the working tree, but it is not complete and does not currently compile.

Implemented pieces include the `CrawlStartIntent` and `ResumeMode` model types, a coordinator `crawl(...)` signature that accepts both, loading saved crawl XML state through `SavedCrawlLoader`, hydrating `CrawlRunTracker`, seeding durable external package approvals and resolved links, frontier seeding for all three resume modes, and `CrawlRunTracker.clearOutboundEdges(...)`.

The main remaining gaps are:

- `AppToHtmlAccessibilityService` still passes a one-argument `createSession` lambda to `DeepCrawlCoordinator`, but the coordinator now requires `(Long, Boolean) -> CrawlSessionDirectory`; `:app:compileDebugKotlin` fails on this mismatch.
- `CrawlerSession` has not gained a resume-aware `startCrawl(...)` or `startCapture(...)` API. It still only exposes `startCapture(context, selectedApp)`.
- `AppToHtmlAccessibilityService` still calls `coordinator.crawl(initialRoot, eventClassName)` with defaults and has no path to pass `NEW_CRAWL`, `ResumeFromScreen`, or `ReExpand`.
- `expandScreen(...)` is not idempotent for loaded `IN_PROGRESS` screens. It always calls `TraversalPlanner.planTraversal(...)` and creates fresh pending edges for all skipped and eligible elements, instead of processing only existing `PENDING` / `IN_PROGRESS` edges from the loaded tracker.
- `ReExpand` clears outbound edges and sets the target screen to `NOT_STARTED`, but does not rewrite the target XML immediately before expansion as the plan specifies.
- The planned `DeepCrawlCoordinatorResumeTest` class does not exist, and no tests reference `ResumeFromScreen`, `ReExpand`, `CrawlStartIntent`, or `resumeMode`.

## Detailed Findings

### Resume Types Exist

`CrawlStartIntent` and `ResumeMode` exist in `CrawlerModels.kt`:

- `CrawlStartIntent.NEW_CRAWL`
- `CrawlStartIntent.RESUME`
- `ResumeMode.ContinueAuto`
- `ResumeMode.ResumeFromScreen(screenId)`
- `ResumeMode.ReExpand(screenId)`

Reference: `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:441-449`.

### Coordinator Signature And Hydration Are Partially Wired

`DeepCrawlCoordinator.crawl(...)` accepts:

- `intent: CrawlStartIntent = CrawlStartIntent.RESUME`
- `resumeMode: ResumeMode = ResumeMode.ContinueAuto`

It derives `wipeExisting` from `NEW_CRAWL`, passes that into `createSession`, loads saved state with `SavedCrawlLoader.load(session.directory)`, hydrates `CrawlRunTracker.fromExistingState(...)`, seeds `allowedPackageNames` with saved allowed packages plus the selected app package, and restores `resolvedLinksByScreenId`.

References:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:36-89`
- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:19-160`

This is broadly aligned with the phase 7 load/hydrate requirement, though the plan specifically says to compute `CaptureFileStore.crawlDirectory(...)` at the top of `crawl(...)`; current code instead uses the directory returned by `createSession(...)`.

### Frontier Seeding Exists

`seedResumeFrontier(...)` implements the three planned modes:

- `ContinueAuto`: enqueue the single `IN_PROGRESS` screen first, then all `NOT_STARTED` screens sorted by ID.
- `ResumeFromScreen`: BFS through captured/linked children, enqueue the target and its `NOT_STARTED` descendants.
- `ReExpand`: validate target, clear outbound edges, mark target `NOT_STARTED`, enqueue target.

References:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:190-213`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:334-389`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:191-193`

The missing part is the immediate XML rewrite for `ReExpand`; the target XML is not rewritten at frontier seeding time.

### Existing Screen Expansion Is Not Resume-Aware

`expandScreen(...)` currently treats every screen as a fresh expansion:

- It always computes `TraversalPlanner.planTraversal(snapshot, blacklist)`.
- It always sets the screen to `IN_PROGRESS`.
- It creates new pending edges for every skipped element.
- It creates new pending edges for every eligible element.
- It then marks the screen `COMPLETE`.

References:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:393-453`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:882-884`

This does not implement the phase 7 requirement: when a loaded screen has `expansionStatus != NOT_STARTED`, replay/rescan the screen and resolve only existing outbound edges whose status is `PENDING` or `IN_PROGRESS`, marking missing live elements as `FAILED`.

Because of this, a resumed `IN_PROGRESS` screen can duplicate edges instead of continuing the saved edge lifecycle.

### CrawlerSession And Service Are Not Resume-Wired

`CrawlerSession` still exposes only `startCapture(context, selectedApp)` as the entry point for launching work. There is no `startCrawl(...)`, no `CrawlStartIntent` parameter, and no `ResumeMode` parameter.

Reference: `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt:42`.

`AppToHtmlAccessibilityService` constructs `DeepCrawlCoordinator` with `createSession = { startedAt -> ... }`, but `DeepCrawlCoordinator` now requires a two-argument function type `(Long, Boolean) -> CrawlSessionDirectory`.

References:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:155-223`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:13`

The service also calls `coordinator.crawl(initialRoot = initialRoot, eventClassName = eventClassName)` with no resume intent or mode, relying on defaults.

### Tests Are Missing

No `DeepCrawlCoordinatorResumeTest` file exists. Repository search found no test references to:

- `ResumeFromScreen`
- `ReExpand`
- `CrawlStartIntent`
- `resumeMode`

The existing `DeepCrawlCoordinatorTest` helper also still passes a one-argument `createSession` lambda.

References:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:2859-2874`
- `app/src/test/java/com/example/apptohtml/crawler/CrawlerSessionTest.kt`

## Verification

Command attempted:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest
```

Result: failed at `:app:compileDebugKotlin`.

Compiler error:

```text
AppToHtmlAccessibilityService.kt:212:33 Argument type mismatch:
actual type is 'Function1<Long, CrawlSessionDirectory>',
but 'Function2<Long, Boolean, CrawlSessionDirectory>' was expected.
```

The first attempt failed because Gradle needed to download its wrapper distribution and sandbox networking was blocked; rerunning with approved escalation downloaded Gradle and reached the Kotlin compile error above.

## What's Left For Phase 7

1. Fix production and test call sites for the new `createSession(startedAt, wipeExisting)` signature.
2. Decide where the UI/session intent should live now: either add the planned `CrawlerSession.startCrawl(..., intent, resumeMode)` path or extend `startCapture(...)` with those parameters and forward them through `AppToHtmlAccessibilityService`.
3. Pass `intent` and `resumeMode` from `CrawlerSession` through `AppToHtmlAccessibilityService` into `DeepCrawlCoordinator.crawl(...)`.
4. Add the resume-aware expansion branch for screens whose loaded `expansionStatus != NOT_STARTED`, processing only existing `PENDING` / `IN_PROGRESS` outbound edges.
5. Add immediate XML rewrite for `ReExpand` after clearing outbound edges and marking the screen `NOT_STARTED`.
6. Add `DeepCrawlCoordinatorResumeTest` coverage for `ContinueAuto`, `ResumeFromScreen`, `ReExpand`, loaded fingerprint dedup, and durable external-package approval reuse.
7. Re-run the phase 7 verification commands after the compile fix.

## Open Questions

- The current `crawl(...)` default intent is `RESUME`. That matches one-crawl-per-app semantics, but the existing UI still says "Start Deep Crawl"; phase 8 may decide whether the default button should continue, start new, or expose both.
- The plan describes orphan descendants from `ReExpand` as a diagnostic-only known shape. Current code does not log that diagnostic.
