# Google Continue Entry Restore Failure Research

## Research Question

Investigate the issue described in `documentation/crawl-20260428-google-continue-entry-restore-analysis.md`: after the Google external-package pause is continued, why does the crawler fail before it re-clicks Google?

## Summary

The current code path confirms that the Google edge fails before destination compatibility is evaluated. The pre-pause click reaches `com.google.android.gms`, destination settling chooses the richer Google Services root, and the Continue path records that rich fingerprint as the expected destination. After Continue, `DeepCrawlCoordinator.openChildFromScreen()` tries to restore the parent Settings screen before replaying the Google click, but root restore can return a transient Settings root with logical fingerprint `android.widget.FrameLayout::`. The subsequent top-state validation rejects that transient root and calls `failCurrentEdge(...)`, so the code never executes `host.click(element)` for the post-Continue Google replay.

The important current-state behavior is that `ScrollScanCoordinator.rewindToEntryScreen()` uses the same `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` for both "matched the expected entry fingerprint" and "saw no visible in-app back affordance." `DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch()` accepts `NO_BACK_AFFORDANCE` as restore success in both the in-app path and relaunch path. The log field `matchedExpectedLogical` is derived from `expectedEntryLogicalFingerprint != null && stopReason == NO_BACK_AFFORDANCE`, not from a returned match flag.

## Live Evidence

Referenced crawl folder: `E:\Logs\com.android.settings\crawl_20260428_194407`.

The Google pre-pause settle path succeeds:

- `crawl.log:114` reports `child_destination_settle_result ... triggerLabel="Google" ... selectedPackageName="com.google.android.gms" ... stopReason=fixed_dwell_exhausted selectionReason=best_richness`.
- The selected fingerprint includes `All services`, `Give feedback`, `More options`, and `Sign in`.
- `crawl.log:118` records `crawl_pause_resolved reason=external_package_boundary decision=continue`.
- `crawl.log:119` records `external_package_accepted ... expectedDestinationFingerprint="android.widget.FrameLayout::All services...Sign in..."`.

The post-Continue restore path fails before replaying the click:

- `crawl.log:120` records `external_boundary_restore_attempt` for Google.
- `crawl.log:124` records `entry_restore_relaunch_attempt attempt=2/4 stopReason=no_back_affordance`.
- `crawl.log:125` records `entry_restore_result strategy=relaunch success=true`.
- `crawl.log:126` records `screen_top_validation ... actualFingerprint="android.widget.FrameLayout::"`.
- `crawl.log:127` records `child_open_restore_result ... result=top_fingerprint_mismatch`.
- `crawl.log:128` records the recoverable edge failure message for the Google element.

The same run shows this is not only a Google Services destination-compatibility issue:

- No `external_boundary_restore_result` is emitted for Google after `crawl.log:120`; compatibility is never reached.
- `crawl.log:1781` later emits `external_boundary_restore_result` for Digital Wellbeing with `destinationCompatible=true`, which means the same Continue machinery can reach destination comparison when parent restore returns the expected Settings top state.
- Wallpaper & style shows the same transient-empty-root pattern later (`crawl.log:895-898`), so the issue is not unique to GMS destination content.

## Code Path

Initial crawl setup stores a bounds-free entry fingerprint after scanning the Settings root:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:68-83` restores or relaunches the selected app, scans the root, and computes `entryScreenLogicalFingerprint` from the first step root.

Root screen expansion calls `openChildFromScreen(...)` for each eligible target:

- `DeepCrawlCoordinator.kt:295-314` passes `entryScreenLogicalFingerprint`, `expectedTopFingerprint`, and `usesEntryFingerprint` into `openChildFromScreen(...)`.
- `DeepCrawlCoordinator.kt:346-410` handles external package pauses. On Continue it adds the external package to `allowedPackageNames`, logs `external_package_accepted`, logs `external_boundary_restore_attempt`, then calls `openChildFromScreen(...)` again with `expectedChildPackageName = childPackageName`.

`openChildFromScreen(...)` validates the parent before clicking:

- `DeepCrawlCoordinator.kt:824-844` logs `child_open_restore_attempt` and calls `restoreLiveScreenForEdge(...)`.
- `DeepCrawlCoordinator.kt:845-860` rewinds the restored root to top and computes `liveTopFingerprint`.
- `DeepCrawlCoordinator.kt:861-876` logs `screen_top_validation` and fails the edge immediately if `liveTopFingerprint != expectedTopFingerprint`.
- The actual click happens later at `DeepCrawlCoordinator.kt:905`, so any top mismatch prevents post-Continue replay.

Root-screen restore goes through `restoreToEntryScreenOrRelaunch(...)`:

- `DeepCrawlCoordinator.kt:1121-1134` uses `restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint)` directly when the screen route has no steps.
- `DeepCrawlCoordinator.kt:1366-1399` captures the current root, tries `normalizeRootToEntryScreen(...)` if already in the target package, and returns the result when `stopReason == NO_BACK_AFFORDANCE && !entryIsAmbiguous`.
- `DeepCrawlCoordinator.kt:1402-1423` relaunches the target app, calls `normalizeRootToEntryScreen(...)` up to `maxForegroundCaptureAttempts`, and returns the root on the first `NO_BACK_AFFORDANCE`.

`normalizeRootToEntryScreen(...)` delegates to `ScrollScanCoordinator.rewindToEntryScreen(...)`:

- `DeepCrawlCoordinator.kt:1349-1364` passes `expectedEntryLogicalFingerprint` into `rewindToEntryScreen(...)`.
- `ScrollScanCoordinator.kt:119-128` first checks whether `logicalEntryViewportFingerprint(currentRoot) == expectedEntryLogicalFingerprint`, then returns `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` if it matched.
- `ScrollScanCoordinator.kt:130-135` also returns `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` when no visible in-app back affordance is detected, even if an expected entry fingerprint was supplied and did not match.

The same enum value is therefore used for two different observations: expected logical entry matched, or no visible back affordance was found.

## Restore Contract Details

`EntryScreenResetResult` currently contains only:

- `root`
- `stopReason`

It does not expose whether an expected logical fingerprint was supplied, what fingerprint was observed, or whether the final root was verified against the expected entry fingerprint.

`DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch()` logs:

```kotlin
matchedExpectedLogical=${expectedEntryLogicalFingerprint != null &&
    entryResetResult.stopReason == EntryScreenResetStopReason.NO_BACK_AFFORDANCE}
```

That expression makes every `NO_BACK_AFFORDANCE` result look like a logical match when an expected fingerprint exists. In the live Google failure, the later `screen_top_validation` shows the root was not actually matched.

## Fingerprint Mechanics

The entry/top validation path uses logical fingerprints, not geometry-sensitive fingerprints:

- `ScrollScanCoordinator.logicalEntryViewportFingerprint(...)` calls `buildViewportFingerprint(..., includeBounds = false, excludeEntryBackAffordances = true)` at `ScrollScanCoordinator.kt:265-270`.
- `buildViewportFingerprint(...)` includes the root class and sorted pressable-element semantics at `ScrollScanCoordinator.kt:284-314`.
- An empty or still-loading root with no collected pressable elements fingerprints as `android.widget.FrameLayout::`, matching the live failure.

This means the failure is not caused by bounds drift. It is caused by returning a root whose semantic content is not the captured Settings top state.

## Test Coverage

Existing unit coverage verifies destination settling but does not model the post-Continue parent restore transient:

- `DeepCrawlCoordinatorTest.kt:700-769` covers sparse-to-rich Google Services settling and asserts `external_boundary_restore_result` and `destinationCompatible=true`.
- `DeepCrawlCoordinatorTest.kt:774-839` covers empty-to-rich Digital Wellbeing settling.
- `DeepCrawlCoordinatorTest.kt:1206-1265` covers retrying expected-package capture after Continue restore.
- These tests assume parent restore can get back to the entry screen; they do not simulate a relaunch capture returning a transient empty Settings root while an expected entry fingerprint is available.

Existing reset tests preserve the current `NO_BACK_AFFORDANCE` contract:

- `CrawlerExportTest.kt:1062-1110` expects backing through visible back affordances to end with `NO_BACK_AFFORDANCE`.
- `CrawlerExportTest.kt:1112-1138` expects a current root with no back affordance to return `NO_BACK_AFFORDANCE`.
- `CrawlerExportTest.kt:1140-1172` and `1174-1204` cover max attempts and leaving the target app.

Logical fingerprint tests cover bounds tolerance:

- `ScrollScanCoordinatorTest.kt:10-59` verifies logical viewport fingerprints ignore root and element bounds shifts.
- `ScrollScanCoordinatorTest.kt:104-132` verifies entry fingerprints match with and without visible back affordance.

## Related Research

This issue overlaps with the earlier SIMs/T-Mobile stale recovery research in `thoughts/shared/research/2026-04-28-sims-tmobile-crawl-loop-root-cause.md`.

The shared theme is entry restore verification. SIMs/T-Mobile involved a non-entry Settings sub-screen accepted as replayable entry state. The Google Continue failure involves a transient empty Settings root accepted as restored entry state. In both cases, `NO_BACK_AFFORDANCE` is treated as enough to proceed even when a known entry fingerprint exists.

The Google case is narrower because it fails at `screen_top_validation` before clicking an unrelated target. The SIMs/T-Mobile case continued into stale route replay and fallback click behavior.

## Current Architecture Insight

Destination settling and entry restore settling are currently separate behaviors:

- `DestinationSettler` observes post-click child roots over a bounded dwell window and chooses the richest eligible destination.
- Entry restore uses `rewindToEntryScreen(...)` plus a small relaunch capture retry loop, but it does not perform an equivalent bounded wait for the expected entry logical fingerprint.

The latest destination-settling changes improved child selection for Google Services, but they do not affect whether the parent Settings root has settled before `openChildFromScreen(...)` performs top validation.

## Open Questions

- Should entry restore expose an explicit verified/unverified result instead of encoding both expected-match and no-back-affordance observations as `NO_BACK_AFFORDANCE`?
- Should relaunch restore keep sampling until the expected entry logical fingerprint appears when one is known?
- Should `screen_top_validation` remain the first hard gate after restore, or should entry restore itself report a distinct "expected entry did not settle" failure before `openChildFromScreen(...)` rewinds to top?
- How long should parent entry settling wait relative to the existing post-click destination settling window?

## Metadata

- Repository: `AppToHTML`
- Remote: `https://github.com/OCiobanu8/AppToHTML.git`
- Branch: `codex/issue-8-destination-settling`
- Commit: `c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f`
- Working tree: dirty at research time; destination-settling files and issue documentation include uncommitted changes.
- Primary issue document: `documentation/crawl-20260428-google-continue-entry-restore-analysis.md`
- Live crawl folder: `E:\Logs\com.android.settings\crawl_20260428_194407`
