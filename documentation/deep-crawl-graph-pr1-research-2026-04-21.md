# Deep Crawl Graph PR #1 — Continuation Research

Date: 2026-04-21
Branch: `codex/deep-crawl-graph`
PR: https://github.com/OCiobanu8/AppToHTML/pull/1
Plan of record: [deep-crawl-graph-plan.md](deep-crawl-graph-plan.md)

## Summary

The branch is 3 commits ahead of `main` (`b2186f2` plan → `926cd6a` fingerprint dedup → `769a0f8` traversal core). The **Traversal Core** milestone from `deep-crawl-graph-plan.md` is essentially complete; subsequent milestones (expanded safety rules, pause/resume, UI rename, desktop graph export) are largely unstarted. This document maps the current implementation state against the plan checklist with file:line anchors so the next slice can be picked up cleanly.

## What's Done

| Plan item | State | Anchor |
|---|---|---|
| Coordinator extraction out of the service | Done | `DeepCrawlCoordinator.kt:9-22`; `Host` interface `:1189-1198` |
| BFS frontier expansion (replaces one-layer loop) | Done | `DeepCrawlCoordinator.kt:123-167` (ArrayDeque FIFO); enqueue `:485` |
| Route metadata (`CrawlRouteStep`, `CrawlRoute`) | Done | `CrawlerModels.kt:156-164`; conversions `:199-223` |
| Route replay + fingerprint re-verify before expansion | Done | `DeepCrawlCoordinator.kt:537-588` (`prepareScreenForExpansion`); divergence handling `:590-630` |
| Fingerprint dedup → `LINKED_EXISTING` edges | Done | `CrawlRunTracker.kt:10,42-44`; dedup flow `DeepCrawlCoordinator.kt:388-441` |
| Weak-title guard (skip dedup when confidence weak) | Done | `ScreenNaming.kt:107-138,607` (`canLinkToExisting`) |
| Persisted `screenFingerprint`, `maxDepthReached` | Done | `CrawlerModels.kt:322-331`; manifest saved after every material change |
| Expanded blacklist tokens (all 8 categories) | Done | `app/src/main/res/raw/crawl_blacklist.json:1-181` |
| `skipCheckable=true` preserved | Done | `CrawlBlacklist.kt:11`; enforced `:29-31` |
| Recovery / relaunch / abort paths | Done | `DeepCrawlCoordinator.kt:817-886` |
| Test harness (71 tests, 0 `@Ignore`d) | Strong | `DeepCrawlCoordinatorTest.kt` (10), `CrawlerTraversalTest.kt` (7); replay outcomes fully covered in `PathReplayResolverTest.kt` |

## What's Remaining

Ordered by size / independence.

### A. Editable capture + `skipEditable` (small, self-contained)

- `AccessibilityNodeSnapshot` has no `isEditable` field — `CrawlerModels.kt:243-264`.
- `PressableElement` has no `editable` field — `CrawlerModels.kt:144-154`.
- `AccessibilityTreeSnapshotter.kt:39-112` does not query `node.isEditable()` anywhere.
- `CrawlBlacklist.kt:7-12` lacks `skipEditable`; loader `:59-66` does not parse it; `crawl_blacklist.json` does not declare it.
- `CrawlRouteStep` plus `toRouteStep()`/`toPressableElement()` conversions must be updated to carry the new field.
- Tests to add alongside existing `CrawlerTraversalTest.kt:12-117` blacklist tests.

### B. `SKIPPED_EXTERNAL_PACKAGE` status (small)

- `CrawlEdgeStatus` has 5 variants today — `CrawlerModels.kt:273-279`. No `SKIPPED_EXTERNAL_PACKAGE`.
- `DeepCrawlCoordinator.expandScreen` (`:222-535`) has no package check before capture; `:385` falls back to the child's own `packageName`. Insert the package-leave check right after `childInitialRoot` is captured and before the dedup/fingerprint block.
- `DeepCrawlCoordinatorTest.bfsTraversal_allows_cross_package_child_screens` (`DeepCrawlCoordinatorTest.kt:111`) currently asserts the opposite behavior — flip when this lands; add a new test asserting the skip status is emitted and no frontier entry is queued.

### C. UI copy rename "one-layer" → "deep crawl" (trivial)

- Button `MainActivity.kt:177`.
- Explainer `MainActivity.kt:191-194`.

### D. Pause/Resume safety checkpoints (biggest remaining slice, design-heavy)

- Add `CrawlerPhase.PAUSED_FOR_DECISION` — `CrawlerModels.kt:7-16`.
- Extend `CrawlerUiState` with `pauseReason`, `elapsedTimeMs`, `failedEdgeCount`.
- Threshold tracker + roll-forward config (default: warn at 30 min / 30 failed edges; then every +15 / +15) passed into the coordinator; checkpoint probe inside the frontier loop (`DeepCrawlCoordinator.kt:123-167`) and on every `FAILED` edge (`:523-528`).
- Coordinator needs a suspendable resume/stop channel (shared flow or callback).
- `CrawlerSession.kt` needs `pauseForDecision()` (reusing `returnToApp()` at `:202-209`), `resumeCrawl(requestId)`, `stopAndSave(requestId)`.
- New `AlertDialog` block in `MainActivity.kt` alongside the existing picker at `:246-283`, showing pause reason, elapsed time, captured/failed counts.
- No tests exist for any of this yet.

### E. Desktop graph export (medium; mostly new code, no tricky wiring)

- New `GraphExporter.kt` + `GraphHtmlRenderer.kt` under `app/src/main/java/com/example/apptohtml/crawler/`.
- Extend `CrawlSessionDirectory` (`CrawlManifestStore.kt:5-10`) with `graphJsonFile` and `graphHtmlFile`.
- Call site: every `saveManifest(...)` point in `DeepCrawlCoordinator.kt` (lines 111, 257, 377, 500, 529, 626, 169-173) should also emit graph artifacts so the export is incremental.
- Surface `graphJsonPath` / `graphHtmlPath` on `CrawlRunSummary` and render them in the captured-state UI block (`MainActivity.kt:197-217`).
- Deterministic SVG layout: depth columns × discovery order within depth; style edges by `CrawlEdgeStatus`; inline JS for zoom/pan, filter-by-status, neighbor highlight; fully offline, no external fetches.
- Zero test coverage today.

## Test Coverage Snapshot

| Theme | Coverage | Evidence |
|---|---|---|
| BFS traversal & max-depth | Covered | `DeepCrawlCoordinatorTest.bfsTraversal_captures_nested_chain_and_reports_max_depth:16` |
| Cycle dedup via fingerprint | Covered | `DeepCrawlCoordinatorTest.bfsTraversal_terminates_cycle_by_linking_existing_screen:71` |
| Cross-package capture (currently permitted) | Covered (wrong direction) | `DeepCrawlCoordinatorTest:111` |
| Replay full/partial/missing | Covered | `PathReplayResolverTest.kt:9,25,42` |
| Replay divergence recovery | Covered | `DeepCrawlCoordinatorTest:153,205,253` |
| Blacklist (label/resourceId/class/checkable) | Covered | `CrawlerTraversalTest.kt:12,74,120` |
| Screen-name fingerprint (weak vs strong) | Covered | `ScreenNamingTest.kt:10,48`; `CrawlerTraversalTest.kt:171,183` |
| Editable blacklist | Not covered | feature absent |
| `SKIPPED_EXTERNAL_PACKAGE` | Not covered | status absent |
| Pause/resume, threshold roll-forward | Not covered | feature absent |
| Graph export (JSON + offline HTML) | Not covered | feature absent |

## Known Limitation Carried on This Branch

Settings-style split-pane layouts can falsely accept the wrong live UI as the root entry, draining the frontier with `status=completed`. Documented in [crawler-settings-early-stop-root-cause-2026-04-07.md](crawler-settings-early-stop-root-cause-2026-04-07.md). Not blocked by any of A–E.

## Recommended Continuation Order

1. **A + B + C as one PR** — closes the "Safety Rules" checklist in `deep-crawl-graph-plan.md`, expands test coverage against the existing fixtures, and is low risk.
2. **D (pause/resume)** — next PR; requires protocol design between `CrawlerSession` and `DeepCrawlCoordinator` for user-gated continues.
3. **E (graph export)** — can land in parallel with D; no dependency beyond existing manifest structure.
