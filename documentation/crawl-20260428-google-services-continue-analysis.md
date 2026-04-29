# Google Services Continue-Pause Fingerprint Analysis

## Context

Latest inspected crawl:

- Session: `crawl_20260428_151359`
- Target package: `com.android.settings`
- Local copy: `E:\Logs\com.android.settings\crawl_20260428_151359`
- Issue observed: after choosing **Continue** for the Google Services external-package pause, the crawler did not capture the Google Services screen.

## What Happened

The root Settings screen included a `Google` entry as the first eligible traversal edge:

```text
edge_visit_start ... parentScreenName="Search Settings" edgeIndex=1/19 element=label="Google"
```

The initial click did cross into Google Services:

```text
child_destination_observe_result ... triggerLabel="Google" actualPackageName="com.google.android.gms" observedFingerprint="android.widget.FrameLayout::More options||android.view.View|false|false|false|false" result=changed
```

That package transition correctly triggered the external-package pause:

```text
crawl_pause reason=external_package_boundary ... currentPackageName="com.android.settings" nextPackageName="com.google.android.gms" triggerLabel="Google"
```

The user decision was recorded correctly:

```text
crawl_pause_resolved reason=external_package_boundary decision=continue currentScreenId=screen_00000 nextPackageName="com.google.android.gms"
external_package_accepted ... allowedPackageNames="[com.android.settings, com.google.android.gms]" expectedDestinationFingerprint="android.widget.FrameLayout::More options||android.view.View|false|false|false|false"
```

After Continue, the crawler restored the parent screen, tapped `Google` again, and again reached `com.google.android.gms`. This time the destination looked more complete:

```text
child_destination_observe_result ... expectedPackageName="com.google.android.gms" actualPackageName="com.google.android.gms" observedFingerprint="android.widget.FrameLayout::All services||android.view.View|false|false|false|false||Give feedback|ClickableText|android.widget.TextView|false|false|false|false||More options||android.view.View|false|false|false|false||Sign in|ButtonAction|android.view.View|false|false|false|false||Sign in|Card: Sign in|android.view.View|false|false|false|false" result=captured
```

However, the external-boundary restore validation compared this fuller fingerprint against the earlier weak fingerprint and failed the edge:

```text
external_boundary_restore_result ... expectedPackageName="com.google.android.gms" actualPackageName="com.google.android.gms" expectedDestinationFingerprint="android.widget.FrameLayout::More options||android.view.View|false|false|false|false" actualDestinationFingerprint="android.widget.FrameLayout::All services||android.view.View|false|false|false|false||Give feedback|ClickableText|android.widget.TextView|false|false|false|false||More options||android.view.View|false|false|false|false||Sign in|ButtonAction|android.view.View|false|false|false|false||Sign in|Card: Sign in|android.view.View|false|false|false|false" destinationFingerprintMatched=false
edge_failure_recoverable ... message="Could not restore external package screen 'Google' after continue decision."
```

Result: no Google/GMS screen artifact was written for this edge.

## Diagnosis

The Continue decision worked. The package boundary was accepted, and replay after Continue reached `com.google.android.gms`.

The failure came from the initial destination fingerprint being captured too early. The first post-click observation saw only:

```text
More options
```

The later post-Continue observation saw the real loaded screen:

```text
All services
Give feedback
More options
Sign in
Sign in
```

The crawler then rejected the valid destination because `DeepCrawlCoordinator` requires the restored external destination fingerprint to exactly equal the pre-pause `afterClickFingerprint`.

Relevant code path:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- External package acceptance stores `afterClickFingerprint` as `expectedDestinationFingerprint`.
- Restore validation fails when `restoredPackageName != childPackageName || restoredFingerprint != afterClickFingerprint`.

## Proposed Fix

Treat this as a general destination-settling problem, not an external-package-only
problem. Every clicked destination should be given a chance to stabilize before
the crawler records the destination fingerprint, decides whether the screen has
changed, pauses for package boundaries, or writes child artifacts.

Android accessibility does not provide a universal "screen fully loaded" signal,
so the crawler should infer "settled enough to trust" from a bounded set of
signals.

Suggested stabilization strategy:

1. After clicking an edge and observing a changed destination, wait a minimum
   dwell time before accepting the destination fingerprint.
2. Sample snapshots over a bounded window, for example up to 3-5 seconds, so the
   crawl cannot hang forever on animated or continuously updating screens.
3. Track screen richness across samples, such as node count, visible text count,
   clickable count, non-empty label count, and fingerprint length.
4. Treat sparse or obviously transitional snapshots as unsettled. Examples:
   empty root, blank package, only a toolbar/menu, progress indicators, loading
   text, or a fingerprint with too little semantic content for a destination.
5. Continue sampling while richness is still increasing meaningfully.
6. Settle only after the minimum dwell time has elapsed, no loading indicators
   are visible, and both fingerprint content and richness have been quiet for a
   short stability window.
7. Use the settled fingerprint as the destination fingerprint for all downstream
   decisions, including normal in-package deduplication, route replay, external
   package pause validation, manifest edges, and artifact naming.

The important point is that "last two samples match" is not sufficient by
itself. Two incomplete early samples can match before asynchronous content has
loaded. The settling rule needs both a minimum wait and a content-richness check.

This would have helped this crawl because the first Google Services sample was
too sparse:

```text
More options
```

The later post-Continue sample looked complete and usable:

```text
All services
Give feedback
More options
Sign in
Sign in
```

A general settling detector should prefer the latter kind of fingerprint for
every destination screen, not only for external-package screens.

## Expected Benefit

Stabilizing destination fingerprints should reduce false failures and poor
deduplication for screens that load content asynchronously after the first
accessibility snapshot. It keeps the existing safety model intact while making
screen identity less brittle across both in-package and external-package
navigation.

## Verification Ideas

- Add a synthetic unit test where the first destination observation has a sparse fingerprint and a later observation has a fuller settled fingerprint.
- Add an in-package destination test to confirm settling happens before normal child capture and deduplication, not only before external-package pause handling.
- Confirm the crawler stores the settled fingerprint before pausing for an external package.
- Re-run the Settings crawl and verify the `Google` edge captures a child screen for `com.google.android.gms`.
- Confirm `crawl-index.json` and `crawl-graph.json` link the `Google` edge to the captured Google Services screen instead of marking it failed.
