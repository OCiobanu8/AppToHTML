# Crawler Module

## Goal

The crawler launches a selected app, restores the entry screen, and performs a
safe deep crawl across reachable in-app targets. Each captured screen is
exported as HTML and XML, and the crawl manifest records captured, linked, and
skipped traversal outcomes.

## Main components

### `CrawlerSession`

- Holds shared UI state for the current capture request.
- Tracks launch, waiting, scanning, traversal, success, and failure phases.
- Exposes `StateFlow` so the Compose UI can react to progress.

### `AppToHtmlAccessibilityService`

- Listens for accessibility events from the target package.
- Starts the first capture after the app becomes active.
- Delegates traversal work to the crawler domain classes.
- Performs live scroll actions against the current accessibility tree.

### `AccessibilityTreeSnapshotter`

- Converts `AccessibilityNodeInfo` into serializable snapshots.
- Extracts pressable elements from the tree.
- Preserves safety-relevant element flags such as `checkable` and `editable`.
- Detects list-like containers and scrollable candidates.

### `ScrollScanCoordinator`

- Settles the current viewport before merging it.
- Scrolls up to reach a stable top-of-page position.
- Scrolls down while the viewport still changes.
- Merges newly discovered elements by logical identity.

### `ScreenNaming`

- Chooses a useful screen name from event classes, resource IDs, or launcher
  metadata.

### `HtmlRenderer` and `AccessibilityXmlSerializer`

- Transform the merged snapshot into user-facing output files.

## Current scan flow

1. UI triggers `CrawlerSession.startCapture`.
2. App launch helper launches the selected app.
3. Accessibility service waits for the target package to be active.
4. The deep crawl coordinator restores the entry screen and captures the root screen.
5. Scroll scanning rewinds to the top of the current surface and merges unique elements.
6. Safe elements are ordered deterministically and filtered through the blacklist.
7. Eligible targets are replayed breadth-first until the frontier is exhausted.
8. Screen artifacts and the crawl manifest are refreshed in app storage as progress is made.
9. AppToHTML is brought back to the foreground when the crawl completes or aborts.

## Merge strategy

Pressable elements are deduplicated by:

- label
- resource ID
- class name
- list-item flag
- `checkable`
- `editable`

This keeps the merged screen focused on unique controls rather than viewport
positions.

## Known tradeoffs

- Repeated list rows with identical metadata collapse into one merged element.
- Cross-package boundary handling and pause/resume checkpoints still live in later deep-crawl blocks.
- Scroll behavior depends on what the target app exposes through accessibility.

## Good next extensions

- Track repeated list rows as grouped collections instead of collapsing them.
- Add explicit pause/resume checkpoints and package-boundary decisions.
- Persist richer crawl sessions for later replay or diffing.
