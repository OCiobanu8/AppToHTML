# ENG-0002: Detect Delayed External Package Navigation Before Skipping Edges

Date: 2026-04-26
Validation: `thoughts/shared/validations/2026-04-26-ENG-0002-delayed-external-package-navigation-validation.md`
Previous plan: `thoughts/shared/plans/2026-04-26-ENG-0002-external-package-continue-foreground.md`
Original manual failure evidence: `E:\Logs\com.android.settings\crawl_20260426_153148`
Phase 6 manual validation evidence: `E:\Logs\com.android.settings\crawl_20260426_161827`

## Overview

Fix the remaining external-package continue failure exposed by the manual crawl. The coordinator currently clicks the `Google` row, captures one post-click root, sees the same Settings fingerprint, and marks the edge `SKIPPED_NO_NAVIGATION` before the device finishes foregrounding `com.google.android.gms`. The later restore probe proves the click did leave Settings, but the external-package boundary branch was never reached.

The fix is to make `openChildFromScreen(...)` observe the post-click destination over a short bounded capture window before returning an unchanged root to the existing no-navigation check. This keeps normal same-screen taps fast, but gives slower cross-package launches enough time to surface so external-boundary pause, continue, restore, scan, route, and artifact semantics can run.

## Current State

- `DeepCrawlCoordinator.expandScreen(...)` opens an edge through `openChildFromScreen(...)`, computes the child fingerprint, and immediately marks the edge as `SKIPPED_NO_NAVIGATION` when the child fingerprint equals the before-click or top fingerprint (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:303`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:327`).
- The external-package branch is below that no-navigation check, so a delayed package change is invisible to `childPackageName !in allowedPackageNames` (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:342`).
- `openChildFromScreen(...)` performs one `host.captureCurrentRootSnapshot(expectedChildPackageName)` after a successful click and returns that root to the caller (`app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:885`, `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:893`).
- Production root capture waits for one fixed `scrollSettleDelayMillis` of 350ms before reading `rootInActiveWindow`, and rejects the capture only when an explicit `expectedPackageName` is provided and does not match (`app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:58`, `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt:435`).
- Existing regression coverage proves that `CONTINUE` restores AppToHTML-foregrounded external screens before real scanning, but the fake host transitions to the external package immediately after click. It does not reproduce "first capture still parent, later capture external" (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:581`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:628`).
- The fake host's `click(...)` transitions synchronously, and `captureCurrentRootSnapshot(...)` immediately reflects the current screen (`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1306`, `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1324`).

## Desired End State

- A successful click is not classified as no-navigation until the coordinator has observed a bounded number of post-click captures.
- If the first post-click capture still matches the parent/top fingerprint but a later capture changes package or fingerprint, the later root becomes the child root.
- Delayed external-package transitions enter the existing `crawl_pause reason=external_package_boundary` flow instead of being skipped.
- The post-continue restore path also benefits from bounded capture retries when `expectedChildPackageName` is set and the first package-filtered capture is null.
- Same-screen controls and truly inert clicks still become `SKIPPED_NO_NAVIGATION` after the retry budget expires.
- The retry loop is scoped to the edge-opening helper and does not change route replay, manifest schema, pause decision state, blacklist behavior, or saved-crawl semantics.

## Phase 1: Add Bounded Post-Click Destination Observation

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- [x] Add a small helper inside `DeepCrawlCoordinator`, for example `captureChildDestinationAfterClick(...)`, called only by `openChildFromScreen(...)` after `host.click(element)` succeeds.
- [x] Pass the helper enough context to evaluate whether a capture is still the parent:
   - `expectedChildPackageName`
   - `beforeClickFingerprint`
   - `expectedTopFingerprint`
   - `usesEntryFingerprint`
   - parent screen ID/name and element for logging
- [x] For `expectedChildPackageName == null`, capture repeatedly up to a bounded count, for example `maxPostClickCaptureAttempts = 4`.
   - Return immediately when the captured root's logical fingerprint differs from both `beforeClickFingerprint` and `expectedTopFingerprint`.
   - Return immediately when the captured root package differs from the parent package, even if the fingerprint comparison is ambiguous.
   - If every successful capture still looks like the parent, return the last captured root so the existing `SKIPPED_NO_NAVIGATION` path remains the single source of skip behavior.
- [x] For `expectedChildPackageName != null`, retry when `host.captureCurrentRootSnapshot(expectedChildPackageName)` returns null, then fail the edge only after all attempts are exhausted.
- [x] Do not add a separate raw delay in the coordinator unless device testing proves repeated captures are insufficient. In production each capture already includes the service-level 350ms settle delay; repeated captures therefore create a bounded wait without introducing a second timing system.

Design notes:

- Keep the helper private and local to the coordinator. This is a crawl edge timing concern, not a general accessibility service concern.
- The first capture should still count as attempt 1 so fast navigation keeps the current behavior.
- Use the existing logical fingerprint functions rather than geometry-sensitive fingerprints; this matches current no-navigation and external restore validation semantics.

## Phase 2: Move No-Navigation Classification After Observation

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- [x] Update `openChildFromScreen(...)` so it returns the observed child root from Phase 1 instead of the first immediate post-click root.
- [x] Keep the existing no-navigation check in `expandScreen(...)`, but make sure its inputs now come from the settled/observed destination root.
- [x] Preserve current edge failure behavior:
   - failed click still fails immediately
   - no capture after all expected-package attempts still fails as "target app was lost"
   - unchanged root after all attempts still becomes `SKIPPED_NO_NAVIGATION`
- [x] Confirm the external boundary check still runs after a delayed capture returns an external package.

Design notes:

- This phase should be a minimal behavioral relocation: no-navigation stays in `expandScreen(...)`, while the post-click helper improves the quality of the child root that reaches that check.
- Avoid accepting a null expected-package capture as no-navigation during post-continue restore. A null root with `expectedChildPackageName` means the expected external package has not appeared yet or was lost, not that the edge did nothing.

## Phase 3: Add Focused Logging For Delayed Navigation

Files:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- [x] Add concise logs around the observation loop:
   - `child_destination_observe_attempt`
   - `child_destination_observe_result`
- [x] Include fields that explain manual crawl timing:
   - parent screen ID/name
   - trigger label
   - attempt number and max attempts
   - expected package name
   - actual package name
   - before-click fingerprint
   - top fingerprint
   - observed fingerprint
   - result, such as `changed`, `unchanged_retry`, `expected_package_missing_retry`, `unchanged_final`, or `captured`
- [x] Keep existing `edge_click_result`, `edge_skipped_no_navigation`, `external_package_accepted`, and `external_boundary_restore_*` logs intact.

Design notes:

- These logs should make the previous manual sequence diagnosable without guessing: if the first capture is Settings and the second is Google Services, both facts should be visible.
- Do not log full XML, screen text, or large serialized structures.

## Phase 4: Reproduce Delayed Foreground In Unit Tests

Files:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Add a regression test for the exact manual failure shape, for example:
   - `externalPackageDecision_waits_for_delayed_external_package_before_no_navigation_skip`
- [x] Build a fake host where:
   - clicking `Open Google` succeeds but does not immediately change the foreground screen
   - the first post-click capture still returns the parent Settings root
   - a later post-click capture foregrounds and returns the external Google root
   - `awaitPauseDecision(...)` returns `PauseDecision.CONTINUE`
- [x] Assert:
   - the edge is not recorded as `SKIPPED_NO_NAVIGATION`
   - exactly one `EXTERNAL_PACKAGE_BOUNDARY` pause is observed
   - the external child screen is captured
   - the manifest contains the external package
   - logs include observation attempts and `external_package_accepted`
- [x] Prefer a small test-only capability on `FakeHost`, such as delayed transitions keyed by label and capture count, rather than duplicating the host.
- [x] Keep the existing immediate-transition tests unchanged so they continue covering the faster path.

Design notes:

- The current `FakeHost` stores screen state privately, but exposes `foregroundScreen(...)` and `currentPackageName()`. A subclass can hard-code a delayed `Open Google` transition, or the host can accept an optional `delayedTransitions` map if that stays small and readable.
- The new test should not use `scanScreenOverride` if the assertion needs real package-filtered capture behavior during external scanning. Follow the existing `useRealScan = true` pattern when needed.

## Phase 5: Add Expected-Package Restore Retry Coverage

Files:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`

Changes:

- [x] Extend or add a test where post-continue restore calls `captureCurrentRootSnapshot(externalPackageName)` before the external package is visible.
- [x] Simulate one null expected-package capture followed by a successful external capture.
- [x] Assert:
   - the edge still completes successfully
   - `captureExpectedPackages` records repeated external-package captures
   - the child artifact and manifest use the external package
- [x] Add a negative test if the implementation shape makes it cheap:
   - expected external package never appears
   - edge becomes failed, not skipped_no_navigation
   - no AppToHTML package child artifact is saved

Design notes:

- This protects the post-decision replay path from the same timing issue as the initial click path.
- Keep failure assertions tight enough to avoid coupling to unrelated manifest ordering.

## Phase 6: Run Manual Validation Again

Status: complete on 2026-04-26.

Manual crawl evidence: `E:\Logs\com.android.settings\crawl_20260426_161827`

Files:

- No code files unless the manual crawl exposes a new defect.
- Update or add a validation document under `thoughts/shared/validations/` after the crawl.

Steps:

1. [x] Run automated tests first.
2. [x] Install/run AppToHTML on the same device or emulator used for `crawl_20260426_153148`.
3. [x] Start a Settings crawl and click through the same `Google` edge.
4. [x] Choose `Continue outside package` when prompted.
5. [x] Copy the latest crawl to `E:\Logs` and inspect logs and artifacts.

Manual acceptance checks:

- [x] Logs show one or more `child_destination_observe_attempt` lines for the Google edge.
- [x] Logs show `crawl_pause reason=external_package_boundary`.
- [x] Logs show `external_package_accepted`.
- [x] Logs show `external_boundary_restore_attempt`.
- [x] Logs show `external_boundary_restore_result`.
- [x] `crawl-index.json` includes at least one child screen whose `packageName` is `com.google.android.gms` or the actual external package reached by the device.
- [x] Saved XML for the external child contains the external package.
- [x] Saved XML for that child does not contain `com.example.apptohtml` as the captured foreground package.
- [x] The Google edge is not listed as `skipped_no_navigation`.

## Automated Verification

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\octak\AndroidStudioProjects\AppToHTML\.gradle-user'
.\gradlew.bat test
```

Expected automated outcomes:

- Phase 1 automated verification:
  - [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.
- Phase 2 automated verification:
  - [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.
- Phase 3 automated verification:
  - [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.
- Phase 5 automated verification:
  - [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26.
- Phase 6 automated verification:
  - [x] `.\gradlew.bat test` completed successfully with the plan's `JAVA_HOME` and `GRADLE_USER_HOME` settings on 2026-04-26 before marking manual validation complete.
- Existing external-package skip, continue, restore, route replay, and allowed-package tests pass.
- The new delayed initial foreground test fails before the implementation and passes after Phase 1 and Phase 2.
- The new expected-package restore retry test passes after Phase 1.
- Existing pause checkpoint tests pass, proving the bounded observation loop did not interfere with elapsed-time or failed-edge pause behavior.

## Risks and Mitigations

- Risk: bounded retries slow down truly inert clicks.
  - Mitigation: cap attempts tightly, rely on the service's existing 350ms capture delay, and only retry when the captured root still looks unchanged or the expected package is missing.
- Risk: repeated captures could observe a transient intermediate package.
  - Mitigation: when `expectedChildPackageName` is known, require that package. During initial discovery, stop on the first package/fingerprint change and let the existing external-boundary decision flow handle the package.
- Risk: dynamic screens may change fingerprint without real navigation.
  - Mitigation: keep the existing edge classification semantics; this plan only gives delayed navigation more than one capture chance before that classification.
- Risk: test host delay support could make the fake host harder to reason about.
  - Mitigation: add the smallest delayed-transition hook needed for this specific regression and keep existing immediate behavior as the default.

## Out of Scope

- Persisting accepted external packages across crawl sessions.
- Changing pause UI copy or button behavior.
- Changing manifest schema.
- Reworking route replay beyond using the improved child-open helper.
- Tuning `AppToHtmlAccessibilityService.scrollSettleDelayMillis` globally.
