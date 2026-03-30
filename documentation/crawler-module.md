# Crawler Module

## Goal

The crawler captures the first screen of a selected app and exports a merged
representation of that screen as HTML and XML. For scrollable screens, it first
rewinds to the top, then crawls downward until the viewport stops changing or a
safety cap is reached.

## Main components

### `CrawlerSession`

- Holds shared UI state for the current capture request.
- Tracks launch, waiting, scanning, success, and failure phases.
- Exposes `StateFlow` so the Compose UI can react to progress.

### `AppToHtmlAccessibilityService`

- Listens for accessibility events from the target package.
- Starts the first capture after the app becomes active.
- Delegates traversal work to the crawler domain classes.
- Performs live scroll actions against the current accessibility tree.

### `AccessibilityTreeSnapshotter`

- Converts `AccessibilityNodeInfo` into serializable snapshots.
- Extracts pressable elements from the tree.
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
4. The initial accessibility tree is captured and settled.
5. The scan rewinds to the top of the current scrollable surface.
6. Step `0` is merged into the screen map.
7. The crawler scrolls downward and captures each changed viewport.
8. HTML and XML are written to app storage.
9. AppToHTML is brought back to the foreground.

## Merge strategy

Pressable elements are deduplicated by:

- label
- resource ID
- class name
- list-item flag

This keeps the merged screen focused on unique controls rather than viewport
positions.

## Known tradeoffs

- Repeated list rows with identical metadata collapse into one merged element.
- The crawler currently maps only the first screen, not a full app graph.
- Scroll behavior depends on what the target app exposes through accessibility.

## Good next extensions

- Track repeated list rows as grouped collections instead of collapsing them.
- Follow selected pressable elements into secondary screens.
- Persist richer crawl sessions for later replay or diffing.
