# Google Services Destination Settling Research

## Research Question

Continue the analysis from `documentation/crawl-20260428-google-services-continue-analysis.md` and document how the codebase currently handles post-click destination capture, external-package continue restore validation, and the likely implementation surface for a fix.

## Summary

The Google Services continue failure is caused by the post-click destination observer accepting the first changed accessibility snapshot as authoritative. In `crawl_20260428_151359`, the first changed GMS snapshot contained only `More options`, so `DeepCrawlCoordinator` stored that sparse fingerprint as the expected external destination. After the user selected Continue, the coordinator restored the same external package and observed a fuller, more useful GMS screen containing `All services`, `Give feedback`, `More options`, and `Sign in`; because external-boundary restore requires exact fingerprint equality with the earlier sparse snapshot, the edge was failed before any GMS screen artifact could be written.

This is not limited to Google Services. The same crawl shows `Digital Wellbeing & parental controls` failing the same way: the first external observation was an empty `android.widget.FrameLayout::` fingerprint, then the post-continue restore observed the loaded Wellbeing screen and failed exact validation.

The core current-state issue is that the crawler has viewport settling for scroll scans, but it does not have destination settling for clicked transitions. The post-click loop retries while nothing changes or while an expected package is absent, but once it sees either a package change or a logical fingerprint change, it immediately returns that snapshot.

## Evidence From Crawl Artifacts

The inspected crawl folder exists locally at `E:\Logs\com.android.settings\crawl_20260428_151359`.

For the root Settings `Google` edge:

- `crawl.log:47` starts edge 1/19 for `label="Google"`.
- `crawl.log:63` records attempt 1 after the click as `actualPackageName="com.google.android.gms"` with `observedFingerprint="android.widget.FrameLayout::More options||android.view.View|false|false|false|false"` and `result=changed`.
- `crawl.log:66-68` pauses for an external package boundary, then records `decision=continue` and stores that sparse fingerprint as `expectedDestinationFingerprint`.
- `crawl.log:84` restores GMS and observes a richer fingerprint: `All services`, `Give feedback`, `More options`, `Sign in`, and `Sign in`.
- `crawl.log:86-87` fails the edge because `expectedDestinationFingerprint` and `actualDestinationFingerprint` differ, even though `expectedPackageName` and `actualPackageName` are both `com.google.android.gms`.

`crawl-index.json` and `crawl-graph.json` reflect the outcome:

- `crawl-index.json:559-566` has the `Google` edge with `"status": "failed"` and the message `Could not restore external package screen 'Google' after continue decision.`
- `crawl-graph.json:228-230` has the same failed edge and no captured GMS node.

The same pattern appears later for `Digital Wellbeing & parental controls`:

- `crawl.log:778` first observes `com.google.android.apps.wellbeing` as `android.widget.FrameLayout::`.
- `crawl.log:783` stores that empty fingerprint as the expected external destination.
- `crawl.log:807-809` restores the Wellbeing package and sees the loaded screen, including `App timers`, `Bedtime mode`, `TODAY`, and `View app activity details`, then fails exact fingerprint validation.
- `crawl-index.json:767-774` records that edge as failed with the same restore message shape.

This broader evidence supports treating the issue as general destination-settling behavior rather than a GMS-specific rule.

## Current Code Flow

### Post-click destination capture returns on first changed snapshot

`DeepCrawlCoordinator.openChildFromScreen()` restores the parent screen, rewinds to top, moves to the element's scroll step, captures `beforeClickFingerprint`, performs the click, and then calls `captureChildDestinationAfterClick()`.

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:806-918`
- Stable link: https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L806-L918

`captureChildDestinationAfterClick()` runs up to `maxPostClickCaptureAttempts = 4`. For normal discovery (`expectedChildPackageName == null`), it returns immediately when either:

- the package changes, or
- the logical viewport fingerprint differs from both the before-click fingerprint and the top fingerprint.

For restore to an expected external package (`expectedChildPackageName != null`), it returns immediately on the first non-null snapshot from that expected package.

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:920-1039`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1757-1760`
- Stable links:
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L920-L1039
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L1757-L1760

The important current behavior is that the retry loop waits for "something changed", not for "the destination is settled enough to trust".

### External-package Continue stores and later exact-compares the first destination fingerprint

After `openChildFromScreen()` returns, `expandScreen()` computes `afterClickFingerprint` from `activeChildRoot`. If the child package is not allowed yet, the external-package pause stores this exact fingerprint in log context as `expectedDestinationFingerprint`.

When the user chooses Continue, the coordinator adds the package to `allowedPackageNames`, reopens the same edge through `openChildFromScreen(expectedChildPackageName = childPackageName)`, recomputes a restored fingerprint, and requires:

```kotlin
restoredPackageName == childPackageName &&
    restoredFingerprint == afterClickFingerprint
```

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:302-431`
- Stable link: https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L302-L431

This validation is useful for ensuring Continue returns to the same destination instead of scanning AppToHTML or another foreground package. The brittle part is that the expected fingerprint may be an early transitional snapshot.

### The real host already applies a fixed capture delay

`AppToHtmlAccessibilityService.captureCurrentRootSnapshot()` always delays `scrollSettleDelayMillis = 350L` before reading `rootInActiveWindow`, and it returns `null` when a non-null expected package does not match the live root package.

Relevant source:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:57-60`
- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:435-445`
- Stable links:
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt#L57-L60
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt#L435-L445

This delay was not enough for GMS or Wellbeing. The logs show those packages were foregrounded, but their first snapshot was semantically sparse.

### Scroll scanning has a separate viewport settling helper

`ScrollScanCoordinator.scan()`, `rewindToTop()`, `rewindToEntryScreen()`, and `moveToStep()` call `settleViewport()` after scroll/back transitions. `settleViewport()` samples up to two additional captures and keeps the root with the highest distinct visible pressable count.

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:11-71`
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:176-202`
- Stable links:
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt#L11-L71
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt#L176-L202

That helper is not used by `captureChildDestinationAfterClick()`, and its current richness metric is only distinct visible pressable count. The GMS sparse fingerprint had one visible pressable element (`More options`), while the fuller screen had five; Wellbeing went from zero to several. So the existing metric would have helped these two examples, but destination settling likely needs a richer model than pressable count alone.

### Fingerprints are based on pressable elements, not all visible text

`ScrollScanCoordinator.logicalViewportFingerprint()` builds a fingerprint from collected pressable elements. It includes root class name and each distinct pressable element's label, resource id, class name, list-item flag, checkable state, checked state, and editable state, but omits bounds.

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:249-333`
- Stable link: https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt#L249-L333

Pressable-only fingerprints are good for navigation checks, but they also mean a loading screen with one toolbar action can become a "changed" destination even while non-clickable content is still loading.

### Downstream capture and graph output depend on the post-click root

Once external boundary handling passes, `expandScreen()` scans the child from `activeChildRoot`, computes a screen identity with `ScreenNaming.buildScreenIdentity()`, writes artifacts through `CaptureFileStore.saveScreen()`, adds the edge to the manifest, rewrites parent HTML links, and refreshes graph artifacts.

Relevant source:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:482-603`
- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt:107-138`
- Stable links:
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L482-L603
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt#L107-L138

That means destination-settling behavior should happen before:

- no-navigation classification,
- external-package pause and restore fingerprint storage,
- child screen scan,
- screen identity and dedup,
- manifest and graph edge writes.

## Test Coverage Today

`DeepCrawlCoordinatorTest` already covers several external-package flows:

- skip external package,
- continue and capture cross-package child,
- wait for delayed external package before no-navigation skip,
- replay through recorded package context,
- restore external foreground before real scan after Continue,
- retry expected-package capture after Continue restore,
- fail restore when the expected package never appears.

Relevant source:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:407-872`
- Stable link: https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt#L407-L872

The current fake host supports delayed transitions where the package change itself is delayed. It does not currently model a destination whose package is already correct but whose semantic content becomes richer across subsequent captures of the same screen. That is the missing regression shape for the Google Services and Wellbeing failures.

Most coordinator tests use `scanScreenOverride`, which snapshots from the supplied root instead of exercising real scroll-scan callbacks. One existing test opts into `useRealScan = true` for the external foreground issue. A destination-settling regression can probably stay in coordinator tests if the fake host gains per-capture screen variants, while lower-level richness/stability scoring can be tested as deterministic domain logic.

Relevant source:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1408-1436`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1501-1536`
- Stable links:
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt#L1408-L1436
  - https://github.com/OCiobanu8/AppToHTML/blob/c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f/app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt#L1501-L1536

## Architecture Insights

The cleanest current boundary for this behavior is the crawler domain layer, not the Android service:

- The service can only provide delayed snapshots of the active root.
- `DeepCrawlCoordinator` owns the semantics of "this click produced a usable child destination".
- `ScrollScanCoordinator` already owns viewport fingerprinting and simple settling utilities.
- `AccessibilityNodeSnapshot` and `AccessibilityTreeSnapshotter` are deterministic models usable in unit tests without Android framework mocks.

A fix should introduce a new `DestinationSettler` helper under `crawler/` rather
than expanding `ScrollScanCoordinator` with clicked-destination behavior. The
helper should operate on `AccessibilityNodeSnapshot` and injected capture
callbacks, so tests can remain synthetic.

## Implementation-Relevant Current Constraints

The code currently has these constraints and tradeoffs:

- Captures already cost at least 350 ms each in the real service because `captureCurrentRootSnapshot()` delays before reading the window.
- `maxPostClickCaptureAttempts` is currently count-based, not elapsed-time-based.
- No monotonic clock or coroutine delay is injected into `DeepCrawlCoordinator` for post-click settling beyond the host capture delay.
- Existing post-click logging is attempt/result based and can be extended to report richness/stability without changing artifact formats.
- `ScreenSnapshot` stores merged screen output after scan; no persistent model currently stores intermediate post-click samples.
- The project explicitly does not need backward compatibility for internal behavior or data formats, but graph artifacts should remain portable sibling basenames.

## High-Value Fix Surface

This investigation is read-only, but these are the code areas most directly involved in an implementation:

1. Replace the immediate return in `captureChildDestinationAfterClick()` with a bounded destination-settling loop that keeps sampling after first changed/correct-package observation until the chosen root is stable enough or the budget expires.
2. Use the settled root to compute `afterClickFingerprint` in `expandScreen()` before no-navigation checks and external-package pause storage.
3. Use the same settled-root path for post-Continue external restore, so `external_boundary_restore_result` compares settled destination fingerprints instead of comparing a sparse pre-pause sample to a richer post-pause sample.
4. Add deterministic per-sample metrics over `AccessibilityNodeSnapshot`, likely including visible node count, visible text/content-description count, pressable count, non-empty pressable-label count, fingerprint length, package presence, and loading/progress indicators. These metrics should guide "best settled sample" selection and diagnostics rather than act as a hard sparse-screen rejection threshold.
5. Extend tests with same-package asynchronous enrichment and external-package asynchronous enrichment, including a Google-like case where the first changed fingerprint is only `More options` and a later snapshot contains the real destination controls.

## Implementation Decisions

- Destination settling should live in a new focused crawler-domain class named `DestinationSettler`, instead of expanding `ScrollScanCoordinator` with post-navigation behavior.
- Each clicked destination should get up to 10 seconds to settle. This implies `DeepCrawlCoordinator` should use an elapsed-time budget for destination settling rather than only the current capture-count budget.
- Avoid a hard "too sparse" concept. Sparse destinations can be legitimate, so richness should be used for sample ranking, stability checks, and diagnostics, not as an automatic blocker.
- External-boundary restore validation should accept partial matches. Package match remains required, but settled fingerprints should not need exact equality when both samples are compatible by identity/richness signals.
- Logs should include per-sample richness and stability metrics so the next live-device diagnosis can explain why a particular destination sample was selected.

## Metadata

- Repository: `AppToHTML`
- Remote: `https://github.com/OCiobanu8/AppToHTML.git`
- Branch: `3-NEW-use-screen-id-based-artifact-filenames-for-deep-crawl-captures`
- Commit: `c7ed69e211ee1e6cf2408bb634dedb3aaa495e5f`
- Primary analysis: `documentation/crawl-20260428-google-services-continue-analysis.md`
- Crawl folder: `E:\Logs\com.android.settings\crawl_20260428_151359`
