# SIMs/T-Mobile Crawl Oscillation Root Cause

## Research Question

Continue the analysis from `documentation/crawl-20260428-sims-tmobile-analysis.md` and identify why the crawl repeatedly goes back and forth between Android Settings `SIMs` and `T-Mobile`.

## Summary

The repeated `SIMs -> T-Mobile -> SIMs -> T-Mobile` behavior is caused by a route replay recovery failure chain, not by the saved `SIMs` artifact itself.

The root cause is that replay can start from the wrong live screen and still perform low-confidence fallback clicks:

1. `restoreToEntryScreenOrRelaunch()` can return a non-entry Settings sub-screen as if it were the Settings root when `rewindToEntryScreen()` stops because it sees no visible in-app back affordance.
2. In this crawl, that happened while the live UI was on the `SIMs`/carrier area. The log line says `matchedExpectedLogical=true`, but `DeepCrawlCoordinator` computes that value from the `NO_BACK_AFFORDANCE` stop reason, not from a fingerprint match.
3. The Compose `SIMs` screen exposes the back control as a clickable parent with no direct label/resource id and a nested child with `content-description="Navigate up"`. `EntryScreenBackAffordanceDetector` only checks the candidate node's own label/resource id, so it can miss this back affordance.
4. Once replay starts on the wrong screen, `AppToHtmlAccessibilityService.performClick()` falls back from the stale child-index path to broad clickable-candidate scoring. Candidates get a positive base score even when label/resource id/class/bounds do not match, so the crawler can click the `T-Mobile` row while trying to click `Network & internet`.
5. `DeepCrawlCoordinator.replayRouteToScreen()` treats each route step as successful if the logical viewport fingerprint changed. It does not validate the intended intermediate or destination identity until `prepareScreenForExpansion()` runs a full scan afterward. That allows a wrong click that enters `T-Mobile` to be logged as route success for `SIMs`, then only later marked divergent.

The effect is the observed oscillation: recovery returns to `SIMs` or nearby carrier UI, stale route replay clicks the carrier row, validation eventually notices `T-Mobile`, recovery backs out, and the next queued replay can repeat the cycle.

## Evidence From Crawl Artifacts

`documentation/crawl-20260428-sims-tmobile-analysis.md` establishes the video sequence near the end:

`SIMs -> T-Mobile -> SIMs -> T-Mobile -> SIMs -> T-Mobile -> SIMs -> T-Mobile -> AppToHTML`

The log confirms replay starts from the wrong live screen. For `screen_038` (`SIMs`), replay begins with route `Network & internet@0 -> SIMs@0`, but before step 0 the live logical fingerprint already contains `Mobile data`, `Navigate up`, and `T-Mobile`:

- `E:\Logs\com.android.settings\crawl_20260428_125014\crawl.log:3597` starts replay for `screen_038` with expected fingerprint `title:sims`.
- `crawl.log:3600` reports `entry_restore_result ... stopReason=no_back_affordance matchedExpectedLogical=true`.
- `crawl.log:3608` shows step 0, `Network & internet`, had `beforeClickFingerprint="android.widget.FrameLayout::Mobile data...Navigate up...T-Mobile..."`.

So replay was not at the Settings root when it tried to click `Network & internet`.

The same step used fallback clicking:

- `crawl.log:3602` reports `live_action_path_diverged` for `Network & internet`.
- `crawl.log:3606` reports fallback candidates.
- `crawl.log:3607` clicks fallback candidate `android.view.View[]@[0,598][814,797]`.

That bounds range corresponds to the clickable `T-Mobile` carrier row in `038_child_sims.xml`:

- `038_child_sims.xml:15` has a clickable `android.view.View` at `[184,598][998,797]` with nested text `T-Mobile`.

The final `SIMs` replay then goes one screen too deep:

- `crawl.log:3617` shows after clicking `SIMs`, the fingerprint contains `App data usage`, `Preferred network type`, `Roaming`, `Use this SIM`, and other carrier-detail fields.
- `crawl.log:3618` still logs `replay_result ... success=true`.
- `crawl.log:3644` later validates actual screen identity as `title:t_mobile`.
- `crawl.log:3646` records `Route replay diverged for 'SIMs'. Expected 'SIMs' but found 'T-Mobile'.`

The saved `SIMs` screen itself is valid:

- `038_child_sims.xml:2` names the screen `SIMs`.
- `038_child_sims.xml:4-7` lists `T-Mobile`, selected `T-Mobile`, `Mobile data`, and `Navigate up`.
- `002_child_network_internet.xml:5` has the `SIMs` row with summary `T-Mobile`.

## Code Findings

### Entry Restore Can Accept The Wrong Screen

`DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch()` accepts any `EntryScreenResetStopReason.NO_BACK_AFFORDANCE` result unless this is the initial ambiguous launch path:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1293-1307`

It also logs `matchedExpectedLogical` as:

```kotlin
expectedEntryLogicalFingerprint != null &&
    entryResetResult.stopReason == EntryScreenResetStopReason.NO_BACK_AFFORDANCE
```

That is not an actual comparison against `expectedEntryLogicalFingerprint`.

`ScrollScanCoordinator.rewindToEntryScreen()` does compare the expected entry fingerprint first, but if that does not match and no back affordance is detected, it still returns `NO_BACK_AFFORDANCE`:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:95-111`

This makes "no visible back affordance" equivalent to "entry screen", even in calls that supplied a known expected entry fingerprint.

### Compose Back Affordance Shape Is Missed

The saved `SIMs` XML shows the `Navigate up` control as:

- clickable parent: `android.view.View`, empty text/content-description/resource id, bounds `[196,147][322,273]`
- nested child: `android.view.View`, `content-description="Navigate up"`, not clickable

`EntryScreenBackAffordanceDetector.isLikelyInAppBackAffordance()` checks only the candidate clickable node's own `contentDescription`, `text`, and `viewIdResourceName`:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:281-304`

It does not derive the clickable parent's label from descendants the way `AccessibilityTreeSnapshotter.resolveElementLabel()` does for pressable elements.

### Fallback Clicks Are Too Broad

When path replay diverges, `AppToHtmlAccessibilityService.performClick()` tries path candidates, then `collectClickableCandidates(root, element)`:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:396-426`

`clickableCandidateScore()` returns a positive score for any visible enabled clickable/action-click node before requiring a semantic match:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:505-536`

The base score is `100 - depth`; label/resource id/class/bounds only add confidence. Therefore a stale `Network & internet` route step on the `SIMs` screen can click the carrier row even though the label and class do not match.

### Replay Success Is Only "Changed Fingerprint"

`DeepCrawlCoordinator.replayRouteToScreen()` checks each route step by comparing before/after logical viewport fingerprints:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1188-1217`

If the fingerprint changes, replay continues. It does not verify that the changed screen is the intended parent or destination. Destination identity validation happens later in `prepareScreenForExpansion()`:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:734-760`

That is why `crawl.log:3618` can say replay succeeded and `crawl.log:3644-3646` can later say the route diverged.

## Root Cause

The root cause is not simply that `SIMs` contains a clickable `T-Mobile` row. The crawler should handle a navigable row. The failure is that recovery/replay can operate from a stale live screen and then accept low-confidence fallback clicks.

The most direct cause of the repeated `SIMs`/`T-Mobile` oscillation is:

`restoreToEntryScreenOrRelaunch()` returns `SIMs`/carrier UI as replayable entry state because back-affordance detection misses the Compose `Navigate up` structure and the expected entry fingerprint is not enforced after `NO_BACK_AFFORDANCE`; then fallback click resolution clicks the `T-Mobile` row while replaying unrelated route labels; finally route replay reports success on any changed fingerprint until delayed validation catches the wrong `T-Mobile` destination.

## High-Value Fix Areas

This investigation was read-only, but the code points to three focused fix areas:

1. Enforce expected entry fingerprint when `expectedEntryLogicalFingerprint` is present. `NO_BACK_AFFORDANCE` alone should not be enough unless the logical entry fingerprint also matches.
2. Teach `EntryScreenBackAffordanceDetector` to derive labels from descendants for clickable containers, matching `AccessibilityTreeSnapshotter.resolveElementLabel()` behavior.
3. Tighten fallback click selection so fallback candidates need a minimum semantic or bounds confidence, especially after path divergence.

An additional hardening layer would be to validate each replay step against the expected parent/route identity instead of only requiring any fingerprint change.

## Metadata

- Repository: `AppToHTML`
- Branch: `codex/external-package-continue-foreground`
- Commit: `db06aad89705d189c2fff684de15d6c83c1f9740`
- Crawl folder: `E:\Logs\com.android.settings\crawl_20260428_125014`
- Prior analysis: `documentation/crawl-20260428-sims-tmobile-analysis.md`
