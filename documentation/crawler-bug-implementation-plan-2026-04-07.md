# Crawler Bug Implementation Plan 2026-04-07

## Goal

Fix the confirmed crawler bug from `E:/Logs/crawl_20260407_112741` by:

- preventing stale `childIndexPath` replay from crashing the run
- preventing weak screen names like `Navigate up` from driving dedup
- reducing false-positive `linked_existing` decisions during deep crawl traversal

## Recommended Approach

Implement a layered fix in three parts:

1. bounds-safe path replay
2. stronger screen naming
3. stronger screen dedup identity

This is the best balance between correctness, implementation size, and validation effort.

## Part 1: Make Path Replay Safe

Primary file:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`

Changes:

- update `resolvePathNodes(...)` so it checks live child bounds before calling `getChild(index)`
- stop path resolution at the last valid node when the live tree no longer matches the stored path
- avoid throwing when a stored child index is out of range
- expose enough information to the caller to distinguish:
  - full path resolution
  - partial path resolution
  - no useful path resolution

Caller behavior:

- `performClick(...)` should treat partial resolution as a replay mismatch, not a fatal exception
- `performScroll(...)` should do the same
- both methods should continue using fallback candidate selection when the stored path no longer fully resolves
- if no fallback candidate works, return a normal failure so the crawl can mark the edge as recoverable or failed without crashing

Logging:

- add a dedicated log entry for path divergence
- include:
  - intended path
  - resolved depth
  - failing child index
  - current node child count

## Part 2: Improve Screen Naming

Primary file:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt`

Changes:

- add rejection or strong penalties for generic chrome labels used as screen titles
- include labels like:
  - `Navigate up`
  - `More options`
  - `Recommended`
  - `All services`
  - generic sign-in CTA text when stronger content exists
- avoid choosing clickable toolbar affordances as the screen title when a stronger non-generic candidate is present
- prefer stronger top-of-screen title signals such as:
  - visible non-clickable header text
  - stable title resource ids
  - top app bar content descriptions when they clearly represent the destination

Expected outcome:

- the Google Services screen captured from `Google` should no longer be named `Navigate up`
- the SIM details screen should also avoid collapsing to the same generic name

## Part 3: Strengthen Dedup Fingerprinting

Primary files:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- replace the current name-only fingerprint format `v1:name:<screen name>` with a stronger versioned format
- derive the new fingerprint from a combination of:
  - package name
  - chosen screen title
  - small stable top-level identity hints from the visible screen
- do not treat weak generic names as high-confidence dedup keys
- only apply `linked_existing` when the screen identity is strong enough
- when identity is weak or ambiguous, capture a new child screen instead of reusing an existing one

Expected outcome:

- `SIMs` should no longer link to the earlier Google Services page
- unrelated screens with generic chrome text should stop colliding under the same dedup id

## Validation Plan

### Unit and logic tests

- add a test for safe path resolution where a stored child index is out of range
- verify the result is a controlled mismatch, not an exception
- add naming tests where generic toolbar labels compete with a real title
- verify the real title wins
- add dedup tests where two unrelated snapshots previously both produced `Navigate up`
- verify they now produce different fingerprints

### Crawl validation

Re-run the same Settings crawl path and verify:

- the crawler no longer crashes with `length=1; index=1`
- the Google Services screen is captured under a stronger name
- `SIMs` is captured as its own child screen instead of `linked_existing -> screen_001`
- replay failures, if any remain, are recorded as recoverable traversal issues rather than unexpected crawler exceptions

## Pros And Cons Of This Plan

### Pros

- removes the hard crash
- fixes the specific mis-dedup pattern shown by this log bundle
- keeps the current crawler architecture intact
- limits scope to the areas directly implicated by the logs

### Cons

- screen naming and identity remain heuristic-driven
- some edge cases in other apps may still need tuning after the first pass
- dedup will likely become more conservative, which may temporarily produce more distinct captured screens

## Non-Goal For This Pass

This plan does not attempt a full crawler identity redesign. It keeps the current route replay model and improves the weak points exposed by this failure instead of replacing traversal or dedup end-to-end.
