# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Inherited context (SPEAR factory tiers)

Every agent run inherits the four factory tiers, broad → narrow. Imported **directly** (not
chained) so each loads exactly once:

@factory/COMPANY.md
@factory/FUNCTION.md
@factory/PRODUCT.md
@factory/SURFACE.md

## Development Philosophy

This service is not deployed in production. There are no backward compatibility requirements — make the best long-term architectural decisions without being constrained by existing interfaces or data formats.

## What This Project Does

AppToHTML is an Android app that uses AccessibilityService to capture a target app's UI as merged HTML and XML. It scrolls through screens, deduplicates merged elements, and optionally follows clickable elements one level deep ("deep crawl") to capture child screens.

## Build & Test Commands

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.apptohtml.CrawlerSessionTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean
./gradlew clean
```

## Architecture

The project is organized into 9 modules described in `documentation/modules.md`. The key data flow:

1. **User selects a target app** → `SelectedAppRepository` (DataStore Preferences) persists the choice
2. **Capture starts** → `AppLaunchHelper` launches the target app; `AppToHtmlAccessibilityService` waits for the window
3. **Scroll scan** → `ScrollScanCoordinator` scrolls down, calling `AccessibilityTreeSnapshotter` at each scroll stop to snapshot `AccessibilityNodeInfo` into serializable `AccessibilityNodeSnapshot` trees. Viewports are merged by deduplicating on label + resource ID + class name + list-item flag
4. **Deep crawl (optional)** → `DeepCrawlCoordinator` passes candidate `PressableElement`s to `TraversalPlanner`, which filters via `CrawlBlacklist`. `PathReplayResolver` replays the path to each screen. `CrawlRunTracker` tracks state
5. **Export** → `HtmlRenderer` + `AccessibilityXmlSerializer` write to per-session directories managed by `CaptureFileStore`. `CrawlManifestStore` writes the crawl index JSON
6. **Session state** → `CrawlerSession` holds in-memory `StateFlow` with states: `IDLE → LAUNCHING → WAITING → SCANNING → TRAVERSING → CAPTURED/ABORTED/FAILED`

### Core Domain Models

- `AccessibilityNodeSnapshot` — serializable tree node (replaces live `AccessibilityNodeInfo`)
- `ScreenSnapshot` — full merged screen with its list of `PressableElement`s
- `PressableElement` — detected clickable element with path for replay

### Design Constraints

- **Crawl sessions are intentionally not persisted** — only `SelectedAppRef` (the chosen target app) is stored
- Unit tests use synthetic `AccessibilityNodeSnapshot` trees; no mocking of Android framework classes
- `CrawlBlacklist` guards against navigating dangerous system elements (back buttons, system UI)
- Capture timeout: 15 s; scroll debounce: 350 ms

## Key Source Locations

| Concern | Location |
|---|---|
| UI (Compose) | `MainActivity.kt` |
| A11y service entry point | `AppToHtmlAccessibilityService.kt` |
| Session state machine | `CrawlerSession.kt` |
| Scroll + merge logic | `ScrollScanCoordinator.kt` |
| Deep crawl orchestration | `DeepCrawlCoordinator.kt` |
| Element safety filtering | `CrawlBlacklist.kt`, `TraversalPlanner.kt` |
| HTML/XML export | `HtmlRenderer.kt`, `AccessibilityXmlSerializer.kt` |
| Screen name heuristics | `ScreenNaming.kt` |
| Diagnostics | `DiagnosticLogger.kt` |

All source lives under `app/src/main/java/com/example/apptohtml/`.

## Documentation

`documentation/` contains detailed module breakdowns, crawler flow diagrams, and investigation notes:
- `modules.md` — module responsibilities and boundaries
- `crawler-module.md` — merge strategy, scroll flow, known tradeoffs
- `data-and-state.md` — what is persisted vs. runtime-only
- `.drawio` files — editable architecture diagrams


<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:6cd5cc61 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Agent Context Profiles

The managed Beads block is task-tracking guidance, not permission to override repository, user, or orchestrator instructions.

- **Conservative (default)**: Use `bd` for task tracking. Do not run git commits, git pushes, or Dolt remote sync unless explicitly asked. At handoff, report changed files, validation, and suggested next commands.
- **Minimal**: Keep tool instruction files as pointers to `bd prime`; use the same conservative git policy unless active instructions say otherwise.
- **Team-maintainer**: Only when the repository explicitly opts in, agents may close beads, run quality gates, commit, and push as part of session close. A current "do not commit" or "do not push" instruction still wins.

## Session Completion

This protocol applies when ending a Beads implementation workflow. It is subordinate to explicit user, repository, and orchestrator instructions.

1. **File issues for remaining work** - Create beads for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **Handle git/sync by active profile**:
   ```bash
   # Conservative/minimal/default: report status and proposed commands; wait for approval.
   git status

   # Team-maintainer opt-in only, unless current instructions forbid it:
   git pull --rebase
   git push
   git status
   ```
5. **Hand off** - Summarize changes, validation, issue status, and any blocked sync/commit/push step

**Critical rules:**
- Explicit user or orchestrator instructions override this Beads block.
- Do not commit or push without clear authority from the active profile or the current user request.
- If a required sync or push is blocked, stop and report the exact command and error.
<!-- END BEADS INTEGRATION -->
