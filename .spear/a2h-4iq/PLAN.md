# PLAN

Slug `a2h-4iq` · governed by [SCOPE.md](SCOPE.md) · design reference:
[`thoughts/shared/plans/2026-08-07-single-screen-capture-broadcast.md`](../../thoughts/shared/plans/2026-08-07-single-screen-capture-broadcast.md)

## Corrections to the design doc found while reading the code

The design doc was written before this pass over the source. Four of its claims are wrong or
underspecified; the plan below uses the corrected versions.

1. **`ScreenCrawlState.screenIdentity` is the wrong type in the doc's §5 snippet.** The field is
   `ScreenIdentityFields` ([CrawlerModels.kt:538](../../app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:538)),
   but `screenIdentityFor(...)` returns `ScreenIdentity` (fingerprint + confidence + hints,
   [ScreenNaming.kt:620](../../app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt:620)) — different
   shapes, and `screenIdentityFor` is `private` to `DeepCrawlCoordinator`
   ([DeepCrawlCoordinator.kt:2196](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:2196)) so it is
   not callable anyway. The crawl path actually reaches `ScreenIdentityFields` via
   `ScreenIdentityCodec.decode(screenRecord.screenFingerprint)` with a hand-built fallback
   ([DeepCrawlCoordinator.kt:1791-1796](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1791)).
   The snapshot path must reproduce **that exact two-step**, or the `<crawl>` block differs from a
   crawl root's. This is the single highest-risk detail in the feature and gets its own test.

2. **`runLevel.sessionId` is not free-form.** The crawl root's value comes from
   `CrawlSessionDirectory.sessionId`, formatted `crawl_yyyyMMdd_HHmmss`
   ([CaptureFileStore.kt:37-38](../../app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:37)). A snapshot
   must **not** claim a `crawl_` id — that would make a snapshot indistinguishable from a crawl to
   any log scraper. Use `snapshot_yyyyMMdd_HHmmss`. Structure identical, provenance honest.

3. **The doc's screenId `"snapshot"` breaks the file-name convention.** `saveScreen` builds
   `"${screenId}_${ScreenNaming.toFileBase(name)}"`
   ([CaptureFileStore.kt:69](../../app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:69)) and crawl ids are
   `screen_NNN` ([CrawlRunTracker.kt:257](../../app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:257)).
   `ScreenXmlReader` is the consumer to satisfy; the plan uses `screen_001` so a snapshot's file
   base matches a crawl root's exactly, and records provenance in `snapshot.json` instead.

4. **`CaptureFileStore.save` is already dead and already broken.** It is referenced from no
   main-source call site (only `preparePackageDirectory` is exercised, from a test), and
   `preparePackageDirectory` throws on any *directory* entry because `File.delete()` returns false
   for a non-empty dir ([CaptureFileStore.kt:162-173](../../app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:162)) —
   so it would already throw today on any package that has a `crawl/` dir. The doc says "don't
   reuse it"; the plan additionally **files a follow-up bead** rather than deleting it in this
   cycle (out of scope, and deleting dead code mid-feature muddies the diff).

## Step 0 — Baseline

Record the pre-change state so every later "green" is comparable and the Phase-1 refactor has a
real before/after.

- `./gradlew test` and `./gradlew assembleDebug` → archive both under
  `.spear/a2h-4iq/evidence/00-baseline/`.
- Record the pre-change test count. Phase 1 must not reduce it.

## Step 1 — Extract `LiveNodeActions` (pure refactor)

Move from [AppToHtmlAccessibilityService.kt](../../app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt):
`performScroll` `:454`, `performClick` `:492`, `attemptActionOnCandidates` `:592`,
`preferredActionIds` `:616`, `collectScrollableCandidates` `:645`,
`collectClickFallbackCandidates` `:663`, `resolveLiveElementLabel` `:715`,
`scrollableCandidateScore` `:727`, `resolveLiveLabel` `:741`, `findNestedTitleLabel` `:756`,
`findNestedTextLabel` `:770`, `describeNode` `:782`, `actionName` `:795`, `quoteForLog` `:808`,
`resolvePathNodes` `:560`, `logPathDivergenceIfNeeded` `:572`, `formatEligibilityReasons` `:541`,
`clickFallbackTargetFor` `:554`, and the two private carrier types `LiveCandidate` `:876` /
`LiveClickCandidate` `:881`.

Generic over the node type, following `PathReplayResolver`'s lambda-injection shape so tests can
drive it with synthetic nodes and **no Android framework mocking**:

```kotlin
internal class LiveNodeActions<N>(
    private val childCount: (N) -> Int,
    private val childAt: (N, Int) -> N?,
    private val attributes: (N) -> LiveNodeAttributes,
    private val supportedActionIds: (N) -> Set<Int>,
    private val performAction: (N, Int) -> Boolean,
    private val logger: () -> CrawlLogger?,
)
```

`LiveNodeAttributes` carries what the moved code reads off a node: `className`,
`viewIdResourceName`, `text`, `contentDescription`, `boundsShortString`, `isVisibleToUser`,
`isEnabled`, `isScrollable`, `isClickable`, `isCheckable`, `isEditable`.

The service keeps one `LiveNodeActions<AccessibilityNodeInfo>` wired to the framework accessors
and delegates. **Behavior must not change.**

> **Refactor-fidelity guard.** Grading a "pure refactor" on "tests still pass" is weak when the
> moved code had *zero* coverage to begin with — the existing suite cannot detect a regression in
> code it never touched. So the new characterization tests are written **first, against the
> current service behavior**, by reading the source; if a characterization test disagrees with the
> post-move implementation, the *implementation* is wrong, not the test. Two details are known
> tripwires and get explicit assertions:
> - `preferredActionIds` returns supported-preferred first, then **unsupported** preferred ids
>   appended (not filtered out) — `:641-642`. Easy to "clean up" into a behavior change.
> - `attemptActionOnCandidates` is called with `pathNodes.asReversed()` (deepest-first) for path
>   candidates but **plain order** for fallback candidates — `:467` vs `:477`. Easy to normalize
>   by accident.

`LiveNodeActionsTest`: scroll-candidate ordering (score by class + depth, `:727-739`),
`preferredActionIds` fallback order incl. the unsupported-append rule, label resolution chain
(text → contentDescription → nested `title` → nested text → `ScreenNaming.chooseElementLabel`),
and the two ordering tripwires above.

**Gate:** `./gradlew test` green with **≥** the baseline test count; `assembleDebug` green.

## Step 2 — `SnapshotFileStore`

New `SnapshotFileStore.kt`. Writes to `html/<pkg>/snapshots/<ts>_<token>_<label>/`, a **sibling**
of `crawl/`. Reuses `HtmlRenderer.render` and `AccessibilityXmlSerializer.serialize` directly;
never calls `CaptureFileStore.createSession` (wipe semantics) or `.save` (see correction 4).

Files, in this order, `.done` **strictly last**: `screen_001_<base>.html`,
`screen_001_<base>.xml`, `screen_001_<base>_merged_accessibility.xml`, `snapshot.json`,
`capture.log`, then `.done`. On failure: `.failed` containing the reason, and no `.done`.

Takes a `File` root injected by the caller, so tests use a JUnit `TemporaryFolder` and never need
a `Context`.

`SnapshotFileStoreTest` — including the `.done`-is-last assertion implemented by **recording write
order**, not by comparing mtimes (filesystem timestamp granularity on Windows makes an mtime
comparison flaky and it would pass vacuously).

## Step 3 — Synthetic `ScreenCrawlState` + XML fidelity

Build the state per correction 1: `ScreenIdentityCodec.decode(ScreenNaming.buildScreenIdentity(
screenName, packageName, root).fingerprint)` with the same `ScreenIdentityFields(...)` fallback
the crawl path uses; `screenId = "screen_001"`, `depth = 0`, `isRoot = true`, `parent = null`,
`route = CrawlRoute()`, `expansionStatus = NOT_STARTED`, `edgesByElement = emptyMap()`,
`runLevel = RunLevelState(sessionId = "snapshot_<ts>", …, status = COMPLETED, maxDepthReached = 0)`.

**The fidelity test is a structural diff, not a spot-check.** Serialize the *same* synthetic
`ScreenSnapshot` twice — once through a crawl-shaped `ScreenCrawlState` built the way
`DeepCrawlCoordinator.buildScreenCrawlState` builds a root, once through the snapshot's — then
assert the element-tree and `<crawl>` structure are identical modulo the deliberately-differing
fields (session id, edge content). A test that only asserts `depth="0"` is present would pass on
XML that had lost half its attributes.

## Step 4 — `SnapshotCaptureCoordinator`

Mirrors `DeepCrawlCoordinator`'s host-interface shape so it is testable with fakes exactly like
`DeepCrawlCoordinatorTest`. **The host interface deliberately has no `relaunchTargetApp` and no
`performGlobalBack` member** — the safety guarantee is then structural, not behavioral: there is
no method to call, so no code path can launch or back-navigate, and the guarantee survives future
edits by anyone. The corresponding test asserts the *absence* at the type level plus a fake whose
every unexpected member fails the test.

`CrawlerSession` is a singleton `object`; the coordinator takes the phase as
`currentPhase: () -> CrawlerPhase` rather than reading it, so tests need no singleton mutation.

Sequence: reject if phase ∉ {IDLE, CAPTURED, ABORTED, FAILED} → reject self/SystemUI foreground →
synthesize `SelectedAppRef(packageName = fg, appName = <label>, launcherActivity = "", …)` →
`scan` under `withTimeout` (or single `captureRootSnapshot` when `scroll = false`) → rewind to top
→ write → publish terminal state, **no `returnToApp()`**.

## Step 5 — Receiver + service wiring

Runtime-register in `onServiceConnected`, unregister in `onDestroy`, every build variant, via
`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`. Extras: `token`, `name`, `scroll`. The
receiver validates and hands off to `serviceScope`; no `goAsync()`. Add a **separate**
`StateFlow<SnapshotUiState>` on `CrawlerSession` — `CrawlerPhase` is not touched. Toast +
one `DiagnosticLogger` line per capture.

Intent-extra parsing is factored into a pure function so malformed/absent extras are unit-tested
without a device (`scroll` absent → `true`; `--ez` false → `false`; blank token → generated).

## Step 6 — `capture-screen` skill + script

`.claude/skills/capture-screen/SKILL.md` + `scripts/capture-screen.ps1`, following `pull-crawl`'s
conventions (adb resolution chain, `-DeviceSerial`, `-DestinationRoot`, staged temp pull, 260-char
path pre-check). Pre-checks `enabled_accessibility_services` before broadcasting so a disabled
service is a ~2 s diagnosis, not a 45 s timeout. Polls `snapshots/*_<token>_*/` for `.done` /
`.failed`. Parameters per SCOPE.

**Automated check:** parse-only validation (`[ScriptBlock]::Create((Get-Content -Raw …))`) exits 0
in the loop. Actual device behavior is a Gate-3 item — the script cannot be meaningfully executed
without an emulator.

## Step 7 — Docs + final re-verify

Update `documentation/modules.md` and `documentation/data-and-state.md` with the snapshot path and
the `snapshots/` directory. Then **re-run `./gradlew test` and `assembleDebug` at the final
artifact state** and archive to `.spear/a2h-4iq/evidence/`. Green evidence from step N is void
once step N+1 edits anything — convergence is declared only on evidence from the final state.

## Risks this plan accepts

- **Phase 1 moves ~180 previously-untested lines.** Mitigated by characterization-tests-first, not
  by the pre-existing suite (which never covered them). This is the likeliest place for a silent
  regression, and the full-crawl device check at Gate 3 is the backstop.
- **`scan`'s scroll lambdas capture `rootInActiveWindow` per call.** The snapshot path must
  re-read it per lambda invocation exactly as the service does today (`:344`, `:348`) — caching a
  root across a scroll yields stale nodes.
- **Receiver is unauthenticated in every variant.** Accepted per SCOPE; tracked in `a2h-oo0`.

## Approval

`[x] User confirmed`
