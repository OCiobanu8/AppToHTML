# Crawl Logger Plan

## Summary

Add an always-on, per-crawl `crawl.log` file in the existing crawl session directory next to `crawl-index.json`. The log should make it possible to reconstruct the crawler's decisions, queue evolution, screen identity choices, replay attempts, and unexpected crashes after the fact.

This plan already includes the extra logging needs surfaced by the `length=1; index=1` bug investigation, so no additional product information is needed before implementing the logger itself.

## Core Logging Goals

- Record the current screen being crawled.
- Record the frontier/queue being formed and consumed.
- Record all recoverable and unrecoverable errors.
- Preserve enough context to debug screen identity mistakes, replay drift, and path-resolution crashes.

## File And Ownership

- Create `crawl.log` in each `CrawlSessionDirectory`.
- Keep `crawl.log` scoped to one crawl run only.
- Keep the existing app-wide `DiagnosticLogger` for global diagnostics and uncaught crashes.
- Mirror unexpected crawler failures into both:
  - the per-run `crawl.log`
  - the global diagnostics log

## Information The Logger Must Capture

### 1. Session lifecycle

- crawl start time
- session id
- target package name
- selected app name if available
- initial event class name
- manifest status transitions:
  - `in_progress`
  - `completed`
  - `partial_abort`
  - `failed`

### 2. Root and screen capture identity

For every screen capture:

- screen id
- parent screen id if any
- depth
- route summary
- chosen screen name
- screen fingerprint
- saved artifact paths
- scroll step count

Also log naming inputs that explain why the screen got that name:

- event class name
- top visible text/title candidates used by naming
- resource-id fallback if naming falls back

This is important because the current bug suggests a screen may have been misnamed as `"Navigate up"`.

### 3. Queue/frontier evolution

Log all queue mutations:

- initial frontier contents after root capture
- each dequeue event
- each enqueue event
- frontier size after each mutation
- frontier contents snapshot after each mutation

### 4. Traversal planning

For each expanded screen:

- count of eligible elements
- count of skipped elements
- skipped blacklist reasons
- deterministic traversal order

For each target element:

- label
- resource id
- class name
- bounds
- `childIndexPath`
- `firstSeenStep`
- edge status outcome

### 5. Route replay and recovery

For each replay attempt:

- destination screen id/name being restored
- expected fingerprint
- actual fingerprint after replay
- route step currently being replayed
- restore-to-entry vs relaunch decision
- recovery success/failure

For each failed branch:

- whether recovery succeeded
- whether the crawl continued
- whether the crawl escalated to partial abort

### 6. Screen dedup and linking

For each `linked_existing` decision:

- source parent screen id
- candidate child screen name/fingerprint
- matched existing screen id/name/fingerprint
- reason the crawler considered it the same screen

This is required because the current bug strongly suggests a false dedup caused by weak screen naming/fingerprint identity.

### 7. Live action execution

For click and scroll attempts, log:

- requested action type
- intended `childIndexPath`
- how many nodes were actually resolved from that path
- the actual resolved path depth
- candidate source:
  - path candidate
  - fallback candidate
- candidate node description
- action id tried
- success/failure per attempt

This is required because the current crash message suggests an index/path mismatch while replaying on the live tree.

### 8. Unexpected exceptions

For every unexpected crawler exception, log:

- full throwable class
- full message
- full stack trace
- last known screen id/name
- last known edge/element being processed
- last known frontier snapshot
- last known replay/recovery stage

The current top-level failure only exposes `error.message`, which is not enough.

## Internal Interface Changes

- Extend `CrawlSessionDirectory` with `logFile: File`.
- Add a crawler-owned log writer that appends timestamped lines to `crawl.log`.
- Pass the logger into `DeepCrawlCoordinator` so coordinator events can be logged directly.
- Make it easy for service-side action helpers to write into the active crawl log.

## Suggested Implementation Shape

### Logger API

Keep it plain text and simple. A small internal API is enough:

- `info(message: String)`
- `warn(message: String)`
- `error(message: String, throwable: Throwable? = null)`
- optional helpers for common crawler events such as:
  - `logScreenCapture(...)`
  - `logFrontierState(...)`
  - `logEdgeAttempt(...)`
  - `logReplayResult(...)`

### Context handling

Use lightweight, explicit context in log messages instead of implicit global state. Each important line should include enough identifiers to stand on its own:

- session id
- screen id
- edge label or route step label
- fingerprint when relevant

## Test Plan

- Verify a session creates `crawl.log` next to `crawl-index.json`.
- Verify the root capture writes session start and initial frontier entries.
- Verify queue enqueue/dequeue entries are written during BFS traversal.
- Verify linked-existing decisions write both the candidate and matched screen identity.
- Verify click/scroll logging includes intended path and resolved path depth.
- Verify an injected unexpected exception writes the full throwable details into `crawl.log`.
- Verify completion, partial abort, and failure each write a terminal lifecycle entry.

## Assumptions

- Logging is always enabled for crawl runs.
- The log is file-only for now and is not surfaced in the UI.
- `crawl.log` is human-readable plain text, not JSON.
- The logger is intended first for debugging correctness and crash analysis, not telemetry aggregation.
