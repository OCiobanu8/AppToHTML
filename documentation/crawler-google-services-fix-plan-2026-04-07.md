# Google Services Replay Validation Fix Plan 2026-04-07

## Summary

Fix the `Google services` retry loop by introducing a bounds-insensitive logical fingerprint for replay and validation paths, while keeping the existing geometry-sensitive fingerprint for scroll progression.

This targets the failure documented in `documentation/crawler-google-services-root-cause-2026-04-07.md`: replay returns to the correct screen, but top-state validation fails because raw `root.bounds` and element `bounds` drift across Compose relayouts.

## Recommended Approach

Implement a split fingerprint model instead of removing bounds everywhere:

1. keep the current geometry-sensitive viewport fingerprint for scan progression
2. add a logical viewport fingerprint for replay, validation, and no-navigation checks
3. update service-level recovery checks to use the logical fingerprint as well

This is the safest path because it fixes the unstable replay guard without changing scroll-step detection semantics more than necessary.

## Part 1: Split Viewport Fingerprinting

Primary file:

- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt`

Changes:

- keep the current geometry-sensitive fingerprint behavior for viewport-change detection during scanning and rewind-to-top termination
- add a logical fingerprint variant that excludes:
  - `root.bounds`
  - per-element `bounds`
- keep stable semantic inputs in the logical fingerprint, including:
  - label
  - resource id
  - class name
  - list-item status
  - checkable state
- add a logical entry-screen fingerprint variant parallel to the existing entry fingerprint behavior
- make the API names explicit so callers clearly choose either geometry-sensitive or logical comparison

Expected outcome:

- small Compose relayout shifts no longer change replay-validation fingerprints
- scroll progression still detects real viewport movement using the stricter geometry-sensitive signal

## Part 2: Use Logical Fingerprints In Replay And Validation

Primary file:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- switch captured top-state versus live top-state validation to the logical fingerprint
- switch before-click versus after-click no-navigation detection to the logical fingerprint
- switch route replay step validation to the logical fingerprint
- switch entry-screen restore equality checks in replay flows to the logical entry fingerprint
- update log messages so they state whether a comparison used a logical or geometry-sensitive fingerprint

Expected outcome:

- `Google services` can be replayed and validated successfully even if the toolbar or tab strip re-enters with shifted coordinates
- recoverable traversal failures occur only when logical screen content actually changes

## Part 3: Align Service-Level Recovery

Primary file:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`

Changes:

- replace root-recovery equality checks that currently rely on `viewportFingerprint(...)` with the logical fingerprint
- keep click targeting and fallback scoring unchanged, including bounded-tolerance geometry checks used to score click candidates

Expected outcome:

- service-side root recovery no longer disagrees with crawler-side replay validation
- click behavior remains unchanged because this fix is about screen-state equality, not target matching

## Validation Plan

### Unit tests

- add tests proving logical viewport fingerprints remain equal when only root or element bounds shift
- add tests proving logical viewport fingerprints still differ when semantic content changes
- add tests covering logical entry fingerprint behavior with and without a visible back affordance

### Coordinator tests

- add a `DeepCrawlCoordinatorTest` case where replay returns to the same logical screen with shifted bounds and traversal continues
- verify the edge is not failed just because geometry changed

### Regression checks

- keep existing scroll-scan tests passing so scan progression still depends on geometry-sensitive viewport changes
- verify service-level recovery paths use the same logical comparison semantics as deep crawl replay

## Assumptions

- this fix does not change screen dedup identity in `ScreenNaming`
- this fix does not remove bounds from click targeting or fallback candidate scoring
- if later evidence shows geometry also harms scroll progression, that should be handled as a follow-up change rather than bundled into this fix
