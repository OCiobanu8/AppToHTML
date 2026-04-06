# Deep Crawl Graph Feature

## Summary

Implement a deep graph crawler that explores safe in-app edges until the frontier is exhausted, with no hard screen-count cap. Replace hard operational cutoffs with soft warning checkpoints that pause the crawl, return to AppToHTML, and let the user either continue or stop and save.

Persist the crawl for desktop inspection only:

- `crawl-index.json` as the authoritative crawl manifest
- `crawl-graph.json` as a normalized graph dataset
- `crawl-graph.html` as a self-contained offline viewer intended to be opened later on a PC from the downloaded crawl folder

## Delivery Setup

- Branch: `codex/deep-crawl-graph`
- Plan file: `documentation/deep-crawl-graph-plan.md`
- Draft PR title: `Add deep crawl graph exploration and desktop graph export`

## Key Changes

### 1. Deep traversal becomes a graph crawl

- Replace the one-layer child traversal loop with a breadth-first frontier of discovered screens.
- For each frontier screen:
  - restore to the entry/root screen
  - replay the known route to that screen
  - verify the destination fingerprint
  - enumerate safe outgoing edges
  - capture newly discovered child screens and enqueue them
- End only when no eligible unmapped safe edges remain.

### 2. Safety becomes pause-and-decide, not auto-stop

- Remove the hard unique-screen limit entirely.
- Track soft operational thresholds:
  - elapsed crawl time
  - accumulated failed edge attempts
- Default warning thresholds:
  - first warning at 30 minutes
  - first warning at 30 failed edges
- On threshold hit:
  - pause traversal
  - persist manifest and graph artifacts
  - return AppToHTML to foreground
  - show an `AlertDialog`
- Dialog actions:
  - `Continue`
  - `Stop and save`
- If the user continues, warning thresholds roll forward:
  - every additional 15 minutes
  - every additional 15 failed edges
- Keep only true hard stops for fatal runtime failure, irrecoverable recovery failure, or explicit user stop.

### 3. Safety model is expanded

- Broaden blacklist coverage for:
  - auth/session/account actions
  - destructive/data-loss actions
  - payments/subscriptions
  - publish/send/submit/create actions
  - permissions/consent/system settings
  - external handoff flows
  - camera/call/message/share/import/export actions
- Add `skipEditable = true`.
- Keep `skipCheckable = true`.
- Treat leaving the target package as a hard skip status, not a blacklist match.
- Re-verify expected screen fingerprint before expanding a previously discovered screen.

### 4. Graph identity and deduplication

- Maintain a per-run fingerprint-to-screen map.
- If an edge lands on an already known screen:
  - link the edge to the existing node
  - do not rescan it
  - do not enqueue it again
- Add edge statuses:
  - `LINKED_EXISTING`
  - `SKIPPED_EXTERNAL_PACKAGE`
- Keep `screens[].parentScreenId` as first-discovery parent only.
- Treat `edges[]` as the authoritative graph relationship list.

### 5. Desktop graph export

- Save these files in each crawl session folder:
  - `crawl-index.json`
  - `crawl-graph.json`
  - `crawl-graph.html`
- The HTML viewer is for local desktop viewing after downloading the crawl folder, not for in-app rendering.
- The viewer must be self-contained and work offline from a local browser.
- Render a deterministic SVG graph with:
  - depth-based columns
  - discovery order within each depth
  - multiple incoming edges preserved
- Display node name, id, depth, and output artifact references.
- Style edges by status.
- Include lightweight offline controls for zoom/pan, filtering skipped/failed edges, and neighbor highlighting.
- Update graph artifacts incrementally throughout the crawl.

## Public Interfaces / Types

- `PressableElement`: add `editable: Boolean`
- `CrawlBlacklist`: add `skipEditable: Boolean`
- `CrawlEdgeStatus`: add `LINKED_EXISTING`, `SKIPPED_EXTERNAL_PACKAGE`
- `CrawlerPhase`: add a paused-for-decision phase
- `CrawlerUiState`: add:
  - `maxDepthReached`
  - `graphJsonPath`
  - `graphHtmlPath`
  - `pauseReason`
  - `canContinueAfterWarning`
- `CrawlerSession`: add explicit resume/stop APIs for warning checkpoints

## Development Tasks

### Branch And Planning

- [ ] Create branch `codex/deep-crawl-graph`
- [ ] Add `documentation/deep-crawl-graph-plan.md`
- [ ] Open a draft PR titled `Add deep crawl graph exploration and desktop graph export`
- [ ] Paste this checklist into the PR body

### Traversal Core

- [ ] Refactor traversal orchestration out of `AppToHtmlAccessibilityService` into a dedicated deep-crawl coordinator
- [ ] Add route metadata needed to replay navigation from the root screen to any discovered screen
- [ ] Replace the one-layer traversal loop with breadth-first frontier expansion
- [ ] End crawls on frontier exhaustion instead of layer exhaustion

### Graph Identity

- [ ] Add per-run screen fingerprint deduplication
- [ ] Link edges to existing screens when a known screen is revisited
- [ ] Add edge statuses for `LINKED_EXISTING` and `SKIPPED_EXTERNAL_PACKAGE`
- [ ] Keep graph relationships authoritative in `edges[]`

### Safety Rules

- [ ] Expand blacklist tokens across the agreed risky action categories
- [ ] Add `editable` capture to accessibility snapshots and `PressableElement`
- [ ] Add `skipEditable` support to blacklist config and evaluation
- [ ] Preserve `skipCheckable = true`
- [ ] Treat leaving the target package as a hard skip outcome
- [ ] Re-verify screen fingerprints before expanding previously discovered screens

### Pause/Resume Flow

- [ ] Add soft safety checkpoints for elapsed time
- [ ] Add soft safety checkpoints for repeated edge failures
- [ ] Add paused-for-decision state to crawler session models
- [ ] Persist progress before showing a warning
- [ ] Return AppToHTML to the foreground when a warning is triggered
- [ ] Add `Continue` and `Stop and save` session actions
- [ ] Roll warning thresholds forward after each user-approved continue

### UI

- [ ] Rename one-layer crawl UI copy to deep crawl
- [ ] Update explainer text to match the new crawl contract
- [ ] Add warning `AlertDialog` for paused safety checkpoints
- [ ] Show current progress and pause reason in the dialog
- [ ] Surface graph export paths and max depth in the final crawl summary

### Export

- [ ] Add `crawl-graph.json` export format
- [ ] Add self-contained offline `crawl-graph.html`
- [ ] Render deterministic SVG graph layout by depth and discovery order
- [ ] Add node metadata and local artifact references
- [ ] Style edges by crawl status
- [ ] Add offline controls for zoom/pan, filtering, and neighbor highlighting
- [ ] Update manifest and graph exports incrementally during crawl progress

### Documentation And Tests

- [ ] Update crawler documentation to replace one-layer wording
- [ ] Document desktop graph export usage
- [ ] Add tests for deep traversal chains
- [ ] Add tests for cycles and deduplication
- [ ] Add tests for external-package skips
- [ ] Add tests for editable/checkable blacklist handling
- [ ] Add tests for pause/resume checkpoints
- [ ] Add tests for incremental graph export and offline viewer generation

## Test Plan

- Deep chain `A -> B -> C -> D` crawls until frontier exhaustion and reports the correct max depth.
- Cycle `A -> B -> A` links back to the existing screen and does not loop forever.
- Two different triggers landing on the same screen reuse one screen record.
- External-package transition records `SKIPPED_EXTERNAL_PACKAGE`.
- Editable and checkable targets are skipped.
- Time checkpoint pauses the crawl, saves artifacts, and exposes continue/stop actions.
- Choosing `Continue` resumes and can trigger another warning later.
- Choosing `Stop and save` ends cleanly without marking the run as failed.
- Manifest and graph exports stay synchronized during partial progress.
- `crawl-graph.html` works offline from the downloaded crawl folder.

## Assumptions

- “As deep as possible” means exhausting all safe, non-blacklisted, in-app unmapped edges.
- Desktop inspection means opening the exported graph locally on a PC browser.
- Breadth-first traversal is the first implementation because it keeps graph growth predictable and easier to inspect visually.
- The draft PR should remain the working checklist for implementation progress.
