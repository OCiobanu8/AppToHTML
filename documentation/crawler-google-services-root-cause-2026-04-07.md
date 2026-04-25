# Crawler Google Services Root Cause 2026-04-07

## Summary

This note captures the confirmed root cause behind the crawler getting stuck retrying the `Google services` screen in the run stored at `E:\Logs\crawl_20260407_121131`.

The key conclusion is that the retry loop is not primarily caused by the visible tabs on the screen. The crawler successfully replays back to `Google services` many times, but then fails a stricter top-of-screen validation step because the screen returns with shifted absolute bounds. The current validation fingerprint includes raw geometry, so a layout-stable screen in logical terms can still fail validation if Compose re-enters it with slightly different coordinates.

The final diagnosis is: geometry-sensitive replay validation on a layout-shifting Compose screen.

## Artifacts Reviewed

- log bundle: `E:\Logs\crawl_20260407_121131`
- crawl log: `E:\Logs\crawl_20260407_121131\crawl.log`
- manifest: `E:\Logs\crawl_20260407_121131\crawl-index.json`
- HTML snapshot: `E:\Logs\crawl_20260407_121131\001_child_google_services.html`
- merged accessibility snapshot: `E:\Logs\crawl_20260407_121131\001_child_google_services_merged_accessibility.xml`
- relevant source:
  - `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
  - `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`

This note discusses only the `crawl_20260407_121131` run and does not rely on evidence from the earlier `112741` investigation.

## Confirmed Failure Pattern

The run captures `Google services` successfully, replays back to it successfully, and repeatedly fails only after replay when validating the top-of-screen state before opening an edge.

Representative log sequence:

- initial candidate capture:
  - `crawl.log:126` shows `candidateScreenName="Google services"`
- saved screen record:
  - `crawl.log:128` shows `screen_capture screenId=screen_001 ... screenName="Google services"`
- successful replay to the same screen:
  - `crawl.log:915` shows `replay_result ... destinationScreenName="Google services" success=true`
  - `crawl.log:978` shows replay dedup validation succeeded because expected and actual screen fingerprints are the same
- repeated top-state validation failures:
  - `crawl.log:1028`
  - `crawl.log:1072`
  - `crawl.log:1116`
  - `crawl.log:1338`
  - `crawl.log:1514`
- final run outcome:
  - `crawl.log:1523` shows `crawl_complete status=partial_abort`

This means route replay to the screen is working. The failure happens later.

## What The Run Shows

### 1. The crawler reaches and records `Google services` normally

The crawler enters the screen from the root Settings page via the `Google` entry:

- `crawl.log:126` records `child_capture_candidate ... route="Google@0" candidateScreenName="Google services"`
- `crawl.log:128` records `screen_capture screenId=screen_001 ... screenName="Google services"`

The captured screen fingerprint is stable at the screen-identity level:

- `v2:pkg:com_google_android_gms:title:google_services:hint:card_sign_in`

So there is no evidence that the crawler misidentified the destination screen itself.

### 2. Replay back to `Google services` succeeds repeatedly

The run replays back to `screen_001` over and over, and those replays succeed:

- `crawl.log:915`
- `crawl.log:1003`
- `crawl.log:1047`
- `crawl.log:1091`
- `crawl.log:1135`
- `crawl.log:1179`
- `crawl.log:1223`
- `crawl.log:1267`
- `crawl.log:1313`
- `crawl.log:1357`
- `crawl.log:1401`
- `crawl.log:1445`
- `crawl.log:1489`

The replay validation at `crawl.log:978` also confirms that the expected screen fingerprint and actual screen fingerprint match:

- expected: `v2:pkg:com_google_android_gms:title:google_services:hint:card_sign_in`
- actual: `v2:pkg:com_google_android_gms:title:google_services:hint:card_sign_in`

This is strong evidence that the crawler is not getting lost on the way back to the screen.

### 3. The screen fails after replay during top-of-screen validation

Once replay succeeds, the crawler rewinds to the top of the screen and then compares a live top fingerprint against the captured top fingerprint before opening the target edge.

That check fails repeatedly:

- `crawl.log:1028` before opening `More options`
- `crawl.log:1072` before opening `All services`
- `crawl.log:1116` before opening `Give feedback`
- `crawl.log:1160` before opening `Recommended`
- `crawl.log:1204` before opening `Cast options`
- `crawl.log:1248` before opening `Cross-device services`
- `crawl.log:1292` before opening `Devices`
- `crawl.log:1338` before opening `Thread networks`
- `crawl.log:1382` before opening `Ads`
- `crawl.log:1426` before opening `Location Accuracy`
- `crawl.log:1470` before opening `Usage & diagnostics`
- `crawl.log:1514` before opening `Autofill with Google`

Each mismatch leads to a recoverable edge failure and another recovery or replay attempt, which creates the visible retry loop.

### 4. The content remains materially the same while bounds drift

Across mismatches, the visible top-level content is still recognizably the same screen:

- `All services`
- `Give feedback`
- `More options`
- `Navigate up`
- `Sign in`

What changes is the raw geometry inside the fingerprint.

Examples from the log:

- `crawl.log:1028` compares an expected root of `[184,0][1264,2400]` with an actual root of `[184,0][1264,2400]`, but the `More options` and `Navigate up` bounds shifted
- `crawl.log:1072` compares `[184,0][1264,2400]` against `[188,0][1268,2400]`
- `crawl.log:1116` compares `[184,0][1264,2400]` against `[113,0][1193,2400]`
- `crawl.log:1292` compares `[184,0][1264,2400]` against `[110,0][1190,2400]`

The accessibility snapshot also shows this is a Compose-backed Google Play Services screen with tabs and pressable content rather than a classic static Settings page:

- `001_child_google_services_merged_accessibility.xml` contains `androidx.compose.ui.platform.ComposeView`
- the same snapshot contains both tab labels `Recommended` and `All services`

## Why Tabs Are Not The Root Cause

The visible tabs are a contributor to fingerprint instability, but they are not the primary root cause.

Reasons:

- the first failure already occurs after a successful replay back to `Google services`, before the crawler has meaningfully traversed tab content
- `crawl.log:915` shows replay succeeded
- `crawl.log:1028` shows the first top-state mismatch before opening `More options`
- the route-level screen identity remains stable, as shown by `crawl.log:978`

In other words:

- the crawler is able to get back to the right screen
- the screen still looks logically the same
- the retry loop starts because geometry-sensitive validation rejects the top state

The tab strip matters only because its absolute bounds are included in the fingerprint. If those bounds shift when Compose re-lays out the screen, the fingerprint changes even when the logical screen did not.

So the tabs are incidental input to the unstable fingerprint, not the underlying defect.

## Actual Root Cause

The root cause is geometry-sensitive replay validation on a layout-shifting Compose screen.

More specifically:

1. `DeepCrawlCoordinator` captures a top-of-screen fingerprint for `Google services`.
2. After replaying back to the screen and rewinding to top, it recomputes the live top fingerprint.
3. That comparison uses fingerprints built from raw `root.bounds` and raw per-element `bounds`.
4. The `Google services` Compose layout re-enters with different absolute coordinates across runs and recoveries.
5. The fingerprints therefore mismatch even though the screen content is materially the same.
6. The crawler treats the screen as no longer matching its captured top state, marks the edge as recoverable, retries, and eventually aborts partially.

This is why the run keeps retrying the same screen without making forward progress.

## Code Paths Involved

### `DeepCrawlCoordinator.kt`

The failure happens in the top-state validation path:

- `DeepCrawlCoordinator.kt:226-233` chooses the expected top fingerprint
- `DeepCrawlCoordinator.kt:281-289` rewinds the live screen back to the top
- `DeepCrawlCoordinator.kt:291-299` computes `liveTopFingerprint` and logs `screen_top_validation`
- `DeepCrawlCoordinator.kt:300-305` fails the current edge when the fingerprints differ

This is the exact stage reflected by the repeated log lines such as `crawl.log:1028` and `crawl.log:1514`.

### `ScrollScanCoordinator.kt`

The fingerprint functions are geometry-sensitive by construction:

- `ScrollScanCoordinator.kt:237-257` defines `viewportFingerprint`
- `ScrollScanCoordinator.kt:260-281` defines `entryViewportFingerprint`

Both functions include:

- `element.bounds`
- `root.bounds`

That means any shift in root position or pressable element position changes the fingerprint, even if the visible structure and labels are effectively unchanged.

## Fix Implications

The later fix plan should target replay validation rather than treating tabs as a special-case screen type.

Most likely implications:

- make top-state validation less sensitive to raw geometry drift
- separate logical screen identity from transient layout coordinates
- decide whether bounds should be removed, normalized, bucketed, or used only selectively for replay validation
- keep tabs out of the direct blame model unless later evidence shows tab selection itself is mutating state

The important takeaway is that any fix aimed only at blacklisting or skipping tabs would be too narrow. The failure mode is broader: the validation fingerprint is too strict for screens that re-enter with layout shifts.

## Conclusion

This run shows a consistent pattern:

- `Google services` is captured successfully
- replay back to `Google services` succeeds repeatedly
- the crawler then fails during top-of-screen validation, not during replay
- the mismatch is driven by raw coordinate changes in a Compose-backed layout whose visible content remains materially the same

The correct root-cause statement for this run is:

geometry-sensitive replay validation on a layout-shifting Compose screen

The tabs on `Google services` are incidental rather than primary. They contribute to fingerprint instability only because their bounds are included in the current fingerprint, not because they independently break the traversal flow.
