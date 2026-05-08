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

### `DestinationSettler`

- Settles clicked destinations after a traversal target is pressed.
- Samples the active root for a fixed dwell window before choosing the authoritative destination.
- Ranks eligible samples by richness metrics such as visible text, pressable controls, fingerprint length, scrollable nodes, and loading indicators.
- Keeps sparse destinations valid while making sparse-to-rich transitions diagnosable in `crawl.log`.

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
7. After each click, destination settling observes post-click roots before no-navigation checks, external-package pause handling, and child scanning.
8. Eligible targets are replayed breadth-first until the frontier is exhausted.
9. Manifest and graph artifacts are refreshed in app storage as progress is made.
10. If a checkpoint or external-package boundary fires, the current state is saved before AppToHTML is brought back to the foreground.
11. The user can continue or stop and save for checkpoint pauses; external-package boundaries can be continued or skipped.
12. AppToHTML is brought back to the foreground when the crawl completes or aborts.

## Settling behavior

Scroll viewport settling and clicked-destination settling solve different
problems. `ScrollScanCoordinator` samples around scroll and back transitions so
the current viewport is stable before it is merged into a screen capture.
`DestinationSettler` runs after a click, when the crawler must decide which
root is the child destination for no-navigation detection, external-boundary
decisions, route metadata, and child screen scanning.

Destination settling does not reject sparse roots outright. It records every
sample in `crawl.log` with package, fingerprint, eligibility, richness metrics,
and whether the sample became the current best candidate. After the fixed dwell,
the best eligible sample is selected. This lets legitimate one-control screens
settle successfully while allowing a transitional root, such as a toolbar-only
or empty loading state, to be replaced by a richer later root.

For external-package Continue, restore validation still requires the expected
package. Destination identity is validated by compatibility over the settled
expected and restored roots, not by exact raw fingerprint equality alone. Exact
fingerprint matches pass, and compatible enrichment can pass when pressable
identity and richness signals show that the restored root is the same
destination with more loaded content. Incompatible same-package destinations
still fail the edge.

## Entry restore as a verified replay prerequisite

Entry restore is split between initial discovery and replay/recovery:

- Initial discovery may use the no-back-affordance assumed-entry policy when no
  expected entry fingerprint is known yet.
- Replay/recovery requires that the observed root match the captured entry
  logical fingerprint. `EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL` and
  `verifiedForReplay=true` are the only success modes for that path.
- `restoreToEntryScreenOrRelaunch(...)` treats an expected entry fingerprint as
  a bounded settle operation. Current-package restore continues to relaunch when
  the expected logical fingerprint is not observed; relaunch restore samples
  until the expected entry root appears or the bounded restore window expires.
- `EntryScreenBackAffordanceDetector` resolves descendant labels for top or
  toolbar-aligned clickable parents so Compose-style `Navigate up` controls are
  recognised as in-app back affordances.

`entry_restore_*` log entries record observed/expected/matched/verified fields
so the source of every assumed-entry decision is auditable.

## Click fallback eligibility

`ClickFallbackMatcher` evaluates fallback candidates after path-based clicking
diverges. It accepts only candidates that match the intended element on at
least one of: exact non-blank resource ID, exact non-blank resolved label,
exact class name plus compatible bounds, or strong bounds compatibility for
unlabeled icon-like controls. Class-only or check-state-only matches are
rejected. Checkable, depth, and tie-breaker signals are used only to rank
already-eligible candidates, never to make a candidate eligible. Fallback
attempts and rejection reasons are logged for diagnosis.

## Route replay step validation

`replayRouteToScreen(...)` validates each route step against the screen it is
expected to reach. Each `CrawlScreenRecord` stores a bounds-free
`replayFingerprint` derived from `logicalEntryViewportFingerprint(topRoot)` for
the root screen and `logicalViewportFingerprint(topRoot)` for non-root screens.
`CrawlRouteStep` carries the expected replay fingerprint and screen name for
the screen that step should reach. After each settled step click, the observed
logical fingerprint is compared with the expected replay fingerprint:

- A match continues the replay.
- A mismatch fails immediately with a `replay_route_step_validation` log entry
  naming the route step, expected screen, expected fingerprint, and observed
  fingerprint, and the edge is reported as failed.

The "fingerprint changed from before/top" check still runs as a no-navigation
guard but is no longer the only success criterion when expected metadata
exists. Stored replay fingerprints are bounds-free; geometry-sensitive
fingerprints remain local to scroll progress and settling.

## Diagnostic guidance

When investigating a failed or oscillating crawl, inspect the following
`crawl.log` fields:

- `entry_restore_*` for observed, expected, matched, and verified entry status
- `screen_top_validation` for parent restore mismatches
- `live_action_path_diverged` and fallback attempt/rejection logs for stale
  fallback selection
- `replay_route_step_*` for expected and observed replay fingerprints at every
  intermediate route step
- `external_boundary_restore_result` for external-package Continue compatibility
  decisions

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
