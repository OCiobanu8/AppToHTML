# Crawl 20260428 SIMs/T-Mobile End-State Analysis

## Source Artifacts

- Crawl folder: `E:\Logs\com.android.settings\crawl_20260428_125014`
- Crawl log: `E:\Logs\com.android.settings\crawl_20260428_125014\crawl.log`
- Recording: `E:\Logs\com.android.settings\crawl_20260428_125014\Screen_recording_20260428_125042.webm`
- Recording sampler used for inspection: `C:\Users\octak\AndroidStudioProjects\AppToHTML\tmp\video_sampler.html`

## Summary

At the end of the crawl, the crawler is not stuck in an infinite loop, but the recording does show repeated oscillation between the `SIMs` screen and the `T-Mobile` carrier detail screen. The earlier summary was too narrow: this is not only a final `SIMs -> T-Mobile` end state. The live UI goes back and forth between those two screens several times during the last part of the crawl.

The saved `SIMs` screen is still valid. The problem is that the live route/recovery state is unstable around the `SIMs` carrier row: the crawler repeatedly lands on `SIMs`, opens or is left on the `T-Mobile` carrier detail screen, restores back to `SIMs`, and later enters `T-Mobile` again. The final validation then detects that the expected screen identity was `SIMs`, but the actual screen identity is `T-Mobile`, records a route divergence, recovers, and completes the crawl.

## Timeline From `crawl.log`

Relevant tail range: approximately lines `3581-3653`.

- `12:56:32.149`: while replaying `screen_037` / `Internet`, the crawler tries to click `Network & internet`.
- `12:56:32.892`: the before/after logical fingerprints both contain `Mobile data`, `Navigate up`, and `T-Mobile`; the click did not navigate. This means the live UI was already effectively on the `SIMs`/carrier area while the replay expected to start from the root route.
- `12:56:32.893`: warning logged: `Clicking 'Network & internet' did not navigate while replaying 'Internet'.`
- `12:56:33.254` to `12:56:33.965`: recovery runs and reports `matchedExpectedLogical=true`.
- `12:56:33.990`: `screen_038` is dequeued as `SIMs`.
- `12:56:33.990`: replay starts for route `Network & internet@0 -> SIMs@0`.
- `12:56:34.345`: step 0 begins from the root by clicking `Network & internet`.
- `12:56:35.127`: step 1 begins from `screen_002` by clicking `SIMs`.
- `12:56:35.189`: fallback click for `SIMs` succeeds on `android.widget.LinearLayout[]@[0,1340][1080,1546]`.
- `12:56:35.543`: after clicking `SIMs`, the fingerprint already contains carrier-detail content: `App data usage`, `Preferred network type`, `Roaming`, `Use this SIM`, and related `T-Mobile` detail fields.
- `12:56:35.544`: route replay reports success for destination `SIMs`.
- `12:56:35.915` to `12:56:36.756`: the crawler attempts scroll-backward and scroll-forward actions while validating the destination.
- `12:56:37.484`: validation expected `v2:pkg:com_android_settings:title:sims:hint:t_mobile|1_555_123_4567`, but the actual fingerprint is `v2:pkg:com_android_settings:title:t_mobile:hint:collapsing_toolbar`.
- `12:56:37.490`: the crawler names the current screen `T-Mobile`.
- `12:56:37.490`: warning logged: `Route replay diverged for 'SIMs'. Expected 'SIMs' but found 'T-Mobile'.`
- `12:56:38.570`: recovery succeeds.
- `12:56:38.620`: crawl completes with `capturedScreenCount=39`, `capturedChildScreenCount=38`, `skippedElementCount=13`, `maxDepthReached=2`.

## Saved Screen Evidence

The saved `038_child_sims.xml` is genuinely the `SIMs` screen. It contains:

- Title text: `SIMs`
- A clickable `T-Mobile` row with phone summary
- A selected `T-Mobile` checkable control
- A `Mobile data` toggle
- `Navigate up`

This matters because the `SIMs` screen has a same-screen clickable carrier row. Pressing that row enters the carrier-specific detail page titled `T-Mobile`.

The `002_child_network_internet.xml` route source also shows:

- `Internet` at child index path `0.1.0`
- `SIMs` at child index path `0.1.1`
- `SIMs` summary: `T-Mobile`

## Recording Evidence

The WebM was sampled through the local HTML sampler at `tmp/video_sampler.html`. The recording starts at `12:50:42`, so video timestamp `322s` corresponds to approximately `12:56:04`, and `358s` corresponds to approximately `12:56:40`.

Exact sampled timestamps:

- `322s` / `324s`: the device is on `SIMs`, showing the `T-Mobile` row and `Mobile data`.
- `326s`: the device is on `T-Mobile`, showing `Use this SIM`, data usage, `Mobile network`, `Phone number`, `Roaming`, `App data usage`, and `Data warning & limit`.
- `328s`: the device is back on `SIMs`.
- `330s` / `332s`: the device is back on `T-Mobile`.
- `334s` through `346s`: the device is again on `SIMs` for a long stretch.
- `348s`: a transition/overlay shows `SIMs` and `T-Mobile` content briefly overlapping, consistent with navigation from `SIMs` into the carrier detail page.
- `350s`: the device is fully on the top of the `T-Mobile` detail page.
- `352s` / `354s`: the device returns to `SIMs`.
- `356s`: the device is on a lower scrolled position of the `T-Mobile` detail page, showing `App data usage`, `Data warning & limit`, `Preferred network type`, `IMEI`, and network selection controls.
- `358s` / `360s`: the device returns to the AppToHTML app after recovery/completion.

So the video sequence near the end is:

`SIMs -> T-Mobile -> SIMs -> T-Mobile -> SIMs -> T-Mobile -> SIMs -> T-Mobile -> AppToHTML`

## Most Likely Cause

The crawler reaches `SIMs`, but the live UI state around that route is unstable because the `SIMs` screen contains a clickable `T-Mobile` carrier row that opens the carrier-specific detail page. The repeated video transitions suggest this is not a single accidental click at the final validation moment. The crawler is repeatedly restoring or navigating back to `SIMs`, then entering or remaining in `T-Mobile` as it replays routes and performs validation/scroll actions.

Two log details line up with the video:

- The `Internet` replay failure at `12:56:32.892` sees a `T-Mobile`/`Mobile data` fingerprint before and after trying to click `Network & internet`, which matches the video being on or near `T-Mobile` around `350s`.
- The final `SIMs` replay at `12:56:35.543` reports route success even though the post-click fingerprint contains `T-Mobile` detail-page fields, then validation at `12:56:37.484` correctly identifies the actual screen as `T-Mobile`.

In short: the back-and-forth is real in the video. It looks like repeated recovery/replay through an unstable `SIMs` route whose first actionable row is also a navigation target into `T-Mobile`. It still terminates, but the route replay success signal is too permissive because it can mark the `SIMs` route as successful while the live UI is already on carrier-detail content.

## Graph Outcome

The graph records:

- `screen_002 -> screen_038` via label `SIMs` as captured.
- A later failed replay edge from `screen_002` with label `SIMs`, message: `Route replay diverged for 'SIMs'. Expected 'SIMs' but found 'T-Mobile'.`

The crawl manifest still ends with `status=completed`.

## Implemented Fix

The fix landed under
`thoughts/shared/plans/2026-04-28-ENG-0009-entry-restore-replay-hardening.md`.
It addresses the SIMs/T-Mobile oscillation through three reinforcing changes:

- `EntryScreenBackAffordanceDetector` now resolves descendant labels for
  top/toolbar-aligned clickable parents. Compose `Navigate up` containers are
  no longer missed, so the crawler stops accepting carrier sub-screens as
  entry-restored.
- `ClickFallbackMatcher` extracts fallback scoring from the accessibility
  service into a JVM-testable matcher that gates eligibility on resource id,
  resolved label, class plus bounds, or strong bounds for icon-only controls.
  Class-only or check-state-only matches are rejected, so the previous
  `T-Mobile` row could no longer satisfy a `Network & internet` route step.
- `replayRouteToScreen(...)` validates each step against the screen identity
  it should reach using the bounds-free `replayFingerprint`. A mismatch fails
  the route at the first bad step with `replay_route_step_validation` logs,
  rather than silently treating a fingerprint change as success and only
  catching the divergence later in `prepareScreenForExpansion(...)`.
