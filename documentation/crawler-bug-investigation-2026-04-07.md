# Crawler Bug Investigation 2026-04-07

## Summary

This note captures the confirmed root cause of the crawler failure that surfaced in the UI as:

`Failed to crawl the target app: length=1; index=1.`

Artifacts reviewed for this update:

- investigation target: `E:/Logs/crawl_20260407_112741`
- log file: `E:/Logs/crawl_20260407_112741/crawl.log`
- manifest: `E:/Logs/crawl_20260407_112741/crawl-index.json`
- key snapshots:
  - `001_child_navigate_up_merged_accessibility.xml`
  - `002_child_network_internet_merged_accessibility.xml`

Target package:

- `com.android.settings`

## Confirmed Failure Site

The crash is now confirmed by the stack trace in `crawl.log`.

Top-level throwable:

- `java.lang.ArrayIndexOutOfBoundsException: length=1; index=1`

Relevant stack frames:

- `android.view.accessibility.AccessibilityNodeInfo.getChild(...)`
- `com.example.apptohtml.AppToHtmlAccessibilityService.resolvePathNodes(AppToHtmlAccessibilityService.kt:489)`
- `com.example.apptohtml.AppToHtmlAccessibilityService.performClick(AppToHtmlAccessibilityService.kt:449)`
- `com.example.apptohtml.crawler.DeepCrawlCoordinator.replayRouteToScreen(DeepCrawlCoordinator.kt:698)`

This confirms that the immediate crash happens while replaying a stored `childIndexPath` against a live accessibility tree whose shape no longer matches the captured path.

## What The Run Shows

### 1. The crawler captured a weakly named screen from the `Google` entry

The first suspicious child capture is:

- parent screen: root Settings screen
- clicked element: `"Google"`
- captured child screen: `screen_001`
- chosen name: `"Navigate up"`
- dedup fingerprint: `v1:name:navigate up`

The naming log shows the winning candidates were dominated by toolbar/chrome text:

- `Navigate up`
- `More options`
- `Google services`

This is already a strong signal that the naming pipeline over-weighted a generic back affordance instead of the logical destination.

### 2. The captured `screen_001` is a Google Services screen, not a logical screen named `Navigate up`

The merged snapshot `001_child_navigate_up_merged_accessibility.xml` shows:

- package: `com.google.android.gms`
- top chrome labels such as `Recommended`, `All services`, and `Navigate up`
- body content such as `Connected devices & sharing`, `Devices`, `Thread networks`, `Ads`, and other Google settings entries

So the crawler did not really discover a screen whose identity is `Navigate up`. It discovered a Google Services page whose title selection was weak.

### 3. Dedup then linked an unrelated screen to the same weak fingerprint

Later, from `screen_002` (`Network & internet`), the crawler clicks:

- `"SIMs"`

That produces:

- candidate screen name: `"Navigate up"`
- candidate fingerprint: `v1:name:navigate up`

The manifest and log both show the result:

- `edge_055`
- `status = "linked_existing"`
- child screen id reused: `screen_001`
- message: `Linked to existing screen 'Navigate up'.`

This is a false-positive dedup. The `SIMs` destination is not the same screen as the earlier Google Services page, but both collided because dedup only used the weak screen name fingerprint.

### 4. Replay then runs against the wrong logical screen

After that bad link, the crawler repeatedly replays `screen_001` and lands on the Google Services page. The run then logs many recoverable failures like:

- `The screen 'Navigate up' no longer matches its captured top state before opening ...`
- `Could not scroll back to ... on 'Navigate up'.`

This is consistent with the crawler trying to use `screen_001` as a reusable logical destination even though the dedup merged unrelated screens under the same identity.

### 5. The final crash is stale path replay, not just a naming issue

After the false dedup, replay returns to `screen_002` and starts replaying the route step for `Network & internet` again. During that replay, `resolvePathNodes(...)` attempts to walk a stored child path and calls `getChild(1)` on a node whose live child array length is only `1`.

That is the direct crash.

So the naming/dedup bug explains why the crawler gets onto the wrong branch, and the unsafe `childIndexPath` replay explains why that bad branch terminates the run with an unhandled exception.

## Root Cause

This run confirms a combined failure:

### Root cause A: unsafe live replay of stored `childIndexPath`

- `resolvePathNodes(...)` assumes `AccessibilityNodeInfo.getChild(index)` is safe for any stored index.
- On a changed live tree, that assumption is false.
- The crawler crashes instead of treating the path mismatch as a recoverable replay failure.

### Root cause B: weak screen identity and dedup

- `ScreenNaming.dedupFingerprint(...)` currently fingerprints only the chosen screen name.
- The chosen screen name can be a generic toolbar or back affordance such as `Navigate up`.
- Different logical screens can therefore collapse to the same dedup fingerprint.
- In this run, that caused the `SIMs` destination to be incorrectly linked to the earlier Google Services page.

## Most Likely Fix Direction

The best fix is a layered change rather than a single guardrail:

- make path replay bounds-safe so stale live trees cannot crash the crawler
- improve screen naming so generic affordance labels do not become canonical screen titles
- strengthen dedup so it does not rely on screen name alone when deciding `linked_existing`

This addresses both the hard crash and the upstream identity error that made the replay path much more likely to diverge.

## Logger Implications

The updated logs were enough to confirm root cause. The following logging remains worth keeping or extending:

- chosen screen name and dedup fingerprint for every saved screen
- naming inputs and top text candidates
- every `linked_existing` decision with both candidate and matched fingerprints
- replay attempts with destination screen id and expected fingerprint
- intended `childIndexPath` plus actual resolved depth
- full throwable stack traces for unexpected crawler crashes
- an explicit log when path replay diverges before the stored path is fully resolved

## Conclusion

The bug is now confirmed as a two-layer failure:

1. The crawler incorrectly merged unrelated screens because screen identity was based on a weak name, `Navigate up`.
2. After that bad merge, route replay used a stale `childIndexPath` on a changed live tree and crashed with `ArrayIndexOutOfBoundsException`.

The immediate crash site is `AppToHtmlAccessibilityService.resolvePathNodes(...)`, but the upstream dedup mistake is also part of the real defect. Fixing only the crash guard would stop the hard failure, but it would still leave incorrect screen linking and unstable traversal behavior in place.
