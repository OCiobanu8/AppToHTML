# Issue 6: Fix SIMs/T-Mobile Crawl Oscillation From Stale Replay Recovery

Date: 2026-04-28
Issue: https://github.com/OCiobanu8/AppToHTML/issues/6
Research: `thoughts/shared/research/2026-04-28-sims-tmobile-crawl-loop-root-cause.md`
Prior analysis: `documentation/crawl-20260428-sims-tmobile-analysis.md`
Manual failure evidence: `E:\Logs\com.android.settings\crawl_20260428_125014`

## Overview

Fix the repeated Android Settings `SIMs -> T-Mobile -> SIMs -> T-Mobile` oscillation by making route recovery/replay fail early when it is not actually at the captured entry screen, recognizing Compose back affordance containers, and preventing stale route-step fallback clicks from accepting unrelated live nodes.

Because AppToHTML is not deployed in production, this plan should optimize for the clean long-term architecture instead of preserving current in-memory model shapes, manifest fields, or heuristic contracts. Prefer explicit crawl/replay identity data and deterministic domain helpers over compatibility shims around the existing ambiguous behavior. Durable fingerprints must be bounds-free so crawl identity and replay validation survive different screen sizes, densities, font scaling, and device form factors.

The saved `SIMs` artifact is valid. The failure chain is live-state recovery and replay confidence:

1. Entry recovery can accept `NO_BACK_AFFORDANCE` as entry-restored even when an expected entry fingerprint is available and not matched.
2. Compose Settings exposes `Navigate up` as a label on a descendant of a clickable parent, so the current back affordance detector can miss it.
3. Click fallback scoring gives any visible clickable live node a positive score, so a stale `Network & internet` route step can click the unrelated `T-Mobile` row.
4. Route replay only checks that the logical fingerprint changed after each route step, so wrong navigation is reported as replay success until the later full screen scan catches divergence.

## Current State

- `DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch(...)` logs `matchedExpectedLogical=true` from `EntryScreenResetStopReason.NO_BACK_AFFORDANCE`, not from a fingerprint comparison, then accepts that root when `entryIsAmbiguous` is false.
- `ScrollScanCoordinator.rewindToEntryScreen(...)` first compares `logicalEntryViewportFingerprint(currentRoot)` with the expected entry fingerprint, but still returns `NO_BACK_AFFORDANCE` if no in-app back affordance is detected later.
- `EntryScreenBackAffordanceDetector.isLikelyInAppBackAffordance(...)` checks only the candidate clickable node's own `contentDescription`, `text`, and `viewIdResourceName`; it does not inspect descendant labels.
- `AppToHtmlAccessibilityService.clickableCandidateScore(...)` starts each fallback candidate at `100 - depth`, so candidates with no semantic or bounds match can be attempted.
- `DeepCrawlCoordinator.replayRouteToScreen(...)` treats a route step as successful whenever `afterClickFingerprint != beforeClickFingerprint`.
- `ScrollScanCoordinator.logicalViewportFingerprint(...)` and `logicalEntryViewportFingerprint(...)` already omit bounds, while `geometrySensitive*Fingerprint(...)` includes bounds for local scroll/settle behavior. The plan should preserve that boundary and avoid introducing bounds into stored or replay-validation fingerprints.
- Unit coverage exists for entry ambiguity, shifted replay bounds, delayed external transitions, path replay resolution, and logical entry fingerprinting, but not for this exact stale-entry/stale-click chain.

## Desired End State

- Entry recovery exposes a verified outcome, not just a stop reason. When an expected entry logical fingerprint is supplied, restore success means the current root's logical entry fingerprint matched that expected value.
- "No visible back affordance" is recorded as an observation, but it is not treated as verified entry during replay/recovery unless the restore request explicitly allows unverified initial-root discovery.
- Screen identity, entry identity, replay-validation identity, graph identity, and manifest identity are all bounds-free. Geometry-sensitive fingerprints remain local implementation details for scroll settling only.
- A Compose clickable parent with a nested `contentDescription="Navigate up"` child is treated as a visible in-app back affordance when it is top-aligned or in toolbar context.
- Fallback click candidates require at least one meaningful match against the intended element, such as label, resource ID, class plus compatible bounds, or strong bounds overlap.
- Click fallback matching lives in deterministic JVM-testable domain code; `AppToHtmlAccessibilityService` adapts live `AccessibilityNodeInfo` into that matcher instead of owning the scoring policy.
- A stale `Network & internet` route step on the `SIMs` screen fails the click/replay instead of clicking `T-Mobile`.
- Route replay validates the expected screen identity after every route-step click when identity metadata exists, rather than waiting for a delayed destination scan.

## Phase 1: Replace Stop-Reason-as-Success With Verified Entry Restore

Files:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt` if the restore result model should move out of `ScrollScanCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScrollScanCoordinatorTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [ ] Replace the overloaded `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` success contract with an explicit result shape, such as:
  - verified success: `MATCHED_EXPECTED_LOGICAL`
  - unverified initial success: `NO_BACK_AFFORDANCE_ASSUMED_ENTRY`
  - failures: `EXPECTED_LOGICAL_NOT_FOUND`, `BACK_ACTION_FAILED`, `LEFT_TARGET_APP`, `MAX_ATTEMPTS_REACHED`
- [ ] Track both the final observation (`no_back_affordance`, `matched_expected_logical`, etc.) and whether the root is verified for the caller's purpose. Avoid deriving success from a stop reason name.
- [ ] Set the verified match only when `expectedEntryLogicalFingerprint != null` and `logicalEntryViewportFingerprint(currentRoot) == expectedEntryLogicalFingerprint`.
- [ ] When an expected fingerprint is supplied and no back affordance is detected before a match, return a failure/unverified outcome such as `EXPECTED_LOGICAL_NOT_FOUND` instead of making it indistinguishable from a valid entry match.
- [ ] Update `restoreToEntryScreenOrRelaunch(...)` to accept an in-app restore result only when the request's verification requirement is satisfied:
  - replay/recovery with an expected fingerprint requires verified logical match
  - first capture/root discovery may explicitly allow unverified no-back-affordance entry when no expected fingerprint exists
- [ ] Apply the same verification rule to relaunch attempts; relaunch should not return a root that failed the expected-entry check.
- [ ] Update service/coordinator failure-message helpers so new restore outcomes are reported accurately.
- [ ] Update `entry_restore_result`, `entry_restore_relaunch_attempt`, and failure logs to report the actual restore outcome, expected fingerprint presence, observed logical fingerprint, and verified/unverified status.
- [ ] Add or update tests proving a non-entry screen without a detected back affordance triggers relaunch/failure when an expected entry fingerprint is present.
- [ ] Add tests proving initial root discovery can still proceed through the explicit unverified path when no expected fingerprint exists.
- [ ] Add or keep tests proving expected-entry matching ignores root and element bounds changes.

Design notes:

- Prefer a result model that makes invalid states unrepresentable over a minimally disruptive boolean. The long-term fix is to separate "what recovery observed" from "whether this root is verified for replay."
- Rename `preferRelaunchWhenEntryIsAmbiguous` if needed so the call site expresses policy directly, for example `allowUnverifiedNoBackEntry` or `restorePurpose`.
- Do not carry forward `NO_BACK_AFFORDANCE` as a synonym for success in replay/recovery logs or branching.

Verification after Phase 1:

- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [ ] Confirm logs no longer claim `matchedExpectedLogical=true` based only on `NO_BACK_AFFORDANCE`.

## Phase 2: Detect Compose Clickable-Parent Back Affordances

Files:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
- `app/src/test/java/com/example/apptohtml/crawler/ScrollScanCoordinatorTest.kt`

Changes:

- [ ] Add descendant label resolution for clickable back-affordance candidates, matching the spirit of `AccessibilityTreeSnapshotter.resolveElementLabel(...)` without exposing production snapshotter internals unnecessarily.
- [ ] Let `isLikelyInAppBackAffordance(...)` derive `normalizedLabel` from direct `contentDescription`/`text`, then from a bounded descendant search for text/content-description.
- [ ] Keep the existing resource ID, toolbar context, and top-alignment safeguards so ordinary rows containing words like `Back` lower on the screen are not treated as navigation controls.
- [ ] Add a regression fixture matching the observed Compose shape:
  - clickable parent `android.view.View`, no text/content-description/resource ID, top bounds around `[196,147][322,273]`
  - nested non-clickable child with `contentDescription="Navigate up"`
  - detector returns `true`
- [ ] Add a negative fixture where a clickable list row has nested text containing `Back` or `Navigate up` outside the top/toolbar region and detector returns `false`.

Design notes:

- Keep descendant search bounded by tree depth or candidate subtree size if needed; this runs during recovery loops.
- Avoid broadening `looksLikeEntryBackAffordance(...)` unless the same Compose shape affects logical entry fingerprints. The immediate issue is live back detection.

Verification after Phase 2:

- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScrollScanCoordinatorTest`.
- [ ] Confirm the new Compose fixture reproduces the `038_child_sims.xml` back-control shape.

## Phase 3: Move Click Fallback Matching Into Deterministic Domain Code

Files:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/main/java/com/example/apptohtml/crawler/ClickFallbackMatcher.kt` or similarly named domain helper
- `app/src/test/java/com/example/apptohtml/crawler/ClickFallbackMatcherTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/PathReplayResolverTest.kt` if existing path replay coverage should assert integration behavior

Changes:

- [ ] Extract click fallback matching into a small JVM-testable helper that operates on a plain candidate model, not directly on `AccessibilityNodeInfo`.
- [ ] Keep `AppToHtmlAccessibilityService` responsible only for traversing live nodes and adapting each node into the matcher model with resolved label, resource ID, class, bounds, enabled/visible/clickable state, and check state.
- [ ] Replace the unconditional positive base score with an eligibility gate that requires at least one meaningful match before a fallback candidate can be attempted.
- [ ] Treat these as meaningful matches:
  - exact non-blank resource ID match
  - exact resolved label match
  - class match plus compatible bounds
  - strong bounds compatibility for unlabeled/icon-like controls
  - checkable/checked only as tie-breakers, not eligibility by themselves
- [ ] Preserve successful fallback for shifted/reordered but semantically matching nodes, especially existing logical replay bounds-shift coverage.
- [ ] Log rejected fallback candidate counts or the best rejected score if useful for diagnosing future stale paths, without making logs noisy.
- [ ] Add tests proving an intended `Network & internet` element will not select a live `T-Mobile` row when label/resource ID/class/bounds do not meaningfully match.
- [ ] Add tests proving legitimate fallback still works when the live node has the same label/resource ID with shifted bounds.
- [ ] Add icon-only tests where class plus strong compatible bounds is accepted, and where class-only or check-state-only matches are rejected.

Design notes:

- This phase is deliberately independent of the entry restore fix. Even if restore starts from a bad screen again later, fallback click resolution should not turn a stale route step into an unrelated click.
- Since no legacy API has to be preserved, avoid leaving private scoring policy embedded in the Android service. The matcher should be easy to reason about, tune, and test with synthetic data.
- Bounds are acceptable here only as transient live-click confidence, never as a persisted or replay-validation fingerprint criterion.
- Be careful with icon-only controls. For elements with no label/resource ID, bounds and class may need to be sufficient, but the threshold should be stricter than "any visible clickable."

Verification after Phase 3:

- [ ] Run the new scorer/fallback unit tests.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.PathReplayResolverTest` if the scorer lives near path replay coverage.

## Phase 4: Add Required Early Replay-Step Identity Validation

Files:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlGraphBuilder.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `documentation/modules.md`
- `documentation/crawler-module.md`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`
- `app/src/test/java/com/example/apptohtml/crawler/CrawlManifestStoreTest.kt` or existing manifest/graph tests if model fields are serialized

Changes:

- [ ] Store a bounds-free replay-validation fingerprint on each `CrawlScreenRecord`, such as the current `logicalViewportFingerprint`, in addition to the existing screen identity fingerprint used for crawl graph deduplication.
- [ ] If useful for entry checks and diagnostics, also store the bounds-free `logicalEntryViewportFingerprint` explicitly on screen records instead of recomputing expectations from unrelated fields.
- [ ] Audit all persisted, graph-exported, and replay-validation fingerprint call sites to ensure they do not use `geometrySensitiveViewportFingerprint(...)`, `geometrySensitiveEntryViewportFingerprint(...)`, root bounds, or element bounds.
- [ ] Update manifest and graph export schemas to include the new bounds-free replay identity fields. No migration path is required for old crawl artifacts.
- [ ] Update crawler/data-flow documentation to distinguish bounds-free screen identity fingerprints, bounds-free entry fingerprints, bounds-free replay-validation fingerprints, and local geometry-sensitive scroll fingerprints.
- [ ] After each successful route-step click, validate the resulting root against the intended next screen.
- [ ] For an intermediate route step, compare `logicalViewportFingerprint(nextRoot)` with the next route parent screen's stored replay-validation fingerprint; on the final step, compare against the destination screen's stored replay-validation fingerprint.
- [ ] Keep the existing `afterClickFingerprint != beforeClickFingerprint` check as a basic navigation check, but do not let it be the only success condition when expected screen metadata exists.
- [ ] On mismatch, return `ReplayToScreenResult.Failure` immediately with a message that names the clicked route step, expected screen ID/name, expected logical fingerprint, and observed logical fingerprint.
- [ ] Add focused fake-host coverage where a route step changes the fingerprint by navigating to the wrong child; replay should fail before `prepareScreenForExpansion(...)` performs a full scan.

Design notes:

- Treat this as part of the core fix, not optional hardening. The crawler should know whether each route step reached the screen it intended.
- Use only logical, bounds-free fingerprints for validation to preserve existing tolerance for geometry changes and cross-device replay.
- Do not overload the existing `screenFingerprint` if it serves graph identity/deduplication semantics. Add a separate replay-validation fingerprint so screen identity, entry identity, and route replay identity remain distinct.
- Keep geometry-sensitive fingerprints available only where local viewport movement needs them, such as detecting scroll progress or settling within the same device session.

Verification after Phase 4:

- [ ] Run `.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest`.
- [ ] Confirm wrong route-step navigation is reported as replay failure, not delayed destination divergence.
- [ ] Confirm route replay validation still succeeds when the same synthetic screen is rebuilt with shifted/scaled bounds but identical semantic content.

## Phase 5: Manual Crawl Validation Against Android Settings

Files and artifacts:

- Android device or emulator with the Settings app scenario that produced `E:\Logs\com.android.settings\crawl_20260428_125014`
- New crawl output under `E:\Logs\com.android.settings\crawl_yyyyMMdd_HHmmss`
- `documentation/crawl-20260428-sims-tmobile-analysis.md` may be updated only if a new comparison note is useful.

Changes:

- [ ] Install/run the fixed app build.
- [ ] Re-run the Android Settings crawl through the same SIMs/T-Mobile path.
- [ ] Copy the latest crawl folder locally.
- [ ] Inspect `crawl.log` for:
  - no repeated `SIMs -> T-Mobile -> SIMs -> T-Mobile` oscillation
  - no `matchedExpectedLogical=true` unless the expected entry logical fingerprint actually matched
  - stale route replay from a carrier sub-screen fails or relaunches before clicking unrelated rows
  - no broad fallback click for `Network & internet` selecting `T-Mobile`
- [ ] Compare generated graph/manifest around `Network & internet`, `SIMs`, and `T-Mobile`.
- [ ] Inspect manifest/graph fingerprints to confirm they do not encode root or element bounds.

Manual acceptance criteria:

- [ ] Replaying `Network & internet -> SIMs` cannot start from live `SIMs` or carrier UI while being treated as entry-restored.
- [ ] A Compose clickable parent containing nested `Navigate up` is recognized as a back affordance.
- [ ] A stale route step does not click unrelated fallback candidates with no meaningful match.
- [ ] The crawl no longer oscillates between `SIMs` and `T-Mobile` for the recorded scenario.
- [ ] Route replay failure is reported at the first wrong state where possible.
- [ ] Moving the same crawl scenario to another device size does not invalidate stored screen/replay fingerprints solely because bounds changed.

## Final Verification

- [ ] Run `.\gradlew.bat testDebugUnitTest`.
- [ ] Run `.\gradlew.bat assembleDebug`.
- [ ] Perform the Phase 5 manual crawl validation.
- [ ] Attach or reference the new crawl folder in the issue/PR notes.

## Risks And Rollback

- Tightening entry restore may make some apps relaunch more often when their entry screen is dynamic. Mitigation: only require verified entry when the coordinator already has an expected fingerprint; for dynamic apps, future work should model alternate valid entry fingerprints explicitly rather than accepting unverified roots.
- Descendant-label back detection can create false positives if it scans too broadly. Mitigation: keep top/toolbar constraints and add negative tests.
- Click fallback gating can reject legitimate unlabeled controls. Mitigation: preserve a bounds/class path for icon-like controls and cover it in tests.
- Replay-step identity validation changes crawl record and manifest fields. Mitigation: this is acceptable because the app is not deployed; document the new fields and keep graph artifacts portable through sibling artifact basenames.
- Removing bounds from durable fingerprints can merge screens that differ only by layout position. Mitigation: prefer semantic identity signals such as labels, resource IDs, class names, checkable/editable state, package, screen title, and stable identity hints; only use geometry for local click/scroll mechanics.
