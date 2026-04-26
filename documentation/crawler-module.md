# Crawler Module

## Goal

The crawler launches a selected app, restores the entry screen, and performs a
safe deep crawl across reachable in-app targets. Each captured screen is
exported as HTML and XML, and the crawl manifest records captured, linked, and
skipped traversal outcomes.

## Main components

### `CrawlerSession`

- Holds shared UI state for the current capture request.
- Tracks launch, waiting, scanning, traversal, paused-for-decision, success, and failure phases.
- Exposes `StateFlow` so the Compose UI can react to progress.
- Owns the decision-token handshake used when a paused crawl is resumed, stopped, or told to skip an external edge.

### `AppToHtmlAccessibilityService`

- Listens for accessibility events from the target package.
- Starts the first capture after the app becomes active.
- Delegates traversal work to the crawler domain classes.
- Performs live scroll actions against the current accessibility tree.
- Routes pause decisions back into `CrawlerSession` when the coordinator needs user input.

### `DeepCrawlCoordinator`

- Restores the root entry screen and replays known routes before expanding a discovered screen.
- Runs breadth-first traversal across safe pressable elements until the frontier is exhausted or the user stops.
- Persists crawl progress incrementally, including `crawl-index.json`, `crawl-graph.json`, and `crawl-graph.html`.
- Pauses the crawl for elapsed-time checkpoints, failed-edge checkpoints, and external-package boundaries.

### `PauseCheckpointTracker`

- Tracks elapsed-time and failed-edge warning budgets independently.
- Produces the progress snapshot shown in the paused UI.
- Rolls forward only the checkpoint budget that actually triggered.

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

### `CrawlGraphBuilder`, `CrawlGraphJsonWriter`, and `CrawlGraphHtmlRenderer`

- Derive a normalized graph dataset from the current manifest snapshot.
- Emit a machine-readable graph JSON file beside the crawl manifest.
- Emit a self-contained offline HTML viewer with filtering, pan/zoom, and sibling artifact links.

## Current scan flow

1. UI triggers `CrawlerSession.startCapture`.
2. App launch helper launches the selected app.
3. Accessibility service waits for the target package to be active.
4. The deep crawl coordinator restores the entry screen and captures the root screen.
5. Scroll scanning rewinds to the top of the current surface and merges unique elements.
6. Safe elements are ordered deterministically and filtered through the blacklist.
7. Eligible targets are replayed breadth-first until the frontier is exhausted.
8. Manifest and graph artifacts are refreshed in app storage as progress is made.
9. If a checkpoint or external-package boundary fires, the current state is saved before AppToHTML is brought back to the foreground.
10. The user can continue or stop and save for checkpoint pauses; external-package boundaries can be continued or skipped.
11. AppToHTML is brought back to the foreground when the crawl completes or aborts.

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
- Large crawl sessions produce a larger inline graph payload because `crawl-graph.html` embeds the serialized graph data.
- Scroll behavior depends on what the target app exposes through accessibility.

## Good next extensions

- Track repeated list rows as grouped collections instead of collapsing them.
- Persist richer crawl sessions for later replay or diffing.
