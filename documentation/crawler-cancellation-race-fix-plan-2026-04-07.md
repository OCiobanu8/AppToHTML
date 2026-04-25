# Capture Cancellation Race Fix Plan 2026-04-07

## Summary

Stop using coroutine cancellation as the primary debounce/reschedule mechanism for first-screen capture.

The current race is:

1. a qualifying accessibility event starts a delayed capture job
2. another qualifying event arrives while the session still reports `WAITING_FOR_TARGET_SCREEN`
3. the service cancels the in-flight job with `captureJob?.cancel()`
4. that job may already have entered crawl setup and later throws `JobCancellationException` at the next suspension point

The fix is to make waiting-event rescheduling token-based instead of cancellation-based, claim the scan atomically before crawl starts, and treat any remaining coroutine cancellations as expected control flow.

## Implementation Changes

### Part 1: Replace Cancellation-Based Debounce In The Accessibility Service

Primary file:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`

Changes:

- replace `captureJob?.cancel()` rescheduling on every waiting-phase event with a latest-generation debounce
- add a monotonic `captureGeneration` field on the service
- on each qualifying event while the session is still waiting:
  - increment the generation
  - launch a delayed attempt tagged with that generation
- do not cancel earlier waiting attempts on new events
- older attempts must self-abort if their generation is no longer current
- keep explicit `captureJob?.cancel()` only for service shutdown and destruction, not for ordinary content-change rescheduling

Expected outcome:

- repeated `TYPE_WINDOW_CONTENT_CHANGED` events no longer cancel an in-flight capture attempt
- stale delayed attempts become no-ops instead of throwing cancellation into active crawl setup

### Part 2: Atomically Claim The Scan Before Crawl Starts

Primary files:

- `app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt`

Changes:

- extend `attemptCapture(...)` to receive the scheduled generation
- after the debounce delay, exit early unless the generation is still current
- re-check request id and current session state before doing any crawl work
- validate `rootInActiveWindow` and `root.packageName` before changing state; if the target app is not foregrounded yet, return and wait for later events
- replace `CrawlerSession.beginScanning(...)` with `CrawlerSession.claimScanning(...): Boolean`
- `claimScanning(...)` must:
  - transition only from `WAITING_FOR_TARGET_SCREEN` to `SCANNING_TARGET_SCREEN`
  - return `false` if the request id changed
  - return `false` if another attempt already claimed the scan
- call `claimScanning(...)` immediately before constructing and running `DeepCrawlCoordinator`
- if claim fails, exit silently

Expected outcome:

- only one attempt can transition a request into active scanning
- once scanning has been claimed, later accessibility events no longer restart or interrupt the crawl

### Part 3: Treat Cancellation As Expected Control Flow In The Coordinator

Primary file:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt`

Changes:

- add an explicit `catch (cancellation: CancellationException)` before the broad `catch (error: Throwable)`
- rethrow immediately without:
  - saving a failed manifest
  - logging `Unexpected crawler failure`
- keep the existing failure-manifest and diagnostic error path for genuine unexpected exceptions only
- if extra observability is needed, log cancellation as `info` with session id and last replay stage, but never as an error

Expected outcome:

- expected coroutine cancellation no longer appears as a crawler failure
- real crawler faults still produce failure manifests and hard-error diagnostics

## Important Interface Changes

- `CrawlerSession.beginScanning(requestId, message)` becomes `CrawlerSession.claimScanning(requestId, message): Boolean`
- `AppToHtmlAccessibilityService.attemptCapture(...)` gains a generation/token argument
- add a service-local `captureGeneration` field; it stays internal and does not affect persisted state or UI schema

## Test Plan

### Unit tests

- add a `CrawlerSession` test for `claimScanning(...)`
- verify the first caller transitions `WAITING_FOR_TARGET_SCREEN -> SCANNING_TARGET_SCREEN`
- verify a second caller for the same request returns `false`
- verify a mismatched request id returns `false`

### Service scheduling tests

- add a focused test around extracted scheduling logic or a small internal helper
- verify that when two waiting-phase events arrive back-to-back, only the latest generation is allowed to start scanning
- verify the older scheduled attempt exits as stale instead of being canceled mid-crawl

### Coordinator regression test

- add a `DeepCrawlCoordinator` test where the host throws or propagates cancellation from a suspend point
- verify cancellation propagates without taking the failed-manifest or unexpected-error path

### Manual verification

- reproduce the Settings flow that currently emits repeated content-change events
- confirm diagnostics no longer show `Unexpected crawler failure ... JobCancellationException`
- confirm real crawler exceptions still log as failures and still surface through `failRequest(...)`

## Assumptions

- the root issue is the app’s internal waiting-phase reschedule logic, not a user-triggered cancel action
- it is acceptable for older delayed attempts to become no-ops instead of being force-canceled
- once a scan attempt has claimed `SCANNING_TARGET_SCREEN`, newer accessibility events should no longer reschedule or interrupt that crawl
