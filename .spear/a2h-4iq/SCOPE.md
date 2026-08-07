# SCOPE

Bead: `a2h-4iq` — Single-screen capture via ADB broadcast (P1, feature)

## Goal

An operator can drive the emulator to any screen, run one host-side command, and get that
screen's merged HTML + XML — structurally identical to what a crawl produces for a root screen —
pulled to a local folder in seconds, **without** the target app being launched, back-navigated,
or losing foreground, and without a rebuild.

## Audience

The AppToHTML operator/developer working against a local emulator or attached device — the person
driving a target app by hand and needing its current screen as structured data. Secondary
audience: downstream consumers of capture artifacts (`ScreenXmlReader` and anything reading crawl
HTML/XML), who must see no difference between a snapshot and a crawl root screen. Not an
end-user-facing surface.

## Why now

Today the only way to capture a screen is a full crawl, and the crawl entry path always
(a) launches the target app, (b) back-navigates to the entry screen, and (c) foregrounds
AppToHTML on completion. Each of those destroys the screen the operator navigated to, so any
screen reachable only by manual interaction (a logged-in state, a dialog, a deep flow) is
currently uncapturable. The alternative — an instrumented test per screen — costs a rebuild
every iteration.

Secondary payoff: the extraction in Phase 1 converts ~180 lines of live scroll/click logic that
is currently private to the accessibility service and **has zero unit coverage** into tested code
shared by both the crawl and snapshot paths.

## Affected surface

- **New files (main):**
  - `app/src/main/java/com/example/apptohtml/crawler/LiveNodeActions.kt`
  - `app/src/main/java/com/example/apptohtml/crawler/SnapshotFileStore.kt`
  - `app/src/main/java/com/example/apptohtml/crawler/SnapshotCaptureCoordinator.kt`
- **New files (test):** `LiveNodeActionsTest.kt`, `SnapshotFileStoreTest.kt`,
  `SnapshotCaptureCoordinatorTest.kt`
- **Modified:** `AppToHtmlAccessibilityService.kt` (delegate to `LiveNodeActions`; register the
  broadcast receiver), `CrawlerSession.kt` (add a **separate** `StateFlow<SnapshotUiState>`),
  `MainActivity.kt` (surface last-snapshot result)
- **New tooling:** `.claude/skills/capture-screen/SKILL.md` +
  `.claude/skills/capture-screen/scripts/capture-screen.ps1`
- **Docs:** `documentation/modules.md`, `documentation/data-and-state.md`
- **Migrations:** none. **Breaking changes:** none — this is additive.
- **Explicitly NOT touched:** `app/build.gradle.kts`, `AndroidManifest.xml`,
  `DeepCrawlCoordinator.kt`, `CrawlRunTracker.kt`, `TraversalPlanner.kt`, `CrawlBlacklist.kt`,
  `AppLaunchHelper.kt`, `CrawlerPhase`, the `crawl/` directory layout, the `pull-crawl` skill.

## Inputs

- [x] Ticket: `bd show a2h-4iq`
- [x] Design doc: [`thoughts/shared/plans/2026-08-07-single-screen-capture-broadcast.md`](../../thoughts/shared/plans/2026-08-07-single-screen-capture-broadcast.md)
      — six phases, per-phase criteria, open questions. This SCOPE governs where the two differ.

**The design doc's six phases are advisory, not binding.** PLAN may re-cut, reorder, or add
phases where that produces a safer or more testable sequence; the *outcomes* in "Done means" are
what is fixed. This whole feature stays one bead and one cycle — no splitting.

## Constraints

- **PR size:** ≤2000 LOC diff (tests + the extracted ~180 lines dominate; the net *new* logic is
  far smaller). Flag at Assess if exceeded rather than splitting silently.
- **Tests required:** yes — unit tier only (`app/src/test/`), synthetic node models, **no mocking
  of Android framework classes** (per `factory/FUNCTION.md`).
- **Type-check level:** strict — `./gradlew assembleDebug` must compile with no new warnings.
- **Phase 1 is a pure refactor.** No behavior change, therefore no red→green pin is
  constructible; the guard is the pre-existing suite staying green plus new characterization
  tests. Every *other* behavior-bearing criterion below is a new capability, pinned by a test
  that fails on `main` (the class does not exist there) — that is the red half.
- **Safety (non-negotiable, from `factory/COMPANY.md`):** the snapshot path must never launch an
  app, never back-navigate, never foreground AppToHTML, and never write inside `crawl/`.
- Deliberate, already-settled decisions carried in from the design doc — **not** open for
  re-litigation during this cycle:
  - The receiver is registered in **every** build variant (no `BuildConfig.DEBUG` gate). Gating
    it before production is already tracked as `a2h-oo0` and is **out of scope here**.
  - The scan may leave the screen wherever it lands; it always ends rewound to top.
  - Repeated snapshots of one screen always create a new directory; no dedup.

## Done means

### Machine-checked (the Assess loop grades these)

- [ ] `./gradlew test` green — including every pre-existing test, unmodified.
- [ ] `./gradlew assembleDebug` green.
- [ ] `LiveNodeActionsTest` covers scrollable-candidate ordering, `preferredActionIds` fallback
      order, and live label resolution against synthetic nodes.
- [ ] A test asserts `.done` is the **last** file written, and that a failed capture writes
      `.failed` containing the reason instead.
- [ ] A test asserts snapshot XML parses with `ScreenXmlReader` and carries a `<crawl>` block with
      `depth="0"`, `isRoot=true`, and zero resolved edges.
- [ ] A test asserts a snapshot write leaves a pre-existing sibling `crawl/` directory
      byte-identical (same file set, same contents).
- [ ] A test asserts capture is rejected with `crawl_in_progress` when `CrawlerSession`'s phase is
      not `IDLE / CAPTURED / ABORTED / FAILED`.
- [ ] A test asserts capture is rejected when the foreground package is `com.example.apptohtml` or
      `com.android.systemui`.
- [ ] A test asserts `scroll = false` produces a single-step snapshot and issues **zero** scroll
      actions; `scroll = true` ends with a rewind-to-top.
- [ ] A test asserts **no** launch and **no** entry-restore call occurs on any path — success,
      rejection, or failure — via fakes that fail the test if invoked.
- [ ] A test asserts a snapshot never leaves `CrawlerPhase` in a non-terminal state (snapshot
      state is a separate flow).
- [ ] `capture-screen.ps1` parses clean: `Get-Command -Syntax` / PSScriptAnalyzer-equivalent
      parse check exits 0.
- [ ] No leftover debug output, no commented-out code, no hard-coded absolute paths.
- [ ] `documentation/modules.md` and `documentation/data-and-state.md` describe the snapshot path
      and the `snapshots/` directory.

### Human-verified on a real device (Gate 3 — evidence under `.spear/a2h-4iq/evidence/`)

These cannot be executable checks: they require a live emulator and observation of focus.

- [ ] One broadcast captures the foreground screen; **the target app visibly stays foreground**
      and AppToHTML never appears. (screenshot / screen recording)
- [ ] With the accessibility service disabled, the script reports that specifically in ~2 s
      instead of timing out. (transcript)
- [ ] Three captures — a scrollable screen, a non-scrollable dialog, and a screen reachable only
      by manual navigation — all yield valid HTML + XML. (pulled artifacts)
- [ ] A snapshot's XML diffed against the same screen captured as a crawl root: structure matches;
      only crawl-edge content differs. (diff output)
- [ ] Two captures in a row land in distinct local directories, no collision. (directory listing)
- [ ] A full crawl still behaves identically post-refactor — same screen count. (crawl log)

`MAX_ROUNDS = 12`

Rounds are Assess/fix iterations, not implementation phases — the cap is a circuit breaker, not a
budget to spend. 12 gives a large surface (three new classes, service wiring, a PowerShell tool,
docs) room for the grader to attack a fresh dimension each round without the loop grinding
indefinitely on a defect it cannot clear. If the same defect survives two consecutive rounds,
that is a signal to stop and escalate regardless of rounds remaining.
