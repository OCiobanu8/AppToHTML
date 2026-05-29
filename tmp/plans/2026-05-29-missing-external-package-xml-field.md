# Missing External Package XML Field Implementation Plan

Date: 2026-05-29
Repository: AppToHTML
Source research: `thoughts/shared/research/2026-05-29-ENG-0010-issue-2-external-package-xml-field.md`

## Overview

Fix the external-package XML source-of-truth gap by carrying the destination package on durable crawl edge state, then projecting that field into per-screen XML and hydrating it back when loading a saved crawl.

The XML projection layer already supports `external-package`; the missing piece is that `CrawlEdgeRecord` and the tracker lifecycle do not store the value. The implementation should keep manifest JSON unchanged for this issue and make per-screen XML the durable structured audit surface for external package edge metadata.

## Current State

- `EdgeXmlView.externalPackage` exists in `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`.
- `AccessibilityXmlSerializer` already writes `external-package="..."` for edge XML views that contain a value.
- `ScreenXmlReader` already parses `external-package` into `EdgeXmlView.externalPackage`.
- `CrawlEdgeRecord` does not contain `externalPackage`, so `CrawlRunTracker` cannot preserve it.
- `CrawlRunTracker.addEdge(...)` and `updateEdgeStatus(...)` store status, child links, message, and approval only.
- `DeepCrawlCoordinator` computes `childPackageName` after a click and detects package boundaries, but only embeds the package name in the skip message or child screen record.
- `DeepCrawlCoordinator.buildScreenCrawlState(...)` maps `CrawlEdgeRecord` to `EdgeXmlView` without passing `externalPackage`.
- `SavedCrawlLoader` reads edge XML views but drops `externalPackage` when rebuilding `CrawlEdgeRecord`.
- Existing tests cover serializer support and external-package approval/skip behavior, but do not assert that coordinator-generated XML includes `external-package`.

## Desired End State

- Every edge whose observed destination package differs from its parent screen package stores that destination package as `CrawlEdgeRecord.externalPackage`.
- Per-screen XML for originating edges includes `external-package="..."` for skipped, captured, linked-existing, and already-allowed external package edges.
- `SavedCrawlLoader` preserves the attribute when rebuilding tracker edge state.
- Explicit approval package hydration prefers `edgeView.externalPackage` and falls back to the child screen package when needed.
- Skip decisions do not mark approval, but they still preserve `external-package` for auditability.
- Manifest JSON remains unchanged in this fix.

## Phased Implementation Steps

### Phase 1: Add Durable Edge State

- [x] Update `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt`.
   - Add `val externalPackage: String? = null` to `CrawlEdgeRecord`.
   - Keep the default value last or near `approval` to minimize call-site churn.

- [x] Update `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt`.
   - Add `externalPackage: String? = null` to `addEdge(...)`.
   - Store it in the new `CrawlEdgeRecord` field.
   - Add `externalPackage: String? = null` to `updateEdgeStatus(...)`.
   - Preserve the existing value when the parameter is null:
     `externalPackage = externalPackage ?: existing.externalPackage`.
   - Leave `addPendingEdge(...)` unchanged unless a direct caller needs package metadata later.

- [x] Add focused tracker coverage in `app/src/test/java/com/example/apptohtml/crawler/CrawlRunTrackerTest.kt`.
   - Assert `updateEdgeStatus(...)` can stamp `externalPackage`.
   - Assert later status updates preserve the stamped value when they omit the parameter.

### Phase 2: Stamp External Package During Crawl

- [x] Update `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`.
   - After the no-navigation guard has ruled out same-package no-op clicks, detect cross-package edges with:
     `val externalPackage = childPackageName.takeIf { it != currentPackageName }`.
   - If `externalPackage != null`, update the current edge before any pause save:
     `tracker.updateEdgeStatus(edgeId = currentEdgeId, status = CrawlEdgeStatus.IN_PROGRESS, externalPackage = externalPackage)`.
   - This makes the pre-decision manifest/XML rewrite path structurally aware of the external destination.

- [x] Preserve and reinforce that field in decision branches.
   - On `PauseDecision.CONTINUE`, include `externalPackage = childPackageName` alongside `approval = CrawlEdgeApproval.EXPLICIT`.
   - On `PauseDecision.SKIP_EDGE`, include `externalPackage = childPackageName` alongside `SKIPPED_EXTERNAL_PACKAGE`.
   - Let later `CAPTURED` or `LINKED_EXISTING` updates preserve the existing value by omitting the parameter.

- [x] Cover already-allowed external packages.
   - The cross-package stamping should happen before the `childPackageName !in allowedPackageNames` branch, so packages accepted earlier in the run still produce `external-package` on future edges without triggering a second pause.

### Phase 3: Project Tracker State Into XML

- [x] Update `DeepCrawlCoordinator.buildScreenCrawlState(...)`.
   - Pass `externalPackage = edge.externalPackage` when constructing `EdgeXmlView`.

- [x] Do not modify `AccessibilityXmlSerializer`.
   - It already emits the attribute when `EdgeXmlView.externalPackage` is non-null.

- [x] Do not modify `ScreenXmlReader` for parsing.
   - It already reads `external-package`.
   - Add a reader test assertion only if existing round-trip coverage does not exercise the field.

### Phase 4: Hydrate Saved Crawl State

- [x] Update `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt`.
   - Pass `externalPackage = edgeView.externalPackage` into `CrawlEdgeRecord`.

- [x] Update explicit approval package collection.
   - For `edgeView.approval == CrawlEdgeApproval.EXPLICIT`, use:
     - first: `edgeView.externalPackage` when non-blank
     - fallback: child screen package from `childScreenId`
   - This preserves allowed packages even if the child screen is missing or pruned, while keeping the existing child-package fallback for older XML.

- [x] Keep skipped external-package edges out of `allowedPackages`.
   - The approval gate should remain the only way an external package becomes allowed during saved-crawl hydration.

### Phase 5: Tests And Documentation

- [x] Update `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt`.
   - In `externalPackageDecision_continue_marks_originating_edge_with_explicit_approval`, assert the root XML contains both `approval="explicit"` and `external-package="$externalPackageName"`.
   - In `externalPackageDecision_skip_does_not_mark_edge_with_approval`, assert the root XML contains `external-package="$externalPackageName"` and still omits `approval=`.
   - Add or extend an already-allowed package scenario to assert a later external edge into a previously approved package also writes `external-package` without requiring a second pause.

- [x] Update `app/src/test/java/com/example/apptohtml/crawler/SavedCrawlLoaderTest.kt`.
   - Add a test that XML with `EdgeXmlView(externalPackage = externalPackage)` hydrates `CrawlEdgeRecord.externalPackage`.
   - Add a test that explicit approval plus `externalPackage` seeds `allowedPackages` even when `childScreenId` is absent or the child XML is not present.

- [x] Update `app/src/test/java/com/example/apptohtml/crawler/ScreenXmlReaderTest.kt` if needed.
   - Extend the round-trip edge test to include `externalPackage` and assert it is parsed.

- [x] Update documentation.
   - In `documentation/data-and-state.md`, mention that per-screen XML edge metadata includes structured `external-package` for cross-package edges.
   - In `documentation/crawler-module.md`, note that external-package boundary decisions stamp originating edge XML with package metadata for auditability.

## Automated Verification

Run targeted tests first:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.CrawlRunTrackerTest
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.SavedCrawlLoaderTest
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.ScreenXmlReaderTest
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest
```

Then run the broader unit suite if targeted tests pass:

```powershell
.\gradlew.bat test
```

## Manual Verification

1. Run a crawl against an app screen with a control that opens a new package.
2. Choose `Skip edge`.
3. Pull or inspect the session folder and confirm the originating screen XML contains:
   - `status="skipped_external_package"`
   - `external-package="the.destination.package"`
   - no `approval="..."`
4. Run another crawl or repeat with `Continue outside package`.
5. Confirm the originating screen XML contains:
   - `approval="explicit"`
   - `external-package="the.destination.package"`
   - captured or linked child screen metadata as appropriate.
6. Resume from the saved crawl and confirm the previously approved external package does not prompt again, while skipped packages remain unapproved.

## Risks And Notes

- `updateEdgeStatus(...)` using null-as-preserve means there is no explicit clear operation for `externalPackage`. That is acceptable for this issue because once an edge is known to cross packages, later status transitions should keep the metadata.
- Existing manifest JSON will not include the new field unless `CrawlManifestStore` is changed. This is intentional and matches the research decision to scope the fix to per-screen XML.
- Dirty workspace state is broad. Implementers should avoid unrelated refactors and verify any modified files are part of this specific edge metadata lifecycle.
