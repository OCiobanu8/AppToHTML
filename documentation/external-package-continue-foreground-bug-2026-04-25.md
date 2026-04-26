# External Package Continue Foreground Bug

Date: 2026-04-25
Context: PR #1 review of `codex/deep-crawl-graph`

## Summary

External-package boundary pauses can resume from the wrong foreground app when the user chooses `Continue outside package`.

The coordinator captures the child root immediately after the click leaves the current package, then pauses for a user decision. `CrawlerSession.pauseForDecision()` returns AppToHTML to the foreground before waiting for the button tap. If the user continues, `DeepCrawlCoordinator` resumes with the earlier `childInitialRoot` and calls `scanCurrentScreen`, but the real accessibility window is now AppToHTML rather than the external package screen that `childInitialRoot` describes.

## Affected Path

- `DeepCrawlCoordinator.expandScreen`
  - detects `childPackageName != currentPackageName`
  - calls `host.awaitPauseDecision(...)`
  - handles `PauseDecision.CONTINUE`
  - immediately calls `scanCurrentScreen(...)` with the stale `childInitialRoot`
- `CrawlerSession.pauseForDecision`
  - sets `PAUSED_FOR_DECISION`
  - calls `returnToApp()`
  - waits for the decision

## Why This Is Risky

The real scanner does not only read the supplied root snapshot. It also calls back into the host to:

- capture settled viewports
- scroll forward and backward
- inspect the current active window while building merged screen artifacts

After the pause dialog is shown, those host callbacks operate against AppToHTML unless the coordinator restores the destination app first. That can make a continued external-package capture incomplete, stale, or internally inconsistent. Unit tests can miss this because coordinator tests use `scanScreenOverride`, which returns snapshots from the supplied root without exercising live foreground callbacks.

## Expected Behavior

After a user chooses `Continue outside package`, the coordinator should ensure the external destination is foregrounded and matches the expected child route state before scanning or expanding it.

## Possible Fix Directions

- Restore/replay to the external child destination after the decision and before `scanCurrentScreen`.
- Change the pause flow so a continue decision can return the target surface to the foreground before the coordinator resumes.
- Split external-package continuation into an explicit route checkpoint that records enough package-aware replay metadata to reconstruct the destination after AppToHTML has been foregrounded.

## Verification Ideas

- Add a test that exercises the real scan callback path after an external-package continue, with host captures failing if the active foreground package remains AppToHTML.
- Add a device-level manual run where an external package screen is scrollable, choose `Continue outside package`, and verify merged scroll artifacts are from the external package rather than AppToHTML.
