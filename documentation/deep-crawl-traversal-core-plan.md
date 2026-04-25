# Deep Crawl Traversal Core

## Summary

Implement the next PR slice as the full Traversal Core milestone, not just a file move: extract crawl orchestration out of `AppToHtmlAccessibilityService.kt`, add route metadata so any discovered screen can be replayed from the entry screen, and replace the current one-layer child loop with breadth-first frontier expansion until no eligible in-app edges remain.

This keeps the current UI/session flow intact for now and builds on the graph identity groundwork that already exists locally (`screenFingerprint` tracking and `LINKED_EXISTING` support).

## Key Changes

### 1. Add a dedicated coordinator

- Create a new crawler-owned coordinator, for example `DeepCrawlCoordinator`, under `app/src/main/java/com/example/apptohtml/crawler/`.
- Move the full crawl transaction out of the service into the coordinator: entry reset, root scan, frontier processing, manifest saves, edge recording, root recovery, and final summary creation.
- Keep the service responsible only for Android service concerns:
  - debounce/accessibility event gating
  - reading `rootInActiveWindow`
  - performing click/scroll/back actions
  - launching the coordinator and forwarding progress/errors into `CrawlerSession`
- Pass service capabilities into the coordinator through a small interface, not direct service references. The interface should cover:
  - capture current root snapshot
  - scroll forward/backward on a path
  - click a pressable element
  - perform global back
  - relaunch/foreground the target app if needed
  - publish progress text

### 2. Add replayable route metadata

- Extend the screen model with explicit route data from the entry screen to the screen:
  - ordered list of traversal steps
  - each step stores the triggering `PressableElement` identity needed for replay, including `childIndexPath`, `bounds`, `resourceId`, `className`, `label`, `checkable`, `checked`, and `firstSeenStep`
- Persist that route on newly discovered screens in the in-memory tracker and manifest output.
- Preserve `parentScreenId` as first-discovery parent only; route data is the source of truth for replay.
- Add a coordinator helper that:
  - restores to the entry screen
  - replays each route step from the root
  - verifies the expected destination fingerprint after replay
  - fails the branch cleanly if replay diverges

### 3. Replace one-layer traversal with BFS frontier expansion

- Introduce a queue of discovered-but-not-yet-expanded screen ids.
- After the root screen is stored, enqueue it and loop until the queue is empty.
- For each dequeued screen:
  - replay from the entry screen using stored route metadata
  - verify the live screen fingerprint matches the queued screen before expansion
  - scan that screen and build its eligible/skipped edge list with `TraversalPlanner`
  - record skipped edges immediately
  - attempt each eligible edge in deterministic order
- When an edge opens a known screen fingerprint:
  - add an edge with `LINKED_EXISTING`
  - do not enqueue or rescan that screen
- When an edge opens a new screen:
  - save files
  - create a new screen record with `depth = parent.depth + 1`
  - attach its route as `parent.route + currentEdge`
  - add a `CAPTURED` edge
  - enqueue the new screen id
- End the crawl when the queue is empty, replacing the current “root plus direct children” completion condition.

### 4. Keep artifacts and summaries compatible during this slice

- Continue writing `crawl-index.json` incrementally after each screen/edge mutation.
- Continue rewriting the originating screen HTML when a child link becomes resolvable.
- Update summary calculation to report:
  - total captured screens
  - total skipped edges
  - max discovered depth
- Do not introduce pause/resume UI, warning thresholds, `crawl-graph.json`, or `crawl-graph.html` in this slice.

## Public Interfaces / Types

- Add a route type, e.g. `CrawlRouteStep` and `CrawlRoute`, to `CrawlerModels.kt`.
- Extend `CrawlScreenRecord` with persisted route metadata and keep `depth`.
- Extend `CrawlRunSummary` and `CrawlerUiState` with `maxDepthReached`.
- Add a coordinator host interface for service callbacks; keep it internal to the crawler package unless another caller needs it.

## Test Plan

- Root chain `A -> B -> C -> D` exhausts the frontier and records depths `0..3`.
- Cycle `A -> B -> A` terminates without re-enqueueing `A`.
- Two different parents leading to the same fingerprint record two edges but one screen node.
- Replay failure for an intermediate route step marks the affected edge failed and continues with the remaining frontier when recovery succeeds.
- Re-expanding a queued screen verifies its fingerprint before scanning and aborts that branch if the destination no longer matches.
- Manifest snapshots remain valid after partial progress and include route metadata plus the correct `maxDepthReached`.

## Assumptions

- “Next development step” means the next implementation slice after the documentation PR: the Traversal Core milestone.
- Existing dedup work already in the codebase stays in place and is reused rather than reworked first.
- UI copy renames, pause/resume warnings, expanded blacklist behavior, external-package status handling, and graph export stay out of scope until this traversal-core slice is stable.
