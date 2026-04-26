# ENG-0002: Restore External Destination Before Continuing Crawl

Date: 2026-04-26
Issue: https://github.com/OCiobanu8/AppToHTML/issues/2
Reference: `documentation/github-open-issues-research-2026-04-25.md`

## Overview

Fix the external-package boundary flow so choosing `Continue outside package` resumes from the external destination screen, not from AppToHTML after the decision UI is foregrounded.

The core change is in `DeepCrawlCoordinator.expandScreen`: after an external-boundary continue decision, the coordinator must replay back to the clicked external destination and use that fresh live root for `scanCurrentScreen(...)`. The fix should also remember accepted external packages during the current crawl so the pause mechanism only engages when traversal enters a package outside the target package plus the packages already approved by the user.

## Current State

- `DeepCrawlCoordinator.expandScreen` clicks an eligible element, captures `childInitialRoot` with `expectedPackageName = null`, detects `childPackageName != currentPackageName`, pauses for a decision, and on `PauseDecision.CONTINUE` only logs before falling through to `scanCurrentScreen(...)` with the stale pre-pause root (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:369`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:402`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:425`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:431`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:480`).
- `CrawlerSession.pauseForDecision(...)` intentionally calls `returnToApp()` before awaiting the user decision, which means AppToHTML can be foregrounded when the coordinator resumes (`app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt:144`, `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt:182`).
- The real scanner does not rely only on the supplied root. `scanCurrentScreen(...)` delegates scrolling and settled viewport captures to host callbacks, so the active foreground package matters (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:949`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:964`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:970`).
- In production, those callbacks operate against `rootInActiveWindow`, and `captureCurrentRootSnapshot(expectedPackageName)` returns null if the active package does not match (`app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:159`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:163`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:173`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:435`).
- Existing coordinator tests cover skip, continue, and route replay metadata, but the shared test coordinator uses `scanScreenOverride = { _, initialRoot, _, _ -> host.snapshotForRoot(initialRoot) }`, which hides failures from live scan callbacks (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:465`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:521`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1041`).
- Route replay already persists and uses `expectedPackageName` on route steps, which is the right foundation for restoring cross-package screens later (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:486`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:819`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:833`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:919`).
- Current boundary detection is relative to the current screen package, not to the crawl's accepted package set. That means a later navigation from an accepted external package back into the target package, or from the target package back into the same accepted external package, can still look like a package boundary even though it stays inside the user-approved crawl bounds (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:375`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:402`).

## Desired End State

- When the crawler pauses at an external-package boundary and the user chooses continue, the coordinator foregrounds the same external destination before scanning.
- The fresh post-decision root is package-validated and screen-validated against the pre-pause destination fingerprint before it is used for `scanCurrentScreen(...)`.
- Scroll, settle, XML, merged XML, and HTML artifacts for the continued external screen come from the external package, not AppToHTML.
- Accepted package scope is remembered for the current crawl. The allowed package set starts with the selected target package, and each `Continue outside package` decision adds that destination package.
- Once a package is accepted, traversal among screens in the selected target package and any accepted package does not trigger the external-package pause again. For example, after accepting Google Services from Settings, subsequent Settings-to-Google, Google-to-Google, and Google-to-Settings screens continue without pausing.
- The external-package pause appears again only when a click lands in a package outside the allowed package set.
- Existing skip and stop behavior remains unchanged.
- Existing package-aware route metadata remains compatible with future saved-crawl resume work from issue #4.

## Phase 1: Add an Accepted Package Scope Model

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- [x] Add a coordinator-level mutable set, for example `allowedPackageNames`, initialized at crawl start with `selectedApp.packageName`.
- [x] Treat `childPackageName !in allowedPackageNames` as the condition that engages the external-package pause. Do not pause just because `childPackageName != currentPackageName`.
- [x] On `PauseDecision.CONTINUE`, add `childPackageName` to `allowedPackageNames`.
- [x] On `PauseDecision.SKIP_EDGE`, do not add the child package to `allowedPackageNames`.
- [x] Keep using the pre-pause logical destination fingerprint to validate the first restored screen after a continue decision, but do not use screen fingerprint as the acceptance key.

Design notes:

- Acceptance is intentionally package-scoped for the current crawl session. This matches the product expectation: once the user approves Google Services, the crawler can traverse within Settings and Google Services without repeated prompts.
- A new package outside `allowedPackageNames` still pauses. Accepting Google Services must not implicitly accept Chrome, Play Services UI, a browser custom tab, or any other package reached later.
- Keep this state in-memory for issue #2. Persisting accepted external decisions belongs with the broader saved-crawl/resume model from issue #4.

## Phase 2: Refactor Parent-Route Replay to Reopen a Child Destination

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

1. [x] Extract the repeated "restore parent, rewind to top, move to element step, click, capture child root" sequence from `expandScreen(...)` into a helper.
2. [x] Add a helper shaped for reopening a child destination:

   ```kotlin
   private suspend fun openChildFromScreen(
       tracker: CrawlRunTracker,
       screenRecord: CrawlScreenRecord,
       snapshot: ScreenSnapshot,
       element: PressableElement,
       entryScreenLogicalFingerprint: String,
       expectedChildPackageName: String?,
       expectedTopFingerprint: String,
       usesEntryFingerprint: Boolean,
   ): OpenedChildDestination
   ```

3. [x] The helper should:
   - call `restoreLiveScreenForEdge(...)`
   - rewind the restored parent root to the captured top
   - validate the live top fingerprint against `expectedTopFingerprint`
   - move to `element.firstSeenStep`
   - click the target element
   - capture the child with `host.captureCurrentRootSnapshot(expectedChildPackageName)`
4. [x] Use `expectedChildPackageName = null` for the first click, preserving current detection behavior.
5. [x] Phase 3: Use `expectedChildPackageName = childPackageName` when reopening after a continue decision, ensuring AppToHTML is rejected as the active root.

Phase 2 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

Design notes:

- Reusing one helper reduces drift between the original edge visit and post-decision restoration.
- Keep failure behavior aligned with existing recoverable edge failures by continuing to use `failCurrentEdge(...)`.
- Log an explicit restoration attempt/result, including `parentScreenId`, `triggerLabel`, `expectedPackageName`, and whether the destination fingerprint matched.

## Phase 3: Restore the External Screen After Continue

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- [x] Change `childInitialRoot` from an immutable value to a variable or introduce `activeChildRoot`.
- [x] When `childPackageName !in allowedPackageNames`:
   - pause as today
   - on `SKIP_EDGE`, keep current skip behavior
   - on `STOP`, keep current partial-abort behavior
   - on `CONTINUE`, add `childPackageName` to `allowedPackageNames`
- [x] After `CONTINUE`, reopen the destination with the helper from Phase 2:
   - replay to the parent screen
   - click the same element
   - capture with `expectedChildPackageName = childPackageName`
   - recompute the logical destination fingerprint
- [x] Validate the replayed root:
   - `restoredRoot.packageName == childPackageName`
   - restored fingerprint equals the pre-pause `afterClickFingerprint`
- [x] Replace `childInitialRoot`/`activeChildRoot` with the restored root before calling `scanCurrentScreen(...)`.
- [x] If `childPackageName in allowedPackageNames`, skip the UI pause and continue to use the currently captured root. Do not foreground AppToHTML or replay unless a pause actually occurred.

Design notes:

- The actual bug only appears after the decision UI foregrounds AppToHTML. Replaying on every previously accepted boundary is unnecessary and could add fragile navigation churn.
- Fingerprint mismatch should be a recoverable edge failure with a message like: `Could not restore external package screen 'X' after continue decision.`
- Continue should not call `pauseTracker.rollForwardAfterContinue(...)` for `EXTERNAL_PACKAGE_BOUNDARY`; that method is intentionally a no-op for this reason.
- The current package may be either the original target package or an accepted external package. The pause decision depends on whether the destination package leaves the allowed package set, not on whether the destination differs from the current package.

Phase 3 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

## Phase 4: Preserve Route and Artifact Semantics

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt` only if additional serialized state is intentionally added

Changes:

- [x] Keep building `childRoute` from `childSnapshot.packageName` after the restored scan, as current code does (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:486`).
- [x] Do not add accepted package state to `CrawlManifest` for this issue.
- [x] Confirm linked-existing and new-screen capture flows still use the restored root for naming/logging inputs:
   - `screenIdentityFor(...)`
   - `logNamingInputs(...)`
   - `CaptureFileStore.saveScreen(...)`
   - `logPersistedScreenCapture(...)`
- [x] Keep parent HTML rewrite behavior unchanged after capture/link/skip.

Phase 4 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

Design notes:

- The route step should still persist the external `expectedPackageName`; this is what enables later replay through external screens.
- Persisting accepted package scope in the manifest may be useful later, but it is not required to solve the foreground correctness bug.

## Phase 5: Strengthen Unit Coverage

Files:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

1. [x] Add a test that reproduces the production foreground bug:
   - [x] create a host where `awaitPauseDecision(...)` simulates `CrawlerSession.pauseForDecision()` by switching the active screen/package to an AppToHTML screen before returning `PauseDecision.CONTINUE`
   - [x] run the coordinator without the `scanScreenOverride`, or add a test-only coordinator factory option that allows the real `ScrollScanCoordinator.scan(...)` path to execute
   - [x] make `captureCurrentRootSnapshot(externalPackageName)` fail unless the coordinator has replayed back into the external screen
   - [x] assert the external child is captured and its XML/manifest package is the external package
2. [x] Add a scroll/capture assertion:
   - [x] use a fake external screen with scrollable content or override fake host scroll/capture behavior enough to prove `captureCurrentRootSnapshot(externalPackageName)` is called after continue
   - [x] assert no captured artifact contains the AppToHTML package for the child screen
3. [x] Add allowed-package assertions:
   - [x] after accepting Google Services from Settings, navigate from Google Services to another Google Services screen and assert no second pause
   - [x] navigate from Google Services back to Settings and assert no pause
   - [x] navigate from Settings back into Google Services and assert no pause
   - [x] navigate from either allowed package into a third package and assert a new external-package pause appears
4. [x] Preserve existing tests:
   - [x] `externalPackageDecision_skips_edge_when_user_selects_skip`
   - [x] `externalPackageDecision_continues_and_captures_cross_package_child_when_user_selects_continue`
   - [x] `externalPackageDecision_replays_through_recorded_package_context`

Phase 5 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

Design notes:

- The current fake host has no concept of AppToHTML foregrounding during the pause. Add a focused test host capability such as `foregroundAppToHtml()` or `screenIdAfterPauseDecision` rather than changing every existing test.
- The existing `coordinator(...)` helper can keep its default override, but the new regression test should opt into real scanning to exercise live callbacks.

## Phase 6: Logging and Diagnostics

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`
- `documentation/external-package-continue-foreground-bug-2026-04-25.md` optionally, if implementation notes should be appended after the fix

Changes:

1. [x] Add logs for:
   - [x] `external_boundary_restore_attempt`
   - [x] `external_boundary_restore_result`
   - [x] `external_package_accepted`
   - [x] `external_package_already_allowed`
2. [x] Include enough fields to debug device runs:
   - [x] parent screen ID/name
   - [x] trigger label
   - [x] current package
   - [x] next package
   - [x] current allowed package set
   - [x] expected and actual destination fingerprints
3. [x] Keep logs concise and consistent with the existing `crawl_pause_resolved`, `edge_click_result`, and `replay_route_step_result` styles.

Phase 6 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

## Automated Verification

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\octak\AndroidStudioProjects\AppToHTML\.gradle-user'
.\gradlew.bat test
```

Phase 1 automated verification:

- [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.

Expected automated outcomes:

- New regression test fails before the implementation because scan callbacks observe AppToHTML after continue.
- New regression test passes after the coordinator restores the external destination.
- New allowed-package tests prove the pause is not shown while traversal stays within the selected target package plus accepted packages, and is shown again for a new package.
- Existing external-boundary skip, continue, and route replay tests pass.
- Existing pause checkpoint tests pass, proving elapsed-time and failed-edge pause behavior was not disturbed.

## Manual Verification

1. [x] Install/run AppToHTML on a device or emulator with accessibility service enabled.
2. [x] Start a deep crawl on a target app that can navigate into a scrollable external package screen.
3. [x] When prompted at the external-package boundary, choose `Continue outside package`.
4. [x] Confirm the external app is foregrounded before scanning resumes.
5. [x] Confirm generated child artifacts contain the external package and not `com.example.apptohtml`.
6. [x] Confirm scrolling/merged accessibility output includes the external screen's scrollable content.
7. [x] Navigate later within the accepted package and back to the original target package, and confirm AppToHTML does not prompt again.
8. [x] Navigate from either allowed package into a third package and confirm AppToHTML prompts again.

## Risks and Mitigations

- Risk: replaying the edge after the pause can trigger side effects twice.
  - Mitigation: this only happens after the user explicitly approved leaving the package; validate by fingerprint and fail recoverably if the destination differs.
- Risk: dynamic external screens may have unstable fingerprints.
  - Mitigation: compare logical viewport fingerprints, not geometry-sensitive fingerprints, and log mismatches with enough context for follow-up tuning.
- Risk: accepting an entire package allows traversal across more screens in that package than the exact first approved screen.
  - Mitigation: this is the intended product behavior for issue #2 feedback; keep acceptance scoped to the current crawl session and require a fresh pause for every new package outside the allowed set.
- Risk: changing the test coordinator to use real scanning could disturb many tests.
  - Mitigation: keep the existing `scanScreenOverride` default and opt into real scanning only in the new regression test.

## Out of Scope

- Persisting accepted external-boundary decisions across app restarts.
- Saved-crawl resume, manifest readback, or screen picker work from issue #4.
- Screen ID and artifact filename migration from issue #3.
- Changing the AppToHTML pause UI copy or button layout.
