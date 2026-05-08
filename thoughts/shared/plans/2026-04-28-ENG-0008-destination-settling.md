# Issue 8: Add Destination Settling Before External-Boundary Restore Validation

Date: 2026-04-28
Issue: https://github.com/OCiobanu8/AppToHTML/issues/8
Research: `thoughts/shared/research/2026-04-28-google-services-destination-settling.md`
Prior analysis: `documentation/crawl-20260428-google-services-continue-analysis.md`
Manual failure evidence: `E:\Logs\com.android.settings\crawl_20260428_151359`

## Overview

Fix the Google Services and Digital Wellbeing external-package crawl failures by introducing clicked-destination settling before the crawler decides that a post-click accessibility root is the authoritative child destination.

The current crawler treats the first changed post-click root as final. In the April 28 Settings crawl, that allowed a transitional `com.google.android.gms` snapshot containing only `More options` to become the expected external destination. After the user chose Continue, replay reached the same package but captured the fuller Google Services screen, and the exact fingerprint equality check failed before any GMS artifact was written.

This should be solved as a general crawler-domain settling problem, not as a Google-specific rule. The implementation should add a focused `DestinationSettler` helper, observe newly discovered clicked destinations for a fixed 10-second dwell window, rank eligible samples by richness and diagnostic quality at the end of that window, and keep external package matching strict while making destination fingerprint validation compatible with asynchronous content enrichment. Route replay can use one narrow early-settle exception: if a sample's fingerprint equals the fingerprint already saved for the target screen being replayed, the route step can settle immediately.

## Current State

- `DeepCrawlCoordinator.expandScreen(...)` opens each candidate with `openChildFromScreen(...)`, computes `afterClickFingerprint` from the returned root, and uses that fingerprint for no-navigation checks, external-boundary pause logging, Continue restore validation, and downstream child scanning (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:302`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:316`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:345`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:482`).
- External Continue stores the first observed destination fingerprint as `expectedDestinationFingerprint`, then reopens the edge and requires both package equality and exact fingerprint equality (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:382`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:413`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:423`).
- `captureChildDestinationAfterClick(...)` retries only until a root is missing, unchanged, or changed. For expected external restores it returns the first root from the expected package; for discovery it returns the first package change or logical fingerprint change (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:920`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:970`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1001`).
- Post-click observation is bounded by `maxPostClickCaptureAttempts = 4`, not by elapsed time (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:930`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1759`).
- The real accessibility service already delays each root capture by `scrollSettleDelayMillis = 350L`, but the crawl evidence shows that this fixed delay is not enough for GMS or Wellbeing (`app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:57`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:435`).
- `ScrollScanCoordinator.settleViewport(...)` already samples after scroll/back transitions and keeps the sample with the highest visible pressable count, but it is local to scroll viewport behavior and does not run after clicked navigation (`app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:176`).
- Logical viewport fingerprints are built from distinct visible pressable elements and omit bounds, which is useful for navigation identity but can mark a sparse toolbar-only loading state as a changed destination (`app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:249`, `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:284`).
- Route replay still uses one post-click capture per route step and accepts any logical fingerprint change as success (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1195`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1207`).
- Existing coordinator tests cover delayed package transitions and expected-package retry after Continue, but the fake host does not currently model same-screen semantic enrichment across repeated captures (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:521`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:733`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1501`).

## Desired End State

- Newly discovered clicked destinations use a fixed 10-second dwell before the crawler trusts the selected post-click destination.
- Route replay may settle early only when an eligible sample fingerprint exactly matches the fingerprint already saved for that replay target screen.
- The first sparse changed root is no longer automatically stored as the expected destination for external-boundary Continue.
- `DestinationSettler` lives under `app/src/main/java/com/example/apptohtml/crawler/` and operates on `AccessibilityNodeSnapshot` plus injected capture/fingerprint callbacks, keeping the logic deterministic and JVM-testable.
- Destination sample scoring uses richness and eligibility metrics for ranking and diagnostics, not as a hard sparse-screen rejection gate or as an early-stop stability shortcut.
- External-boundary restore still requires the expected package, but it accepts compatible settled destination identities instead of requiring exact fingerprint equality between two asynchronously loaded samples.
- Post-click logs report enough sample metrics to explain why a destination was selected during future live-device diagnosis.
- Synthetic tests cover same-package enrichment, external-package enrichment, Google-like sparse-to-rich enrichment, expected-package-missing failure, and legitimate sparse destination behavior.

## Phase 1: Introduce Destination Settling Domain Model

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt` if shared result/data classes are preferable there
- `app/src/test/java/com/example/apptohtml/crawler/DestinationSettlerTest.kt`

Changes:

- [x] Add a `DestinationSettler` class with a small public API for post-click destination capture, for example:
  - `suspend fun settle(request: DestinationSettleRequest): DestinationSettleResult`
  - request fields: `parentPackageName`, `expectedPackageName`, `beforeClickFingerprint`, `topFingerprint`, optional `knownDestinationFingerprint`, `mode` (`DISCOVERY` vs `ROUTE_REPLAY`), `fingerprint: (AccessibilityNodeSnapshot) -> String`, `capture: suspend (String?) -> AccessibilityNodeSnapshot?`, `timeProvider`, and `maxSettleMillis = 10_000L`
  - result fields: selected `root`, selected `fingerprint`, `packageName`, `samples`, `stopReason`, `elapsedMillis`, and whether the selected root came from best richness, final available sample, first eligible fallback after the fixed dwell, or known route fingerprint match
- [x] Model each observed root as a `DestinationSample` with `attemptNumber`, `elapsedMillis`, `packageName`, `fingerprint`, `metrics`, and classification flags such as `expectedPackageMatched`, `packageChanged`, `fingerprintChangedFromBefore`, `fingerprintChangedFromTop`, and `sameFingerprintAsPrevious` for diagnostics only.
- [x] Add deterministic richness metrics over `AccessibilityNodeSnapshot`:
  - visible node count
  - visible text/content-description count
  - visible text/content-description character count
  - distinct pressable count
  - non-empty pressable-label count
  - logical fingerprint length
  - scrollable node count
  - progress/loading indicator count based on class names containing `ProgressBar`, `ProgressIndicator`, or `Loading`
- [x] Define sample ordering so later richer samples can replace earlier sparse samples, but legitimate sparse screens still settle after the fixed dwell:
  - discard `null` captures from candidate selection while recording them in diagnostics
  - for expected-package restores, only roots from `expectedPackageName` are eligible
  - for discovery, a root is eligible after package change or fingerprint change from both before-click and top fingerprints
  - prefer higher richness score, then later sample, then first eligible fallback
  - treat repeated identical fingerprints as diagnostic evidence, not as proof that the destination is trustworthy
  - only treat exact fingerprint equality as authoritative when `mode == ROUTE_REPLAY` and the sample fingerprint equals `knownDestinationFingerprint`
- [x] Define a fixed-dwell sampling policy instead of a fixed-attempt or consecutive-stability policy:
  - keep sampling until `timeProvider() - startedAt >= maxSettleMillis`
  - do not stop early merely because two consecutive eligible samples have the same fingerprint
  - do not stop early merely because one follow-up sample is not richer than the current best
  - do not stop early during discovery, including initial external-boundary discovery before the user chooses Continue
  - stop early during route replay when an eligible sample fingerprint exactly matches the fingerprint already saved for that route target screen
  - stop immediately on cancellation by allowing coroutine cancellation to propagate
  - after the dwell completes, return the best eligible sample if one exists
  - fail only when no eligible sample was ever captured
- [x] Keep the helper free of Android framework classes and file/artifact writes.
- [x] Add unit tests for the helper:
  - sparse Google-like first sample (`More options`) followed by rich sample (`All services`, `Give feedback`, `Sign in`) selects the rich root
  - empty Wellbeing-like first sample followed by rich content selects the rich root
  - one-control sparse screen settles successfully after the dwell and is not rejected for being sparse
  - expected package missing until timeout returns a failure result with all null attempts recorded
  - richer-but-different fingerprints prefer compatibility diagnostics over exact equality assumptions
  - route replay with a known saved fingerprint stops early when that fingerprint is observed
  - discovery with repeated identical eligible fingerprints still waits the full dwell

Design notes:

- Avoid naming any metric `isTooSparse`; that would invite a hard gate. Prefer `richnessScore`, `eligibilityReason`, and `selectionReason`.
- Use `AccessibilityTreeSnapshotter.collectPressableElements(...)` for pressable metrics to stay consistent with existing fingerprints.
- Use elapsed time from injected `timeProvider`; tests can advance a fake clock inside the capture lambda without real delays.

Verification after Phase 1:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DestinationSettlerTest`.
- [x] Confirm the new helper has no dependency on Android framework types.

## Phase 2: Integrate Settling Into Post-Click Destination Capture

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Add a `DestinationSettler` dependency to `DeepCrawlCoordinator`, defaulting to a production instance so tests can use the coordinator without extra setup.
- [x] Replace `captureChildDestinationAfterClick(...)` internals with a call to `DestinationSettler.settle(...)`.
- [x] Keep `openChildFromScreen(...)` responsible for restoring the parent, rewinding to top, moving to the element step, computing `beforeClickFingerprint`, clicking, and logging the final opened-child result.
- [x] Return settled metadata from `openChildFromScreen(...)`, not just root plus before-click fingerprint. Extend `OpenedChildDestination` with:
  - settled `fingerprint`
  - `settleStopReason`
  - `settleElapsedMillis`
  - sample count
  - selected richness metrics
- [x] Compute `afterClickFingerprint` in `expandScreen(...)` from the settled result instead of recomputing separately from a potentially changed active root.
- [x] Preserve existing no-navigation semantics for same-package clicks, but base them on the settled fingerprint.
- [x] Preserve package-boundary behavior: if the settled child package is outside `allowedPackageNames`, pause before scanning the child.
- [x] Keep failure messages user-facing and concise; keep detailed sample evidence in logs.
- [x] Decide whether `maxPostClickCaptureAttempts` should be removed or replaced with `maxPostClickSettleMillis = 10_000L`.

Design notes:

- `captureChildDestinationAfterClick(...)` can remain as a coordinator wrapper if that keeps logging and failure handling localized, but the sample loop should live in `DestinationSettler`.
- The production capture callback should continue passing `expectedChildPackageName` to `host.captureCurrentRootSnapshot(...)` when expected-package restore is in progress, so the service does not leak AppToHTML or another foreground package into the selected root.
- For discovery, continue passing `null` to the host capture so package changes can be observed.

Verification after Phase 2:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [x] Confirm existing delayed-external tests still pass after replacing fixed attempts with elapsed-time settling.
- [x] Confirm `edge_skipped_no_navigation` still appears for same-package clicks that never produce an eligible changed sample.

## Phase 3: Replace Exact External Restore Fingerprint Equality With Compatibility

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DestinationSettlerTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Add a deterministic destination compatibility function, either on `DestinationSettler` or a companion helper:
  - package match remains mandatory
  - exact fingerprint equality is accepted
  - compatible enrichment is accepted when one fingerprint's pressable identity set is a subset of the other and the richer sample has equal-or-higher richness
  - empty/toolbar-only expected fingerprints can match richer restored fingerprints from the same package when the selected labels/resources overlap or the expected sample has no meaningful labels but the package and root class match
  - incompatible same-package screens still fail when fingerprints share no meaningful element identities and neither is an enrichment of the other
- [x] Replace `restoredFingerprint != afterClickFingerprint` in external Continue validation with the compatibility result.
- [x] Log both `destinationFingerprintMatched` and a new compatibility field, such as `destinationCompatible=true/false compatibilityReason=...`.
- [x] Keep `expectedDestinationFingerprint` and `actualDestinationFingerprint` in logs for continuity, but stop presenting exact equality as the sole validation result.
- [x] Store the settled expected destination metrics from the pre-pause sample so the restore comparison can explain whether the restored root was richer, equal, or poorer.
- [x] If compatibility fails, keep the existing edge failure path and message, but include compatibility details in `external_boundary_restore_result`.

Design notes:

- The compatibility function should operate over normalized pressable identity tokens rather than raw string containment where possible. Reuse the same components as `ScrollScanCoordinator` fingerprints: label, resource ID, class name, list-item flag, checkable state, checked state, and editable state.
- Do not make package-only compatibility the default for normal rich fingerprints. It is acceptable only as a guarded fallback for truly sparse transitional expected roots.
- Prefer returning a result object such as `DestinationCompatibilityResult(isCompatible, reason, expectedMetrics, actualMetrics)` over a bare Boolean.

Verification after Phase 3:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DestinationSettlerTest`.
- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [x] Confirm a Google-like sparse expected sample and rich restored sample pass validation.
- [x] Confirm a same-package but unrelated restored destination still fails validation.

## Phase 4: Extend Coordinator Test Harness For Asynchronous Enrichment

Files:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Extend `FakeScreen` or add a parallel fixture type to support capture variants for the same logical screen. One possible shape:
  - `captureVariants: List<FakeScreenVariant> = emptyList()`
  - `FakeScreenVariant(elements, extraVisibleText, className, packageNameOverride?)`
  - `FakeHost.rootFor(...)` selects the variant based on `captureCountsByScreenId`.
- [x] Ensure capture variants can affect both pressable elements and non-pressable visible text/content-description nodes so richness metrics are exercised beyond pressable count.
- [x] Add a regression test for Google-like external enrichment:
  - initial Settings screen has `Open Google`
  - first GMS capture has only `More options`
  - later GMS capture has `All services`, `Give feedback`, `More options`, and `Sign in`
  - user chooses Continue
  - restored GMS capture settles to a compatible rich root
  - crawl completes with a GMS screen artifact and no failed edge
- [x] Add a regression test for Digital Wellbeing-like empty-to-rich enrichment:
  - first external capture is an empty frame
  - later capture includes `App timers`, `Bedtime mode`, `TODAY`, and `View app activity details`
  - Continue restore does not fail exact fingerprint comparison
- [x] Add a same-package enrichment test:
  - a click stays in the selected package
  - first changed same-package root is sparse
  - later root is richer
  - crawler scans the richer child root
- [x] Update tests that assert exact expected capture counts. For example, `externalPackageDecision_fails_restore_when_expected_package_never_appears` currently expects exactly four expected-package captures (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:864`); after elapsed-time settling it should assert on failure outcome and bounded retry behavior instead of the old count.
- [x] Preserve `useRealScan = true` coverage for the foreground AppToHTML return case so the scan path still proves it captures only the external package after Continue (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:641`).

Design notes:

- Keep synthetic trees simple. The point is to model repeated captures becoming semantically richer, not to reproduce the full GMS hierarchy.
- Do not introduce Android framework mocks. All fixtures should stay based on `AccessibilityNodeSnapshot`.

Verification after Phase 4:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [x] Inspect the generated temp crawl logs for the new tests and confirm they include destination-settling sample details.

## Phase 5: Improve Post-Click Diagnostics

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt`
- `documentation/crawler-module.md`

Changes:

- [x] Replace or extend `child_destination_observe_attempt` and `child_destination_observe_result` logs so every sample records:
  - attempt number
  - elapsed settle time
  - expected package
  - actual package
  - observed fingerprint
  - eligibility reason
  - richness metrics
  - whether fingerprint/package changed from parent/top
  - whether it became the current best sample
- [x] Add a final `child_destination_settle_result` log with selected package, selected fingerprint, sample count, elapsed time, stop reason, and selected metrics.
- [x] Extend `external_package_accepted` and `external_boundary_restore_result` logs with selected metrics and compatibility reason.
- [x] Update `documentation/crawler-module.md` to describe destination settling as distinct from scroll viewport settling.
- [x] Mention that external-boundary Continue validates package equality plus destination compatibility, not exact raw fingerprint equality.

Design notes:

- Log schema compatibility is not required, but keep names discoverable and grep-friendly.
- Avoid writing intermediate samples to manifest or graph artifacts unless a later product need appears. Logs are enough for diagnosis.

Verification after Phase 5:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [ ] Manually inspect a new test `crawl.log` and confirm sparse-to-rich selection is explainable from logs alone.
- [x] Confirm no absolute paths are added to crawl graph artifacts.

## Phase 6: Add Route Replay Settling And Saved-Fingerprint Early Exit

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Replace the single `host.captureCurrentRootSnapshot(routeStep.expectedPackageName)` after replay click with settled capture using the route step's expected package.
- [x] Pass `mode = ROUTE_REPLAY` and the route step's already-saved destination fingerprint as `knownDestinationFingerprint`.
- [x] Allow route replay settling to stop before 10 seconds only when an eligible captured sample's fingerprint exactly equals `knownDestinationFingerprint`.
- [x] Ensure discovery calls pass `mode = DISCOVERY` and do not use the saved-fingerprint early-settle path.
- [x] Validate replay success against the settled route-step fingerprint instead of the first changed root.
- [x] Add one route replay test where an intermediate external route step enriches across captures before the destination screen replay continues.
- [x] Add one route replay test where the first eligible sample matches the already-saved destination fingerprint and settling exits early.
- [x] Add one discovery test where two identical eligible samples are observed before 10 seconds and settling still waits for the full dwell.

Design notes:

- Route replay is allowed to be faster because it is validating against a previously captured screen identity, not deciding what a newly discovered destination should be.
- Do not apply the saved-fingerprint early exit to initial discovery, external-boundary pre-pause capture, or same-package child discovery.

Verification after Phase 6:

- [x] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [x] Confirm route replay exits early only for exact known fingerprint matches.
- [x] Confirm discovery still waits the full dwell even when consecutive eligible samples match.

## Automated Verification

- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DestinationSettlerTest`
- [x] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`
- [ ] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`
- [ ] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlGraphJsonWriterTest`
- [ ] `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlGraphHtmlRendererTest`
- [x] `.\gradlew.bat testDebugUnitTest`

## Manual Verification

- [ ] Run a Settings deep crawl and select Continue when the `Google` external-package boundary appears.
- [ ] Confirm the crawl captures a `com.google.android.gms` child screen artifact instead of failing the edge.
- [ ] Confirm `crawl.log` shows multiple destination samples when GMS starts sparse and then enriches.
- [ ] Confirm `external_boundary_restore_result` reports matching package and compatible destination, even if the exact raw fingerprints differ.
- [ ] Confirm `crawl-index.json` records the Google edge as captured/linked rather than failed.
- [ ] Confirm `crawl-graph.json` and `crawl-graph.html` include the GMS node and use sibling artifact basenames.
- [ ] Repeat with `Digital Wellbeing & parental controls` and confirm the empty-frame transitional snapshot does not become the authoritative destination.

## Risks And Open Decisions

- The compatibility heuristic must not allow unrelated screens in the same external package to pass. Keep package match mandatory but insufficient for normal rich samples.
- Fixed-dwell settling intentionally increases crawl latency. For now, every newly discovered clicked destination that reaches destination settling should wait the full 10 seconds before selecting the authoritative sample.
- Route replay has a narrow latency escape hatch: exact equality with the already-saved destination fingerprint. If this condition is applied outside replay, it can reintroduce the sparse-transitional-root bug.
- The real service capture delay is already 350 ms, so a 10-second dwell means roughly 28 captures per settled clicked destination if sampling continuously. Tests should use fake time to avoid slow unit runs.
- If route replay settling cannot be completed in the same implementation, leave the saved-fingerprint early exit unimplemented and document that replay remains vulnerable to transitional roots during deeper replay.
- Richness metrics that count text/content-description must ignore invisible nodes to avoid selecting stale offscreen hierarchy.
- Progress/loading indicator metrics should inform diagnostics and sample ordering, not automatically reject screens; some legitimate screens may expose progress indicators while content is usable.
