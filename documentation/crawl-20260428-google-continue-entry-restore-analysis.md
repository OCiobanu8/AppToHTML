# Google Continue Fails Before External Restore Click

Date: 2026-04-28

Live evidence:

- Crawl folder: `E:\Logs\com.android.settings\crawl_20260428_194407`
- Video: `tmp/video_sampler.html` pointing at `E:\Logs\com.android.settings\crawl_20260428_194407\Screen_recording_20260428_194405.webm`
- Plan being validated: `thoughts/shared/plans/2026-04-28-ENG-0008-destination-settling.md`

## Summary

The destination-settling implementation fixed the original sparse Google
Services destination selection problem for the first pre-pause click. The live
crawl observes `com.google.android.gms` for a full dwell window and selects the
richer Google Services root containing `All services`, `Give feedback`, `More
options`, and `Sign in`.

The crawl still fails the Google edge after the user chooses Continue, but the
failure has moved earlier in the flow. It does not fail external destination
compatibility. It fails before the crawler clicks Google again, because
`openChildFromScreen(...)` restores Settings after the pause, receives a
transient empty Settings root, and rejects it as a top-state fingerprint
mismatch.

This matches the observed video behavior: after the pause, the crawler does not
try to go back into Google Services.

## Relevant Log Sequence

Before the external-package pause, Google destination settling succeeds:

```text
child_destination_settle_result ... triggerLabel="Google" ...
selectedPackageName="com.google.android.gms" ...
selectedFingerprint="android.widget.FrameLayout::All services ... Give feedback ... More options ... Sign in ..."
sampleCount=26 elapsedSettleMillis=10204
stopReason=fixed_dwell_exhausted selectionReason=best_richness
```

The pause is resolved with Continue:

```text
crawl_pause_resolved reason=external_package_boundary decision=continue
currentScreenId=screen_00000 nextPackageName="com.google.android.gms"
```

The coordinator then starts the expected external restore path:

```text
external_package_accepted ... triggerLabel="Google" ...
nextPackageName="com.google.android.gms" ...
expectedDestinationFingerprint="android.widget.FrameLayout::All services ... Sign in ..."

external_boundary_restore_attempt ... triggerLabel="Google" ...
nextPackageName="com.google.android.gms"

child_open_restore_attempt ... triggerLabel="Google"
expectedPackageName="com.google.android.gms"
```

After relaunching Settings, the top validation sees an empty logical root:

```text
entry_restore_probe currentPackage=com.example.apptohtml
entry_restore_attempt strategy=relaunch
entry_restore_relaunch_attempt attempt=2/4 stopReason=no_back_affordance
entry_restore_result strategy=relaunch success=true

screen_top_validation screenId=screen_00000 screenName="Search Settings"
expectedFingerprint="android.widget.FrameLayout::Apps ... Google ... Wallpaper & style"
actualFingerprint="android.widget.FrameLayout::"
```

The edge fails before the click is replayed:

```text
child_open_restore_result ... triggerLabel="Google"
expectedPackageName="com.google.android.gms"
destinationFingerprintMatched=false result=top_fingerprint_mismatch

edge_failure_recoverable ... message="The screen 'Search Settings' no longer
matches its captured top state before opening 'Google'."
```

## Current Code Path

The failing path is:

1. `DeepCrawlCoordinator.expandScreen(...)` pauses on the external package.
2. The user chooses Continue.
3. The coordinator calls `openChildFromScreen(...)` again with
   `expectedChildPackageName = "com.google.android.gms"`.
4. `openChildFromScreen(...)` calls `restoreLiveScreenForEdge(...)`.
5. For the root screen, `restoreLiveScreenForEdge(...)` calls
   `restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint)`.
6. `restoreToEntryScreenOrRelaunch(...)` relaunches Settings and accepts the
   result from `ScrollScanCoordinator.rewindToEntryScreen(...)` when the stop
   reason is `NO_BACK_AFFORDANCE`.
7. `openChildFromScreen(...)` computes the live top fingerprint and requires it
   to exactly equal the captured top fingerprint.
8. The live top fingerprint is temporarily empty, so the method fails before
   calling `host.click(element)`.

Key source locations:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
  - `external_boundary_restore_attempt`
  - `openChildFromScreen(...)`
  - `screen_top_validation`
  - `restoreLiveScreenForEdge(...)`
  - `restoreToEntryScreenOrRelaunch(...)`
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`
  - `rewindToEntryScreen(...)`

## Why This Is Not The Original Settling Bug

The original issue was that the crawler stored the first sparse
`com.google.android.gms` root as the expected destination, then failed later
when the restored root was richer.

In this crawl:

- The pre-pause selected Google root is already rich.
- `external_package_accepted` stores the rich fingerprint.
- No `external_boundary_restore_result` is emitted for Google.
- The failure happens before destination compatibility is evaluated.

So the remaining bug is not destination compatibility. It is entry/root restore
settling after returning from the AppToHTML pause UI.

## Contrast With Digital Wellbeing

The same crawl later succeeds for Digital Wellbeing:

```text
external_boundary_restore_result ... triggerLabel="Digital Wellbeing & parental controls"
expectedPackageName="com.google.android.apps.wellbeing"
actualPackageName="com.google.android.apps.wellbeing"
destinationFingerprintMatched=true destinationCompatible=true
compatibilityReason=exact_fingerprint_match
```

`crawl-index.json` records `screen_00014` with package
`com.google.android.apps.wellbeing`, and the graph links the edge as captured.

The difference is that the Settings root restore reached the expected entry
fingerprint before replaying the Digital Wellbeing click. For Google, the first
accepted restored root was still empty.

## Suspected Root Cause

`restoreToEntryScreenOrRelaunch(...)` accepts `NO_BACK_AFFORDANCE` as a
successful root restore even when an expected entry logical fingerprint is
available and has not actually been observed yet.

`ScrollScanCoordinator.rewindToEntryScreen(...)` can return
`NO_BACK_AFFORDANCE` for a transient root that has no visible in-app back
affordance. In the Google failure, that transient root has logical fingerprint:

```text
android.widget.FrameLayout::
```

That root is technically in the target package and has no back affordance, but
it is not the captured Settings entry state.

## Recommended Fix Direction

Add a bounded entry/root restore settling step for cases where
`expectedEntryLogicalFingerprint` is provided.

Candidate behavior:

- After relaunch or back-normalization, do not accept `NO_BACK_AFFORDANCE` alone
  as success if the expected entry fingerprint is known.
- Re-sample the selected app root for a short bounded window until
  `logicalEntryViewportFingerprint(root) == expectedEntryLogicalFingerprint`.
- Keep the current no-back-affordance fallback only when no expected entry
  fingerprint is available.
- Log each entry restore sample with package, class, logical fingerprint,
  whether the expected fingerprint matched, and whether back affordance was
  visible.
- If the expected entry fingerprint never appears, fail with a message that
  distinguishes "entry restore did not settle" from "top state changed before
  click."

This should be separate from `DestinationSettler`: the problem is not choosing
a clicked child destination, but validating that the parent screen is truly back
at the captured entry state before replaying the external edge.

## Verification Criteria For A Fix

- A Settings crawl that pauses on Google and receives Continue should replay the
  Google click after the pause.
- `crawl.log` should show entry restore samples after Continue, including any
  transient empty root and the eventual matched Settings entry root.
- Google should reach `external_boundary_restore_result`.
- If package and destination compatibility pass, the Google Services screen
  should be captured as a child artifact instead of `edge_002` failing.
- `crawl-index.json`, `crawl-graph.json`, and `crawl-graph.html` should include
  the GMS node and mark the Google edge captured/linked.
- Existing Digital Wellbeing behavior should remain passing.

## Implemented Fix

The fix landed under
`thoughts/shared/plans/2026-04-28-ENG-0009-entry-restore-replay-hardening.md`.
Key changes:

- `EntryScreenResetOutcome` replaces the overloaded `NO_BACK_AFFORDANCE`
  success contract. `MATCHED_EXPECTED_LOGICAL` and `verifiedForReplay=true` are
  the only success modes once an expected fingerprint is supplied.
- `restoreToEntryScreenOrRelaunch(...)` treats a known expected entry as a
  bounded settle operation. Current-package restore continues to relaunch when
  the fingerprint does not match; relaunch restore samples until the expected
  entry root appears or the bounded window expires.
- `entry_restore_*` log entries now record the observed fingerprint, expected
  fingerprint presence, matched status, and verified status for every sample.
- `EntryScreenBackAffordanceDetector` resolves descendant labels for top or
  toolbar-aligned clickable parents so Compose `Navigate up` controls are no
  longer missed.
