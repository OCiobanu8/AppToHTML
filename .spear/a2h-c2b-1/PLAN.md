# PLAN — a2h-c2b-1 · Skip external packages entirely

Ordered so the RED pin exists **before** the behavior is deleted (the pin must be seen failing
against pre-fix code), and so compilation is never broken across more than one step.

---

## Step 0 — Baseline evidence

- `./gradlew test` and `./gradlew assembleDebug` on the untouched tree; archive both to
  `.spear/a2h-c2b-1/evidence/00-baseline-{test,assemble}.txt`.
- Record `git diff --stat` origin so the "net production LOC decreases" criterion has a reference.

**Why first:** a red→green pin is only meaningful against a suite that was green to begin with.

---

## Step 1 — Write the three new pins (RED against pre-fix code)

All three go in `DeepCrawlCoordinatorTest.kt`, reusing the existing `FakeHost` (it already models a
foreign-package destination screen and already lets a test count `relaunchTargetApp` calls — see the
existing test at `DeepCrawlCoordinatorTest.kt:994`).

The `FakeHost` base override of `awaitPauseDecision` is what makes these RED-able: pre-fix, the
coordinator *calls* it at the boundary. Each new pin's host **fails the test if it is called at all**
for an external boundary.

1. **`externalPackageClick_isSkippedAutomatically_withoutAnyPauseDecision`**
   - Screen A has one element opening screen G in `com.google.android.googlequicksearchbox`.
   - Host records every `awaitPauseDecision` invocation.
   - Asserts: outcome `Completed`; the recorded pause-decision list is **empty**; the edge's status
     is `skipped_external_package` with `"externalPackage": "<external pkg>"` in the manifest;
     `capturedScreenCount == 1`; the manifest does **not** contain `"screenName": "Google"`.
   - *Pre-fix result:* the coordinator calls `awaitPauseDecision` → **RED**.

2. **`externalPackageSkip_recoversToTargetApp_onNextEdge`** (the bead's "confirm, don't assume")
   - Screen A has **two** elements: the first opens external G, the second opens in-package screen B.
   - Host counts `relaunchTargetApp` calls and records the package of each
     `captureCurrentRootSnapshot`.
   - Asserts: after the external skip, a `relaunchTargetApp` for the target package occurs, and the
     **second** edge completes normally — screen B is captured, `capturedScreenCount == 2`, second
     edge status `captured`.
   - *Pre-fix result:* the host is never asked for a decision, so the run behaves differently → **RED**.

3. **`noCodePath_capturesAScreenOutsideTheTargetPackage`**
   - Same shape as (1), but asserted over the **final manifest**: no `CrawlScreenRecord` may carry a
     `packageName` other than the target's, and no edge to a foreign package may reach `CAPTURED` or
     `LINKED_EXISTING`.
   - This is the "no code path can follow a click into a non-target package" criterion. Stated over
     the manifest, it holds regardless of how the host answers anything.
   - *Pre-fix result:* **not RED on its own** — pre-fix, with no approval granted, nothing is
     captured either. Documented as a **guard**, not a regression pin. Its RED is demonstrated
     instead by the mutation in Step 5.

**Archive:** run these three, capture the failures to
`.spear/a2h-c2b-1/evidence/01-red-new-pins.txt`.

---

## Step 2 — Delete the boundary approval in `DeepCrawlCoordinator.kt`

Replace the whole `if (childPackageName !in allowedPackageNames) { … }` / `else if` block
(~L557–L712) with an unconditional skip — exactly the body of the old `PauseDecision.SKIP_EDGE`
branch, taken without asking:

- set edge status `SKIPPED_EXTERNAL_PACKAGE`, message `Skipped external package '<pkg>'.`,
  `externalPackage = childPackageName`
- log `edge_skipped_external_package`
- `CaptureFileStore.rewriteScreenHtml(...)`, `rewriteScreenXmlFor(...)`, `saveManifest(...)`
- `return@forEachIndexed`

Also in this file:
- delete `allowedPackageNames` (L35, L83–86) and `formatAllowedPackageNames()` (~L2140)
- delete the `loaded.allowedPackages` seeding and the `allowedPackagesLoaded=` log field
- delete the `SKIP_EDGE ->` throw in `handlePauseCheckpointIfNeeded` (~L981) — with `SKIP_EDGE`
  gone from the enum, the `when` is exhaustive on `CONTINUE`/`STOP`
- drop the `externalPackageContext` argument from the remaining `awaitPauseDecision` call
- delete the `destinationSettler.compatibility(...)` call and the `external_boundary_restore_*`,
  `external_package_accepted`, and `external_package_already_allowed` logging

**Note the pre-existing `IN_PROGRESS` write at L549–L555** that stamps `externalPackage` before the
old branch ran. It is redundant once the skip is unconditional; fold it into the single status write
so the edge is not written twice.

---

## Step 3 — Delete the now-unreferenced declarations

The compiler must be satisfied at the end of the step, not within it.

- `PauseCheckpointConfig.kt`: `PauseReason.EXTERNAL_PACKAGE_BOUNDARY`, `PauseDecision.SKIP_EDGE`,
  `ExternalPackageDecisionContext`
- `DestinationSettler.kt`: `compatibility()`, `DestinationCompatibilityResult`,
  `DestinationCompatibilityReason`, plus `meaningfulPressableIdentities`,
  `meaningfulPressableIdentity`, `normalizeCompatibilityToken`, `expectedLooksSparseForCompatibility`
  — **verify each has no other caller before deleting** (grep, don't assume)
- `CrawlerSession.kt`: `skipExternalEdge()`, the `externalPackageContext` parameter on
  `pauseForDecision`
- `DeepCrawlCoordinator.Host`: the `externalPackageContext` parameter on `awaitPauseDecision`
- `AppToHtmlAccessibilityService.kt:393`: matching signature change
- `CrawlerModels.kt`: `CrawlEdgeApproval`; the `approval` field on the edge records; the
  external-package fields on `withPausedForDecision` and the `pauseCurrentPackageName` /
  `pauseNextPackageName` / `pauseTriggerLabel` UI-state fields
- `CrawlRunTracker.kt`: `setEdgeApproval`, the `approval` parameters
- `AccessibilityXmlSerializer.kt` (L397, L450) + `ScreenXmlReader.kt` (`parseEdgeApproval`): the
  `approval` XML attribute — emitted and parsed
- `SavedCrawlLoader.kt`: `allowedPackages` field and its derivation (L101, L140–147, L170)
- `SavedCrawlRepository.kt`: `AllowedPackage`, `SavedCrawlSnapshot.allowedPackages` (L19, L36, L75–95)
- `MainActivity.kt`: `AllowedPackageRow`, the "Allowed packages" section (L526–L531, L602),
  `isExternalPackagePause` and its four branches (L646–L720), and the
  `EXTERNAL_PACKAGE_BOUNDARY` arm of `pauseReasonText`

---

## Step 4 — Retire and adapt the existing tests

Classified by reading each one, not by name.

**Adapt (behavior survives, assertions change):**
- `externalPackageDecision_waits_for_delayed_external_package_before_no_navigation_skip` (L1506) —
  the *settling* behavior it pins still matters, and is the reason a slow external transition is
  classified `SKIPPED_EXTERNAL_PACKAGE` rather than mis-classified `SKIPPED_NO_NAVIGATION`.
  Rewrite the assertions to expect the automatic skip; **keep** the `result=unchanged_retry` and
  `result=changed` log assertions. Rename to drop `Decision`.

**Delete (each pins behavior being removed):**
`_continues_and_captures_cross_package_child…`, `_continue_marks_originating_edge_with_explicit_approval`,
`_skip_does_not_mark_edge_with_approval`, `revokedApprovalOnExternalPackageEdge_is_excluded_on_load`,
`_waitsForExpectedEntryAfterContinueBeforeReclicking`, `_failsWhenExpectedEntryNeverSettlesAfterContinue`,
`_acceptsCompatibleSparseExpectedAndRichRestoredDestination`,
`_failsCompatibleRestoreForUnrelatedSamePackageDestination`,
`_settlesGoogleLikeExternalSparseToRichDestination`, `_settlesDigitalWellbeingLikeEmptyToRichDestination`,
`_replays_through_recorded_package_context`,
`routeReplay_settlesIntermediateExternalStepBeforeContinuingToDestination`,
`_restores_external_foreground_before_real_scan_after_continue`,
`_retries_expected_package_capture_after_continue_restore`,
`_fails_restore_when_expected_package_never_appears`,
`_allows_previously_accepted_packages_and_pauses_for_new_package`.

*Rationale for deleting rather than keeping:* each asserts that a cross-package screen **is
captured**. That outcome is now the defect this cycle exists to prevent — criterion 3 asserts its
negation. Retiring them is required, not convenience.
`_skips_edge_when_user_selects_skip` (L670) is superseded by new pin (1) and is deleted with it.

**Also update:** `DestinationSettlerTest.kt` (drop the six `compatibility()` cases),
`CrawlerSessionTest.kt:135,162` (the SKIP_EDGE path), `CrawlRunTrackerTest.kt:78,81,132`,
`AccessibilityXmlSerializerTest.kt:147`, `SavedCrawlLoaderTest.kt:114,142,166`,
`SavedCrawlRepositoryTest.kt:101,148`.

**Preserve untouched:** the four `pauseCheckpoint_*` tests (L2366–L2595). They are the "other two
pause reasons still work" criterion — if any needs editing beyond a signature change, that is a
signal the change over-reached.

---

## Step 5 — RED→GREEN confirmation + mutation

- Re-run the three new pins → **GREEN**; archive to `evidence/02-green-new-pins.txt`.
- **Mutation for pin (3)** (the guard that could not be RED naturally): temporarily restore a code
  path that captures the foreign screen, show pin (3) turns **RED**, revert. Archive to
  `evidence/03-mutation-guard.txt`. Per `factory/FUNCTION.md`, a pin never seen red proves nothing.

---

## Step 6 — Full verification at the final artifact state

- `./gradlew test` → `evidence/04-final-test.txt`
- `./gradlew assembleDebug` → `evidence/05-final-assemble.txt`
- Deletion grep (must return nothing outside `.spear/`, `thoughts/`, `documentation/`):
  `EXTERNAL_PACKAGE_BOUNDARY`, `SKIP_EDGE`, `ExternalPackageDecisionContext`,
  `DestinationCompatibilityReason`, `CrawlEdgeApproval`, `.compatibility(`
- `git diff --stat -- app/src/main` → net LOC must be negative

**Re-run order matters:** these run *after* the last edit. Evidence gathered before a later edit is
void.

---

## Step 7 — Documentation

`documentation/crawler-module.md` and `documentation/modules.md` describe the approval pause. Update
the affected passages only. `factory/PRODUCT.md`'s "asks before crossing app boundaries" is
**governance** — raise it at Gate 3 as a flywheel item; do not edit it silently.

---

## Rollback

Single feature branch `feat/skip-external-packages`; revert is one `git revert` of the merge. No data
migration and no persisted state to unwind.

## Approval

`[x] User confirmed`
