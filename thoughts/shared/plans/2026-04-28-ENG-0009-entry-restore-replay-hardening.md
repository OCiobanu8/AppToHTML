# ENG-0009: Verify Entry Restore And Harden Stale Replay Recovery

Date: 2026-04-28
Related research:

- `thoughts/shared/research/2026-04-28-google-continue-entry-restore.md`
- `thoughts/shared/research/2026-04-28-sims-tmobile-crawl-loop-root-cause.md`

Related prior plans:

- `thoughts/shared/plans/2026-04-28-ENG-0008-destination-settling.md`
- `thoughts/shared/plans/2026-04-28-issue-6-sims-tmobile-replay-recovery.md`

## Overview

Fix the Google Continue restore failure and the SIMs/T-Mobile crawl oscillation as one entry-restore/replay correctness problem.

Both failures start when the crawler accepts a live root as "entry restored" without proving it is the captured Settings entry screen. The Google case accepts a transient empty Settings root (`android.widget.FrameLayout::`) after external-package Continue, then fails `screen_top_validation` before it can re-click Google. The SIMs/T-Mobile case accepts a non-entry Settings sub-screen as entry, then stale route replay clicks a broad fallback candidate and reports success because the fingerprint changed.

The fix should make restore state explicit and verified, wait briefly for expected entry content after relaunch/current-root recovery, detect Compose-style back affordances, and prevent stale route replay from clicking or accepting unrelated destinations.

This app is not deployed in production, so prefer clean domain contracts over compatibility shims. Existing crawl manifests/log formats may change if that makes the crawler safer and easier to diagnose.

## Current State

- `ScrollScanCoordinator.rewindToEntryScreen(...)` compares `logicalEntryViewportFingerprint(currentRoot)` with an expected fingerprint, but if that comparison fails and no visible in-app back affordance is detected, it still returns `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` (`app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:108`, `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:119`, `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:130`).
- `EntryScreenResetResult` contains only `root` and `stopReason`, so callers cannot distinguish "matched expected entry" from "no back affordance observed" (`app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:383`).
- `DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch(...)` logs `matchedExpectedLogical=true` from `expectedEntryLogicalFingerprint != null && stopReason == NO_BACK_AFFORDANCE`, then returns that root for both in-app restore and relaunch restore (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1390`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1395`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1420`).
- Root-screen child expansion restores the parent before every click and hard-fails if the restored top fingerprint is not the captured entry/top fingerprint (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:824`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:856`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:865`). This is the immediate Google post-Continue failure path.
- External-package Continue now has destination settling and compatibility, but it is reached only after parent restore succeeds and the edge is clicked again (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:401`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:414`). The Google failure occurs before that compatibility check.
- `EntryScreenBackAffordanceDetector` only checks the clickable candidate node's own text/content description/resource ID, so it can miss a Compose clickable parent whose descendant child has `content-description="Navigate up"` (`app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:415`, `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:424`).
- `AccessibilityTreeSnapshotter` already resolves pressable labels through descendants (`app/src/main/java/com/example/apptohtml/crawler/AccessibilityTreeSnapshotter.kt:116`), so the back-affordance detector is less capable than the element extraction path.
- `AppToHtmlAccessibilityService.performClick(...)` falls back to all live clickable candidates after path divergence (`app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:485`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:504`).
- `clickableCandidateScore(...)` gives every visible enabled clickable/action-click node a positive base score before semantic or bounds confidence is considered (`app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:664`), allowing stale `Network & internet` replay to select an unrelated `T-Mobile` row.
- `DeepCrawlCoordinator.replayRouteToScreen(...)` settles each route-step destination but still treats a step as successful when the settled fingerprint changed from before/top; it does not validate the intended next screen identity until `prepareScreenForExpansion(...)` scans the final destination later (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1249`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1292`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:750`).
- Existing tests cover destination settling and basic entry rewind behavior, but not "expected entry supplied, no back affordance observed, fingerprint does not match" or the combined stale-entry/stale-fallback chain (`app/src/test/java/com/example/apptohtml/crawler/CrawlerExportTest.kt:1062`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:691`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1207`).

## Desired End State

- Entry restore success is explicit. When an expected entry logical fingerprint is supplied, success means the observed root's logical entry fingerprint matched that expected fingerprint.
- "No visible back affordance" remains a useful observation, but it is not treated as verified entry when an expected fingerprint exists.
- Relaunch/current-root restore waits through short transient roots and samples until the expected entry fingerprint appears or a bounded timeout/attempt budget expires.
- Google Continue reaches the post-Continue click replay and external destination compatibility check, instead of failing on a transient empty parent root.
- SIMs/T-Mobile recovery cannot treat a carrier sub-screen as entry-restored, and stale route replay cannot click unrelated fallback candidates.
- Compose clickable containers with nested `Navigate up` descendants are detected as in-app back affordances when top-aligned or in toolbar context.
- Click fallback matching is deterministic, JVM-testable, and requires meaningful confidence before attempting a live node.
- Route replay validates each step against the intended next screen identity when metadata is available, so wrong navigation fails at the first bad step.
- Documentation distinguishes entry restore verification, destination settling, and route replay validation.

## Phase 1: Replace Stop-Reason Success With Verified Entry Restore

Status:

- [x] Added explicit `EntryScreenResetOutcome` values and replay verification fields.
- [x] Required expected logical fingerprint matches before replay verification succeeds.
- [x] Preserved no-back-affordance assumed entry only when no expected fingerprint is known.
- [x] Updated entry restore/relaunch logs to include observed, expected, matched, and verified fields.
- [x] Added and updated rewind tests for expected-match, assumed-entry, and failure outcomes.
- [x] Verified `ScrollScanCoordinatorTest` and `CrawlerExportTest`.

Files:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScrollScanCoordinatorTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/CrawlerExportTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- Replace the overloaded `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` success contract with an explicit result model. One workable shape:
  - `EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL`
  - `EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY`
  - `EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND`
  - `EntryScreenResetOutcome.BACK_ACTION_FAILED`
  - `EntryScreenResetOutcome.LEFT_TARGET_APP`
  - `EntryScreenResetOutcome.MAX_ATTEMPTS_REACHED`
- Extend `EntryScreenResetResult` with:
  - `observedLogicalFingerprint`
  - `expectedLogicalFingerprint`
  - `matchedExpectedLogical`
  - `verifiedForReplay`
  - `observation` or `outcome`
- In `rewindToEntryScreen(...)`, return `MATCHED_EXPECTED_LOGICAL` only when `expectedEntryLogicalFingerprint != null` and `logicalEntryViewportFingerprint(currentRoot) == expectedEntryLogicalFingerprint`.
- When an expected fingerprint is supplied and no back affordance is detected without a match, return `EXPECTED_LOGICAL_NOT_FOUND` with `verifiedForReplay=false`, not assumed success.
- Preserve `NO_BACK_AFFORDANCE_ASSUMED_ENTRY` only for initial/root discovery calls that do not yet have an expected fingerprint.
- Update both `entryScreenResetFailureMessage(...)` helpers to report the new failure modes accurately.
- Update `entry_restore_result` and `entry_restore_relaunch_attempt` logs to include actual observed fingerprint, expected fingerprint presence, matched status, and verified status.

Test coverage:

- Add `rewindToEntryScreen_requires_expected_logical_match_when_expected_is_supplied`: initial root has no back affordance but logical entry fingerprint differs; expect `EXPECTED_LOGICAL_NOT_FOUND`, `matchedExpectedLogical=false`, and zero back attempts.
- Add `rewindToEntryScreen_allows_no_back_assumed_entry_without_expected_fingerprint`: preserves initial discovery behavior.
- Add `rewindToEntryScreen_matches_expected_entry_after_backing`: confirms backing continues until the expected logical fingerprint is observed.
- Update existing assertions that expect `NO_BACK_AFFORDANCE` to assert the more precise outcome.

Verification:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlerExportTest`

## Phase 2: Add Expected-Entry Settling For Restore/Relaunch

Status:

- [x] Treated expected-fingerprint restore as a bounded expected-entry settle operation.
- [x] Required verified replay roots for current-package restore and relaunch restore.
- [x] Kept initial entry discovery able to use the no-back-affordance assumed-entry policy.
- [x] Logged current-root and relaunch restore samples with package, observed fingerprint, outcome, matched, and verified fields.
- [x] Added Google-like transient-empty relaunch, never-settles, and SIMs-like stale sub-screen regressions.
- [x] Verified `DeepCrawlCoordinatorTest`.

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- Treat `restoreToEntryScreenOrRelaunch(expectedEntryLogicalFingerprint = nonNull)` as an expected-entry settle operation, not a first acceptable-root operation.
- For current-package restore:
  - run `normalizeRootToEntryScreen(...)`
  - accept the result only when `verifiedForReplay=true`
  - if the result is `EXPECTED_LOGICAL_NOT_FOUND`, continue to relaunch instead of returning the current root
- For relaunch restore:
  - keep relaunching/capturing until a verified expected entry root is observed or the bounded restore window ends
  - do not return a transient empty root merely because it has no back affordance
- Use a small bounded wait that matches the live service's 350 ms capture delay without making every edge painfully slow. Proposed default: `DEFAULT_MAX_ENTRY_RESTORE_SETTLE_MILLIS = 3_000L`, injected with `timeProvider` for tests. Keep `maxForegroundCaptureAttempts` only as a guard if needed, but make the success condition fingerprint verification.
- Log every relaunch/current-root restore sample with:
  - attempt number
  - package
  - observed entry logical fingerprint
  - outcome
  - matched/verified status
- Return `null` from `restoreToEntryScreenOrRelaunch(...)` when an expected entry is known but never settles. Let existing callers fail the edge or abort through current recovery paths.
- Keep the initial crawl entry path (`expectedEntryLogicalFingerprint=null`, `preferRelaunchWhenEntryIsAmbiguous=true`) able to discover the root screen through the explicit assumed-entry policy.

Test coverage:

- Add a Google-like regression where after external Continue the first relaunch capture is an empty Settings root with no back affordance and the second/third capture is the original Settings root. The crawler should not fail `screen_top_validation`; it should re-click Google and emit `external_boundary_restore_result`.
- Add a negative test where expected entry never appears after Continue. The edge should fail with a restore/expected-entry message, and logs should not claim `matchedExpectedLogical=true`.
- Add a SIMs-like recovery test where current root is a non-entry sub-screen with no detected back affordance. Restore should reject it and relaunch or fail instead of replaying from it.

Verification:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`
- Inspect generated test `crawl.log` snippets and confirm `matchedExpectedLogical=true` appears only beside a real fingerprint match.

## Phase 3: Detect Compose Descendant-Labeled Back Affordances

Status:

- [x] Added descendant-aware back-affordance label resolution for direct labels, nested `title` labels, and first nested text/content-description labels.
- [x] Preserved visible/enabled/clickable, strong signal, and top/toolbar gating.
- [x] Added top Compose parent and lower content row regression fixtures.
- [x] Verified `ScrollScanCoordinatorTest` and `CrawlerExportTest`.

Files:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScrollScanCoordinatorTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/CrawlerExportTest.kt` if existing detector fixtures remain there

Changes:

- Add a snapshot-label helper for back-affordance detection that mirrors the important parts of `AccessibilityTreeSnapshotter.resolveElementLabel(...)`:
  - direct text/content description
  - nested `title` resource label
  - first nested text/content description
- Use that derived label in `EntryScreenBackAffordanceDetector.isLikelyInAppBackAffordance(...)`.
- Keep current safeguards:
  - node must be visible, enabled, and clickable/action-click
  - resource/label must contain a strong back/up signal
  - node must be top-aligned or in toolbar/appbar/actionbar context
- Add a negative fixture so a lower list row with nested `Back` or `Navigate up` text is not treated as an app bar back control.

Test coverage:

- Add a fixture matching the observed SIMs XML shape: clickable `android.view.View` parent, no direct label/resource ID, top bounds around `[196,147][322,273]`, nested non-clickable child with `contentDescription="Navigate up"`. Expect `hasVisibleInAppBackAffordance(root) == true`.
- Add a fixture where the same nested label appears in a lower content row. Expect `false`.

Verification:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlerExportTest`

## Phase 4: Extract And Gate Click Fallback Matching

Status:

- [x] Added `ClickFallbackMatcher` operating on a plain candidate model.
- [x] Replaced positive base score with eligibility gate (resource id, label, class+bounds, icon-only bounds).
- [x] Adapted `AppToHtmlAccessibilityService.performClick` to the matcher and removed obsolete scoring helpers.
- [x] Added `ClickFallbackMatcherTest` covering carrier rejection, path-shift label match, icon-by-bounds, class-only/check-state-only rejection.
- [x] Verified `ClickFallbackMatcherTest` and `DeepCrawlCoordinatorTest`.

Files:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ClickFallbackMatcher.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ClickFallbackMatcherTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- Move fallback click scoring into a crawler-domain helper that operates on a plain candidate model:
  - visible/enabled/clickable/action-click state
  - resolved label
  - resource ID
  - class name
  - bounds
  - checkable/checked state
  - depth/path for tie-breaking only
- Keep `AppToHtmlAccessibilityService` responsible for walking live `AccessibilityNodeInfo`, resolving live labels, adapting candidates, and performing the chosen candidate actions.
- Replace the positive base score with an eligibility gate. A candidate is eligible only if it has at least one meaningful match:
  - exact non-blank resource ID match
  - exact non-blank resolved label match
  - exact class match plus compatible bounds
  - strong bounds compatibility for unlabeled/icon-like controls
- Use checkable/checked/depth only as ranking tie-breakers after eligibility. They must not make a candidate eligible by themselves.
- Log attempted fallback count and rejected-best reason/score at a concise level so stale live paths remain diagnosable.
- Keep path-based clicking unchanged; this phase only limits the fallback path after path resolution diverges or path candidates fail.

Test coverage:

- Add `doesNotMatchUnrelatedCarrierRowForNetworkInternet`: intended element is `Network & internet`; live candidate is `T-Mobile` with different label/resource/class/bounds. Expect no fallback candidate.
- Add `matchesSameLabelAfterPathShift`: live candidate label/resource matches but child index path differs. Expect candidate accepted and ranked first.
- Add `matchesIconOnlyByStrongBoundsAndClass`: unlabeled icon candidate with matching class and near-identical bounds is accepted.
- Add `rejectsClassOnlyAndCheckStateOnly`: class match without bounds, or check-state match alone, is rejected.

Verification:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ClickFallbackMatcherTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`

## Phase 5: Validate Each Route Replay Step Against Intended Identity

Status:

- [x] Added `replayFingerprint` to `CrawlScreenRecord` and `expectedReplay*` to `CrawlRouteStep`.
- [x] Persisted replay fingerprints through tracker, manifest, and graph models.
- [x] Validated each route step against expected replay fingerprint with explicit failure logs.
- [x] Added route-step validation tests for stale wrong-screen and intermediate-step mismatch.
- [x] Verified manifest persists `replayFingerprint` and `expectedReplay*` route fields.
- [x] Verified `DeepCrawlCoordinatorTest`, `CrawlGraphHtmlRendererTest`, `CrawlGraphJsonWriterTest`, `CrawlerTraversalTest`.

Files:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphBuilder.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`
- manifest/graph tests if serialization changes

Changes:

- Store a bounds-free replay-validation fingerprint for each captured screen record. Use:
  - `logicalEntryViewportFingerprint(topRoot)` for the root screen
  - `logicalViewportFingerprint(topRoot)` for non-root screens
- Keep the existing `screenFingerprint` for screen identity/deduplication if its semantics differ; do not overload it unless the code audit proves they are identical and should be unified.
- Extend `CrawlRouteStep` or route metadata so a route step knows the expected replay-validation fingerprint of the screen it should reach.
- In `replayRouteToScreen(...)`, after each settled route-step click:
  - compute the observed logical replay fingerprint from `nextRoot`
  - identify the expected screen for that step: the next parent screen for intermediate steps, or the destination screen for the final step
  - compare observed and expected replay fingerprints
  - fail immediately on mismatch with a message that names the route step, expected screen, expected fingerprint, and observed fingerprint
- Keep the current "fingerprint changed from before/top" check as a no-navigation guard, but do not let it be the only success criterion when expected metadata exists.
- Ensure all stored replay-validation fingerprints are bounds-free. Geometry-sensitive fingerprints remain local to scroll progress/settling only.
- Update manifest/graph writers if the new replay fingerprint is persisted. No migration path is required.

Test coverage:

- Add a fake-host route where clicking `Network & internet` from a stale SIMs root changes the fingerprint by entering `T-Mobile`. Replay should fail at step 0, not later during `prepareScreenForExpansion(...)`.
- Add a positive test where the same route replays successfully with shifted bounds but identical semantic content.
- Add a multi-step route test proving the intermediate expected parent is validated before the final destination.

Verification:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlManifestStoreTest` or the nearest manifest/graph serialization tests touched by the model change

## Phase 6: Documentation And Diagnostics

Status:

- [x] Documented entry restore as a verified replay prerequisite in `crawler-module.md`.
- [x] Documented expected-entry settling as separate from clicked-destination settling.
- [x] Documented descendant-label back-affordance detection.
- [x] Documented click fallback eligibility and route-step identity validation.
- [x] Updated `modules.md` to list `DestinationSettler`, `ClickFallbackMatcher`, and verified entry restore in crawler responsibilities.
- [x] Added implemented-fix sections to the Google Continue and SIMs/T-Mobile analysis notes.

Files:

- `documentation/crawler-module.md`
- `documentation/modules.md`
- `documentation/crawl-20260428-google-continue-entry-restore-analysis.md`
- `documentation/crawl-20260428-sims-tmobile-analysis.md`
- new comparison note only if manual validation produces useful crawl evidence

Changes:

- Document entry restore as a verified replay prerequisite:
  - initial discovery can assume entry when no expected fingerprint exists and no back affordance is visible
  - replay/recovery must match the expected entry logical fingerprint
- Document expected-entry settling as separate from clicked-destination settling.
- Document that `EntryScreenBackAffordanceDetector` resolves descendant labels for top/toolbar back controls.
- Document click fallback eligibility and route-step identity validation.
- Update diagnostic guidance to inspect:
  - `entry_restore_*` observed/matched/verified fields
  - `screen_top_validation`
  - `live_action_path_diverged`
  - fallback rejection/attempt logs
  - `replay_route_step_*` expected/observed replay fingerprints
  - `external_boundary_restore_result`

Verification:

- Read the docs after edits and confirm they describe the new contracts, not the old `NO_BACK_AFFORDANCE` success shortcut.

## Automated Verification

Run focused tests while implementing:

- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlerExportTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ClickFallbackMatcherTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`
- `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.PathReplayResolverTest`

Run broad verification before shipping:

- `.\gradlew.bat testDebugUnitTest`
- `.\gradlew.bat assembleDebug`

## Manual Verification

Status: completed on 2026-04-29 against `crawl_20260429_094845` (Settings, `com.android.settings`).

Findings:

- Initial entry restore succeeded on the first relaunch sample (`entry_restore_relaunch_attempt attempt=1/10 elapsedSettleMillis=409 outcome=no_back_affordance_assumed_entry`); the previous `IllegalStateException("Target app left the foreground while resetting to the first screen.")` no longer fires. This required a follow-up fix to the Phase 2 relaunch loop: `shouldContinueSampling` was gated on `expectedEntryLogicalFingerprint != null`, which made the loop a single-shot when no expected fingerprint was supplied (i.e. on initial discovery). The gate was removed at `DeepCrawlCoordinator.kt:1460,1479`; the bounded settle window and attempt cap remain the sole exit criteria. Regression test: `DeepCrawlCoordinatorTest.initialCrawl_relaunchSamplesUntilEntryRootBecomesVisible`.
- Recovery restores throughout the run typically settled in 1–2 samples (~400–800 ms). One observed case sampled 4 times across ~1.5 s before matching the expected logical fingerprint, well within the 3 s window.
- 60+ screens captured (Network & internet, SIMs, Connected devices, Apps, Notifications, etc.). No `top_fingerprint_mismatch` failures, no carrier-row stale fallback clicks observed.
- The crawl ended in `crawl_complete status=partial_abort` because a 'Sound & vibration' replay attempt could not restore — the foreground was `com.android.systemui` for all 9 relaunch samples (~3.2 s) and the loop exited with `entry_restore_result strategy=relaunch success=false message="The expected entry screen did not settle after relaunch."`. This is the new desired failure mode: explicit, diagnosable, recoverable at the edge level rather than a hang or stale-fingerprint click.

Google Continue scenario:

- Run a Settings crawl.
- Click Continue at the Google external-package boundary.
- Confirm the post-Continue parent restore does not accept `android.widget.FrameLayout::` as verified entry.
- Confirm `crawl.log` shows expected-entry restore samples until the real Settings root fingerprint appears.
- Confirm the crawler re-clicks Google and emits `external_boundary_restore_result`.
- Confirm the Google Services child artifact is captured or, if compatibility fails, the failure is after destination comparison rather than before the post-Continue click.

SIMs/T-Mobile scenario:

- Run a Settings crawl that reaches `Network & internet -> SIMs -> T-Mobile`.
- Confirm `SIMs -> T-Mobile -> SIMs -> T-Mobile` oscillation no longer occurs.
- Confirm a Compose nested `Navigate up` parent is detected as a back affordance.
- Confirm stale replay from a SIMs/carrier sub-screen is rejected or relaunches before any `Network & internet` click is attempted.
- Confirm fallback click logs do not show an unrelated `T-Mobile` row selected for a `Network & internet` route step.
- Confirm route replay failure, if any, is reported at the first mismatched step rather than after a delayed full scan.

Artifact checks:

- Inspect `crawl-index.json` for failed edges and expected-package decisions.
- Inspect `crawl-graph.json` and `crawl-graph.html` to confirm artifact links still use sibling basenames.
- Confirm new replay/entry fingerprints are bounds-free and portable.

## Risks And Mitigations

- Stricter entry restore may fail crawls for dynamic entry screens whose logical content legitimately changes. Mitigate by allowing unverified entry only for initial discovery and by adding future support for multiple valid entry fingerprints if real evidence demands it.
- Expected-entry settling adds latency to recovery-heavy crawls. Mitigate with a short restore-specific settle window and detailed logs before tuning.
- Descendant-label back detection can produce false positives. Mitigate with top/toolbar constraints and negative tests for lower content rows.
- Fallback gating can reject legitimate unlabeled controls. Mitigate with a strict bounds-plus-class eligibility path and icon-only tests.
- Route-step identity validation may expose previously hidden bad routes. That is desired; failures should be recoverable and well logged rather than converted into unrelated clicks.

## Implementation Order

1. Phase 1 and Phase 2 first. They fix the shared root cause and the Google Continue blocker.
2. Phase 3 next. It improves restore accuracy for the SIMs Compose back control.
3. Phase 4 next. It prevents stale live state from producing unrelated clicks.
4. Phase 5 next. It turns wrong route navigation into an early, diagnosable replay failure.
5. Phase 6 alongside the final code changes, then run full automated and manual verification.
