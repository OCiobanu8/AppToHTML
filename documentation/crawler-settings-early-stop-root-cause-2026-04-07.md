# Settings Crawl Early-Stop Root Cause 2026-04-07

## Summary

This note captures the confirmed reason the `com.android.settings` crawl in `E:\Logs\com.android.settings\crawl_20260407_163033` stopped much earlier than expected.

The key conclusion is:

the crawl did not crash, but it exhausted its reachable frontier because replay and recovery kept accepting the wrong live UI state as the root entry screen

More specifically:

- the run finished with `status=completed`, not `failed` or `partial_abort`
- the crawler captured only `17` screens even though the root traversal plan alone had `19` eligible targets and the `Network & internet` screen had another `7`
- after the crawler reached a `T-Mobile` details view, later replay attempts for queued screens frequently restored to a state that was treated as "back at entry" even though the visible destination content was still wrong
- once that happened, queued routes such as `Display & touch`, `Storage`, `Internet`, and `SIMs` failed replay validation and were dropped as recoverable failures

The dominant defect is:

false-positive entry-screen restoration on a split-pane Settings layout, which poisons route replay and causes broad branch loss

## Artifacts Reviewed

- investigation target: `E:\Logs\com.android.settings\crawl_20260407_163033`
- crawl log: `E:\Logs\com.android.settings\crawl_20260407_163033\crawl.log`
- manifest: `E:\Logs\com.android.settings\crawl_20260407_163033\crawl-index.json`
- relevant source:
  - `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
  - `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`

## Final Outcome Of The Run

The run ends cleanly:

- `crawl.log:1432` shows `crawl_complete status=completed capturedScreenCount=17 capturedChildScreenCount=16 skippedElementCount=8 maxDepthReached=2`

So this is not a hard failure. The crawler believed it had completed traversal.

That "completed" status is misleading in practice because the frontier was drained after many queued branches became unreplayable.

## Why This Looks Early

The root screen had a large reachable frontier:

- `crawl.log:44` shows `traversal_plan screenId=screen_000 ... eligibleCount=19 skippedCount=2`

One captured child screen also had its own frontier:

- `crawl.log:789` shows `traversal_plan screenId=screen_001 screenName="Network & internet" eligibleCount=7 skippedCount=2`

But the final run captured only `17` screens total:

- root
- sixteen child screens

That means a large number of branches were never traversed successfully even though they were discovered and queued.

## Confirmed Failure Pattern

### 1. Recovery repeatedly claims the crawler is back at the entry screen

The log contains many repetitions of:

- `crawl.log:50`
- `crawl.log:101`
- `crawl.log:1212`
- `crawl.log:1372`

Each of those lines reports:

- `entry_restore_result strategy=restore_to_entry stopReason=no_back_affordance matchedExpectedLogical=true`

That means the crawler believes recovery has successfully returned to the root entry screen.

### 2. Replay then lands on the wrong screen

Later queued routes fail because the actual live destination does not match the captured destination:

- `crawl.log:1209` shows `Route replay diverged for 'Display & touch'. Expected 'Display & touch' but found 'T-Mobile'.`
- `crawl.log:1425` shows `Route replay diverged for 'SIMs'. Expected 'SIMs' but found 'T-Mobile'.`

This is the clearest evidence that recovery is restoring to a state that still contains the wrong active content pane.

### 3. Some routes fail even earlier because the crawler can no longer replay the required scroll position

Once the entry state is wrong, replay can also fail before the click:

- `crawl.log:1221`
- `crawl.log:1222`

Those lines show:

- `Could not scroll back to 'Storage' while replaying 'Storage'.`

This is consistent with replay starting from the wrong live surface, so the saved `firstSeenStep` and path no longer correspond to the visible tree.

### 4. The run drops branches and continues instead of aborting

The coordinator treats these as recoverable replay failures, records failed edges, and keeps draining the remaining frontier.

That is why the run ends as `completed` instead of `partial_abort`.

## What The Evidence Says About The UI

The most likely interpretation is that this Settings build is using a split-pane or large-screen layout.

This is an inference from the logs, not from a screenshot, but it is strongly supported by the pattern:

- the unexpected screen is repeatedly `T-Mobile`, which looks like a details pane inside Settings rather than a different app
- entry restoration frequently reports `no_back_affordance`, which suggests the UI may not expose a normal in-app back button while still showing nested detail content
- replay of root items later resolves only part of the saved path and falls back to generic visible views instead of cleanly resolving the original list item path

The important behavior is:

the crawler is using "no visible in-app back affordance" plus an entry logical fingerprint as proof that it is back at the true root screen, but on this layout that proof is not strong enough

## Code Paths That Explain The Behavior

### `ScrollScanCoordinator.rewindToEntryScreen`

The relevant logic is in `ScrollScanCoordinator.kt`:

- `ScrollScanCoordinator.kt:120-121` returns success immediately when `logicalEntryViewportFingerprint(currentRoot) == expectedEntryLogicalFingerprint`
- `ScrollScanCoordinator.kt:130` also returns success when no visible in-app back affordance is found

This means recovery can succeed without proving that the primary content pane matches the originally captured root screen.

### `DeepCrawlCoordinator.restoreToEntryScreenOrRelaunch`

The caller in `DeepCrawlCoordinator.kt` trusts that result:

- `DeepCrawlCoordinator.kt:825` logs whether an expected entry logical fingerprint was provided
- `DeepCrawlCoordinator.kt:828` starts the `currentRoot?.packageName == selectedApp.packageName` restoration branch
- `DeepCrawlCoordinator.kt:843` logs `matchedExpectedLogical`

If recovery returns `NO_BACK_AFFORDANCE`, the coordinator accepts the state as restored and proceeds with replay.

### Replay validation fails later, not at restore time

The divergence is only caught after replay reaches the queued destination and the live screen is rescanned:

- `DeepCrawlCoordinator.kt:537` begins `prepareScreenForExpansion`
- `DeepCrawlCoordinator.kt:569` logs `replay_validation`
- `DeepCrawlCoordinator.kt:583` emits the failure message `Route replay diverged ... Expected ... but found ...`

So the bug is not that replay validation is missing. The bug is that the crawler is often starting replay from the wrong "restored" root state.

### The same run also shows weak no-navigation detection

The edge opening logic treats an edge as non-navigating when the after-click logical fingerprint matches the pre-click or top fingerprint:

- `DeepCrawlCoordinator.kt:366`

That likely explains some skipped root items such as:

- `crawl.log:143` for `Apps`

This may be related to the same layout family, but it is a secondary issue. The dominant early-stop behavior comes from false-positive recovery and poisoned replay.

## Root Cause

The root cause is:

entry-screen restoration is too permissive for split-pane Settings layouts

In concrete terms:

1. The crawler stores an entry logical fingerprint for the root screen.
2. Later, after exploring deeper content, it tries to "restore" to that entry screen.
3. `rewindToEntryScreen` treats either of these as success:
   - matching the entry logical fingerprint
   - having no visible in-app back affordance
4. On this Settings layout, those checks can both pass even while the active details pane is still something like `T-Mobile`.
5. Replay then starts from the wrong live surface.
6. Saved routes no longer map cleanly onto the live tree.
7. Queued branches fail replay validation or scroll-step replay and are marked failed.
8. The crawler drains the frontier and reports `completed`, but the crawl is effectively incomplete.

## Why `T-Mobile` Keeps Appearing

`T-Mobile` is not random noise. It is the strongest clue in the run.

Representative lines:

- `crawl.log:1209` expected `Display & touch`, found `T-Mobile`
- `crawl.log:1425` expected `SIMs`, found `T-Mobile`

That means multiple independent queued routes are converging onto the same incorrect live state. This is exactly what you would expect if recovery keeps returning to a shared split-pane details page instead of the true root entry pane.

If this were a normal one-off click miss, the failures would likely fan out to several wrong destinations. Instead they converge on one stable wrong destination.

## Fix Options

### Option 1: Strengthen entry restoration with root screen identity validation

This is the best long-term fix.

Change recovery so that returning to entry requires more than the current entry logical fingerprint or lack of a back affordance. After recovery says it is back at entry, immediately rescan and compare against the captured root screen identity.

Practical shape:

- store the captured root screen fingerprint alongside the existing entry logical fingerprint
- after `restoreToEntryScreenOrRelaunch`, scan the live screen and compare its screen identity to the root screen identity
- if the screen identity is not the original root screen, relaunch instead of trusting the restored state

Pros:

- fixes the actual defect
- works for this Settings case and similar split-pane apps
- keeps replay logic mostly intact

Cons:

- adds an extra capture and screen-identity check during recovery
- may require care to avoid over-relaunching on weakly named root screens

### Option 2: Prefer relaunch when the entry layout is ambiguous

This is the simplest high-confidence reliability fix.

Treat split-pane or ambiguous entry layouts as non-restorable and relaunch before replay or before expanding a queued screen.

Practical shape:

- add a heuristic for ambiguous entry screens such as homepage markers plus persistent side navigation or pane-like layouts
- when ambiguous, skip "restore_to_entry" as a trusted strategy and relaunch immediately

Pros:

- very robust
- avoids subtle state restoration mistakes
- easier to reason about than richer logical matching

Cons:

- slower
- less elegant
- may hide underlying recovery weaknesses instead of improving them

### Option 3: Add a Settings-specific split-pane workaround

This is the fastest narrow fix.

Special-case Android Settings when the root screen identity looks like `settings_homepage` and the replay target later resolves to a detail screen like `T-Mobile`.

Practical shape:

- detect `settings_homepage` entry screens
- if replay validation for a queued screen resolves to a different details pane, relaunch and retry once from a clean app start
- optionally add stronger path resolution for root-list items on this screen

Pros:

- quickest path to unblocking this exact crawl target
- limited blast radius

Cons:

- narrow and app-specific
- does not help the next split-pane app with the same failure mode

### Option 4: Improve no-navigation detection for pane updates

This is a secondary hardening step, not the primary fix.

Some root items were classified as `SKIPPED_NO_NAVIGATION`, for example `Apps` at `crawl.log:143`. That suggests the current logical fingerprint comparison may also be too weak to notice content-pane changes on this layout.

Possible improvement:

- compare screen identity after click, not only logical viewport fingerprint
- treat a changed screen identity as navigation even if the left navigation pane stayed stable

Pros:

- improves coverage on split-pane layouts
- reduces false "did not navigate" classifications

Cons:

- does not solve the main replay-restoration bug by itself

## Recommended Direction

If the goal is a proper fix rather than a narrow workaround, the best sequence is:

1. implement Option 1
2. add the screen-identity-based navigation check from Option 4 where it is cheap
3. keep Option 2 as a fallback policy if ambiguous entry layouts still prove unstable

That sequence fixes the root cause first and then hardens the same layout family in the edge-opening path.

## How To Verify The Fix

After implementing a fix, rerun the same target and confirm:

- the crawler no longer reports repeated `entry_restore_result ... matchedExpectedLogical=true` immediately before wrong-screen replay
- queued screens such as `Display & touch`, `Storage`, `Internet`, and `SIMs` replay to their own screen identities instead of `T-Mobile`
- the run captures materially more than `17` screens
- the root and `Network & internet` frontier items are no longer mostly lost to replay failure
- root entries previously marked `SKIPPED_NO_NAVIGATION`, especially `Apps`, are revisited to confirm whether they now produce distinct child captures

## Conclusion

The crawl stopped early because recovery kept saying "we are back at the Settings entry screen" when that was not actually true.

Once the crawler accepted that false root state, later queued branches replayed against the wrong live UI and were dropped one by one. The run therefore ended as `completed`, but only because the frontier became empty after widespread replay loss.

The correct root-cause statement for this run is:

false-positive entry restoration on a split-pane Settings layout, causing replay divergence and silent frontier loss
