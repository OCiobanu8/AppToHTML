# Pause and Resume Flow

## Goal

Deep crawl checkpoints are soft warnings, not hard stops. When traversal hits a
checkpoint, AppToHTML saves the latest crawl state, returns to the foreground,
and asks the user how to proceed.

## Pause triggers

The crawler pauses for three reasons:

- elapsed crawl time exceeded
- failed-edge count exceeded
- traversal crossed into an external package

Default checkpoint thresholds are defined in `PauseCheckpointConfig`:

- first elapsed-time warning at 30 minutes
- later elapsed-time warnings every 15 minutes
- first failed-edge warning at 30 failed edges
- later failed-edge warnings every 15 failed edges

External-package boundaries are immediate decision points rather than threshold
rollovers.

## Persistence before prompt

When a pause is triggered, the coordinator writes the latest session artifacts
before any dialog is shown:

- `crawl-index.json`
- `crawl-graph.json`
- `crawl-graph.html`

This matters because the service can be interrupted after the pause begins. The
saved artifacts keep the crawl inspectable even if the app process or service
dies before the user responds.

## Decision-token protocol

`CrawlerSession` creates a fresh `pauseDecisionId` for each pause. The active
pause is identified by both:

- `requestId`
- `pauseDecisionId`

UI actions must present both values when resolving a pause. If either value is
stale, the action is ignored.

This prevents a button tap from an older dialog from accidentally resolving a
later pause in the same crawl.

## User-visible pause state

While paused, `CrawlerUiState` stores:

- the pause reason
- elapsed time at pause
- failed-edge count at pause
- captured screen counts
- current and next package names for external-package pauses
- the triggering label for external-package pauses

`MainActivity` uses those fields to render the pause summary and the decision
dialog.

## Available actions

### Time and failed-edge checkpoints

- `Continue`
- `Stop and save`

If the user continues, only the checkpoint budget that actually fired is rolled
forward. A time warning does not also defer the failed-edge warning, and vice
versa.

### External-package boundary

- `Continue outside package`
- `Skip edge`

If the user skips the edge, the coordinator records
`SKIPPED_EXTERNAL_PACKAGE` and keeps crawling without enqueuing the child
screen.

If the user continues, the child screen is captured under its actual package
name and later replay uses the recorded package metadata.

External-package prompts intentionally offer only continue-or-skip choices. The
general `Stop and save` action remains available for elapsed-time and
failed-edge checkpoints.

## Replay and restore behavior

Entry restoration still starts from the originally selected target app, but
once replay begins, each route step can request its expected package and each
screen record carries its captured package name.

That package-aware metadata is what makes cross-package continuation and later
restoration work consistently.
