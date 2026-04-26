# ENG-0002 External Package Continue Foreground Validation

Date: 2026-04-26
Plan: `thoughts/shared/plans/2026-04-26-ENG-0002-external-package-continue-foreground.md`
Manual crawl: `E:\Logs\com.android.settings\crawl_20260426_153148`

## Summary

The implementation matches the planned code structure and the automated unit suite passes, but the latest manual crawl does not validate the desired external-package continue behavior.

The crawl captured 15 screens, all in `com.android.settings`. It captured no `com.google*` XML artifacts and no `com.example.apptohtml` XML artifacts. That means the latest run did not show AppToHTML contamination in saved artifacts, but it also did not capture the expected external-package screen.

The key failure mode is that the external boundary branch was never reached. The `Google` edge was clicked successfully, but the immediate post-click capture still reported the Settings root. The coordinator classified the edge as `skipped_no_navigation` before the device later surfaced `com.google.android.gms` during the next restore.

## Automated Verification

Command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\octak\AndroidStudioProjects\AppToHTML\.gradle-user'
.\gradlew.bat test
```

Result:

```text
BUILD SUCCESSFUL in 1s
24 actionable tasks: 4 executed, 20 up-to-date
```

## Implementation Status By Phase

### Phase 1: Accepted Package Scope

Status: implemented.

Evidence:

- `DeepCrawlCoordinator` has coordinator-level `allowedPackageNames`.
- Crawl startup clears the set and adds `selectedApp.packageName`.
- External-package pause detection uses `childPackageName !in allowedPackageNames`.
- `PauseDecision.CONTINUE` adds the child package.
- `PauseDecision.SKIP_EDGE` does not add the child package.

### Phase 2: Parent-Route Replay Helper

Status: implemented.

Evidence:

- `openChildFromScreen(...)` exists and centralizes restoring the parent, rewinding to top, moving to the element step, clicking, and capturing the child root.
- Initial edge opens pass `expectedChildPackageName = null`.
- Post-continue reopen passes the external child package name.

### Phase 3: Restore External Screen After Continue

Status: implemented, but not exercised by the manual crawl.

Evidence:

- `activeChildRoot` is mutable.
- On external-package `CONTINUE`, the coordinator logs acceptance, reopens the child with `expectedChildPackageName = childPackageName`, compares package and fingerprint, then uses the restored root for scanning.
- The latest crawl contains no `external_package_accepted`, `external_boundary_restore_attempt`, or `external_boundary_restore_result` log lines, so this phase did not run in the manual test.

### Phase 4: Route And Artifact Semantics

Status: implemented structurally.

Evidence:

- `childRoute` still uses `childSnapshot.packageName` for route-step `expectedPackageName`.
- Accepted package state is not persisted to the manifest.
- Naming, identity, save, and log calls use `activeChildRoot` or `childSnapshot.mergedRoot ?: activeChildRoot`.

Manual evidence:

- `crawl-index.json` shows all screen `packageName` values as `com.android.settings`.
- XML artifacts contain `com.android.settings`.
- XML artifacts do not contain `com.google` or `com.example.apptohtml`.

### Phase 5: Unit Coverage

Status: implemented, but missing the manual failure shape.

Evidence:

- `externalPackageDecision_restores_external_foreground_before_real_scan_after_continue` simulates AppToHTML foregrounding during pause and uses the real scan path.
- Allowed-package tests assert a second package is accepted once and a third package prompts again.
- Existing external-boundary tests remain present.

Gap:

- The new regression test uses an immediate fake transition to the external package. It does not cover the device behavior where the first capture after click still returns the parent package and a later capture reveals the external package.

### Phase 6: Logging And Diagnostics

Status: implemented.

Evidence:

- Logs exist for `external_boundary_restore_attempt`, `external_boundary_restore_result`, `external_package_accepted`, and `external_package_already_allowed`.

Manual evidence:

- The latest crawl did not emit those logs because the boundary branch was not entered.

## Manual Crawl Evidence

Manual crawl path:

`E:\Logs\com.android.settings\crawl_20260426_153148`

Screen/package summary:

```text
com.android.settings: 15 screens
```

Edge status summary:

```text
captured: 14
failed: 19
skipped_blacklist: 4
skipped_no_navigation: 6
```

Artifact package checks:

```text
com.android.settings: 30 XML files
com.google: 0 XML files
com.example.apptohtml: 0 XML files
```

Important log sequence for the `Google` edge:

```text
45: edge_visit_start ... element=label="Google" ...
58: live_action_start type=ACTION_CLICK ... label="Google" ...
59: live_action_attempt ... ACTION_CLICK success=true
60: child_open_restore_result ... triggerLabel="Google" expectedPackageName="" actualPackageName="com.android.settings" destinationFingerprintMatched=false result=captured
61: edge_click_result ... afterClickFingerprint=<same Settings top fingerprint>
62: edge_skipped_no_navigation ... element=label="Google" ...
65: entry_restore_probe currentPackage=com.google.android.gms ...
```

Interpretation:

The click appears to have launched or foregrounded Google Services after the coordinator had already captured the still-current Settings root and marked the edge as no-navigation. Because of that ordering, `childPackageName` remained `com.android.settings`, so the accepted-package logic never had a chance to pause or restore the external destination.

## Deviations From Expected End State

- Expected: choosing `Continue outside package` should restore and scan the external destination.
- Observed: the latest crawl never presented or logged the external-package pause for the `Google` edge.
- Expected: generated child artifacts for the external screen should contain the external package.
- Observed: no external-package child artifacts were generated.
- Expected: logs should show external acceptance and restore attempts.
- Observed: no `external_package_accepted` or `external_boundary_restore_*` lines were present.

## Likely Root Cause

The coordinator performs the no-navigation check before external-boundary handling:

1. Click target element.
2. Capture current root once with no expected package.
3. Compare logical fingerprint against the parent/top fingerprint.
4. If unchanged, mark `SKIPPED_NO_NAVIGATION`.
5. Only after that does it check whether `childPackageName !in allowedPackageNames`.

On the device crawl, the external package appeared after the first post-click capture. This suggests the 350ms settle delay in `AppToHtmlAccessibilityService.captureCurrentRootSnapshot(...)` was not enough for the `Google` row transition. The next restore saw `currentPackage=com.google.android.gms`, proving the click did leave Settings, just too late for the current detection logic.

## Potential Issues Or Regressions

- External-package navigations with delayed foreground changes can be misclassified as `skipped_no_navigation`.
- The new unit coverage may be too optimistic because fake host transitions are immediate.
- Real device timing can leave the crawler in an external package after a skipped edge, forcing the next root restore to relaunch the target app.
- Because the external edge is skipped before boundary detection, the manual test cannot validate the post-continue restore behavior even if that code is otherwise correct.

## Recommended Next Steps

1. Add a post-click settle or short polling step in `openChildFromScreen(...)` before classifying an unchanged fingerprint as no-navigation.
2. The poll should allow a successful click to observe a package or fingerprint change over a bounded window, especially when the first capture still matches the parent.
3. Add a regression test where the first capture after click returns the parent Settings root and a later capture returns the external package.
4. Re-run `.\gradlew.bat test`.
5. Re-run the manual Settings crawl and confirm the log includes:
   - `crawl_pause reason=external_package_boundary`
   - `external_package_accepted`
   - `external_boundary_restore_attempt`
   - `external_boundary_restore_result`
   - an external package screen in `crawl-index.json`

