# Single-screen capture via ADB broadcast

Closes `a2h-4iq`.

## Summary

Drive the emulator to any screen, fire one host-side command, get that screen's merged HTML and
XML pulled locally in seconds — with no rebuild, and **without** the target app being launched,
back-navigated, or losing foreground.

Until now the only way to capture a screen was a full crawl, and the crawl entry path always
launches the target app, back-navigates to its entry screen, and foregrounds AppToHTML when it
finishes. Each of those destroys the screen the operator navigated to, so any state reached by
hand — a logged-in view, a dialog, a deep flow — was uncapturable. The alternative was an
instrumented test per screen, costing a rebuild every iteration.

Secondary payoff: extracting `LiveNodeActions` converted ~180 lines of live scroll/click logic
that were private to the accessibility service and had **zero** unit coverage into tested code now
shared by both the crawl and snapshot paths.

## How it works

`am broadcast -a com.example.apptohtml.CAPTURE_SCREEN --es token <id>` reaches a receiver
registered at runtime in the accessibility service. `SnapshotCaptureCoordinator` rejects the
capture if a crawl is active or if our own UI / SystemUI is in front, resolves the foreground
package, runs the existing `ScrollScanCoordinator` scan, rewinds to top, and writes artifacts via
`SnapshotFileStore` into `snapshots/` — a **sibling** of `crawl/`, so a fresh crawl's recursive
wipe can never destroy a snapshot.

Completion is signalled by a `.done` marker written strictly last, not by an ordered-broadcast
result: those are capped around 10 s and a long scrollable screen exceeds that.

## Design decisions worth reviewing

- **The no-launch / no-back-navigation guarantee is structural, not tested-in.**
  `SnapshotCaptureCoordinator.Host` exposes no method that could launch an app, press global back,
  or foreground this app. There is nothing to call, so no future edit inside the coordinator can
  violate it. A test pins the exact member set, so *adding* a capability fails the build.
- **Snapshot state is a separate `StateFlow`, not a new `CrawlerPhase`.** Folding it in would make
  the crawl state machine look busy, which would make the coordinator's own "reject while a crawl
  is active" guard reject the very captures it exists to allow.
- **XML is crawl-shaped.** `SnapshotCrawlState` synthesizes a minimal `ScreenCrawlState` using the
  same two-step identity derivation `DeepCrawlCoordinator` uses for a root screen, so
  `ScreenXmlReader` and every other consumer works unchanged. The run-level session id is
  `snapshot_<ts>`, not `crawl_<ts>` — identical document shape, honest provenance.
- **`LiveActionIds` is injected rather than read from the framework.**
  `AccessibilityAction.ACTION_PAGE_DOWN` and friends resolve through statics that throw under the
  JVM unit-test runtime, so the extracted class could not otherwise have been unit-tested without
  a build-file change that SCOPE ruled out.
- **`liveNodeActions` is `by lazy`.** Same API-29 field: eager initialization would move a possible
  `NoSuchFieldError` on an API 24–28 device from "first scroll attempt" (where it lives today)
  forward to service construction.
- **`SnapshotFileStore` takes an `ArtifactWriter` seam** so `.done`-written-last is asserted on
  real write order. An mtime comparison would pass vacuously — a sub-millisecond write burst gives
  every file the same timestamp.

## Test plan

**Automated — 289 tests, 0 failures** (baseline 220, +69). `assembleDebug` green, no new compiler
warnings, `capture-screen.ps1` parses clean. Re-run at the final artifact state.

Phase 1 was a pure refactor of previously-uncovered code, so a green suite proves nothing on its
own. Characterization tests were written against pre-refactor behavior and then **two mutations
were injected to prove they bite**:

| Mutation | Result |
|---|---|
| `preferredActionIds` filters unsupported ids instead of appending them | 6 tests RED |
| path candidates attempted in natural order instead of `asReversed()` | 2 tests RED |

**Device — 4 captures across 2 packages on emulator-5554.** Evidence in
`.spear/a2h-4iq/evidence/03-device/`.

| Criterion | Result |
|---|---|
| Target app keeps foreground | PASS — `mCurrentFocus` identical before/after, all 4 captures |
| Disabled service fails fast | PASS — 2.0 s with named cause, vs a 45 s timeout |
| Scrollable screen | PASS — Settings, 21 elements, 3 scroll steps |
| Deep manually-reached screen | PASS — GMS `LinkDevicesSettingsActivity`, different package, no target app selected |
| Short sub-screen | PASS — Bluetooth SubSettings, **4 of 4** clickables vs `uiautomator` |
| Snapshot XML vs crawl root XML | PASS — identical structure; only `<edge>` children differ |
| Two captures don't collide | PASS — 4 distinct directories |

Criterion 4 was checked against the device's **own crawl root for the same screen**: every tag
matches and `<screen-identity>` is byte-identical. On Settings, the crawl root XML and a snapshot
each contain exactly 21 `<element>` entries.

## Defects found and fixed during device testing

1. **Accessibility pre-check matched the wrong notation.** It looked for the shorthand
   `pkg/.AppToHtmlAccessibilityService`; the platform stores the fully-qualified
   `pkg/pkg.AppToHtmlAccessibilityService`, so the script refused to run against a correctly
   enabled service — while printing the passing value in its own error. Now matches package and
   class independently, plus a new `accessibility_enabled` master-switch check, which was a second
   silent-timeout path.
2. **`SNAPSHOT_SCREEN_ID` was `screen_001`.** The real crawl root id is `screen_00000`, so the
   stated goal — a snapshot file base indistinguishable from a crawl root's — was not actually met.
   Corrected and pinned by a test.

## Not verified — read before merging

- **A full crawl was not re-run post-refactor.** This was the intended backstop for the ~180
  extracted lines; skipped for time at the operator's direction. The mutation probes and 289 green
  tests cover the extracted *logic*, not the framework wiring around it. Worth running before this
  ships anywhere that matters.
- **A true modal dialog** (zero scrollables, single step) is unit-tested only. The "short screen"
  device case turned out to have two scrollable containers.
- **`-NoScroll` and the `crawl_in_progress` rejection** were never exercised on a device.
- **Grading was self-assessed.** SPEAR asks for a fresh adversarial grader per round; none was
  authorized this cycle. The mechanical half is reproducible from archived commands; the judgment
  half has no independent check.

## Follow-ups filed

- **`a2h-6up` (P1 bug, new)** — `rootInActiveWindow` misses content on multi-window screens.
  Discovered here: on GMS `LinkDevicesSettingsActivity` we capture 5 nodes / 1 pressable where
  `uiautomator` sees 55 nodes / 6 clickables. **Pre-existing and shared with the crawl** —
  `captureCurrentRootSnapshot` is untouched by this PR, and the Settings control shows both paths
  yielding identical trees. Artifacts stay structurally valid, so nothing errors; the loss is
  invisible without a cross-check.
- **`a2h-oo0` (P2, pre-existing)** — gate the `CAPTURE_SCREEN` receiver before production. It is
  registered in **every** build variant, including release: an unauthenticated exported surface
  that can dump the foreground app's tree to disk. A deliberate trade for a local dev tool.

## Rollback

Additive and self-contained. Revert the commit: the snapshot path disappears, `LiveNodeActions`
folds back into the service, and the crawl path is untouched — no migrations, no format changes,
no persisted state. `snapshots/` directories left on a device are inert and can be deleted.

## Assess note

The 16 defects the loop reported are all in `tools/spear-cli/**`, the vendored SPEAR CLI's own
TypeScript (1 `any`, 14 `console.log`, 1 subjective). Zero in `app/src`, `.claude`, or
`documentation`. The `code` adapter scans the whole repository and `factory/FUNCTION.md` makes
vendored source read-only; a `console.log` in a CLI is also how a CLI writes to stdout. Registered
with `--allow-fast-convergence` rather than by editing a dependency to satisfy a metric.
