# Screen identity lives in the screen, and one command says whether it holds

Bead `a2h-u68` · epic `a2h-c2b` · targets `epic/structured-screen-identity` (not `main`).
Absorbs `a2h-c2b.5`. Fixes `a2h-4ah` as a declared scope exception.

## Summary

Every captured screen now carries its **whole identity** — name, element set and traits — inside
its own `.xml` and `.html`. One command answers whether that identity **holds**: does it match the
screen in front of it, and does it match **only** that screen. The crawler **acts on** an identity an
operator has settled by hand.

Before this, the XML carried only the name half, the page carried nothing, and `SavedCrawlLoader`
blanked the content half on every resume — correctly at the time, because nothing had written it.
So a hand-edited identity had nowhere to live, and would have been erased on resume if it had.

Every verdict comes from the crawler's own code. The tool composes `TraitEvaluator`,
`SameScreenPolicy` and `ClickFallbackMatcher` and formats what they return. It does not
re-implement any of them, so a verdict from the tool is one the crawler agrees with.

## What changed

**The identity reaches disk.** `<screen-identity>` grows `root-class`, the `<element>` set and a
`<traits>` block. The page embeds the same block in `<head>` from the same producer. `package` now
means the app package in every container that uses it; the name half's normalized token moved to
its own `name-package` attribute.

**It survives the crawler.** `SavedCrawlLoader` recovers the whole identity on resume. Every page
write carries the identity. `rewriteScreenHtml` *requires* it, so a caller cannot forget to pass it.
Verified on a real 38-screen crawl: traits added by hand come back **byte-identical** after a load
and rewrite.

**The crawler acts on it (A4c).** Route-replay arrival still checks the name. When the identity has
been settled, it also requires the traits to hold. It does **not** compare the element set, because
content legitimately changes between visits.

The change is opt-in: an identity with no traits is not consulted. Removing that guard fails 13
`DeepCrawlCoordinatorTest` cases written before this cycle. A trait that does not hold takes the
existing `handlePreparationFailure` path, and the failure message names the trait.

**The tool.** `.claude/skills/validate-screen-identity/` reports `HOLDS` / `NOT_UNIQUE` /
`DOES_NOT_HOLD` / `UNSETTLED` / `INVALID_INPUT`, each with a distinct exit code. It validates a
screen against its own capture, a second capture, the live device (`-Live`, via `capture-screen`),
or a whole crawl (`-Crawl`). It needs no device for the disk paths and never writes to its inputs.

| Area | Files |
|---|---|
| **New** | `ScreenIdentityXml`, `CrawlXml`, `CaptureTreeReader`, `CaptureIdentitySource`, `ScreenIdentityValidator`, `ScreenIdentityReport`, `ReplayArrivalCheck` |
| **Changed** | `AccessibilityXmlSerializer`, `ScreenXmlReader`, `HtmlRenderer`, `CaptureFileStore`, `SnapshotFileStore`, `SnapshotCrawlState`, `SavedCrawlLoader`, `DeepCrawlCoordinator`, `ScreenIdentity`, `ScreenIdentityPolicy`, `CrawlerModels` |
| **Build** | `app/build.gradle.kts` forwards `-Da2h.*` to the test JVM only when set; `AndroidManifest.xml` gains `<queries>` |
| **Docs** | new `documentation/screen-identity.md`; pointers from `crawler-module.md` and `data-and-state.md` |

Two refactors of covered code landed first, each pinned by an injected mutation:
- one reader and writer for the `<element>` shape;
- one home for the back-affordance rule, `BackAffordanceCounting`. Its single deliberate exception
  is named in code as `RouteStepDestinationCheck`, rather than being a bare `true` behind a comment.

## Bugs in the existing app this fixed

- **`saveScreen` wrote pages without an identity.** On a real crawl, 30 of 36 pages were missing the
  block. A page was only rewritten when one of its edges resolved, so every leaf screen shipped
  without one.
- **The app picker saw 3 apps out of 20+** on any API 30+ device, because the manifest declared no
  package visibility. This is `a2h-4ah`, fixed here as a declared scope exception because it blocked
  this cycle's own device evidence.
- **A resumed crawl wrote an empty `replayFingerprint`** into its own manifest and graph. This fix
  was pinned red before it was made green.

## Not in this PR (deliberately)

- Proposing traits automatically, operator cases, and edits made while a crawl runs — all
  `a2h-c2b.3`.
- Canonical trait order in identity equality (`a2h-c2b.10`).
- Any change to a policy's semantics or tolerance, `ElementFingerprint`, dedup, entry restore or
  destination settling.
- Rewriting the stale route-replay section of `crawler-module.md`. It is marked stale and points to
  `a2h-c2b.9`.

## Verification

- `./gradlew clean test`: **436 tests, 0 failures** (347 before), 2 skipped by assumption.
  `./gradlew assembleDebug`: green, 0 warnings. A plain `./gradlew test` runs no validation and
  writes no report.
- **Three rounds of independent adversarial grading** found 31 defects: 8, 10 and 13. Each fresh
  grader attacked dimensions the previous one had not. Rounds 2 and 3 each found that the previous
  round's fixes were incomplete. The worst finds:
  - the tool certifying `HOLDS` on an identity the crawler refuses to arrive at;
  - a uniqueness check that structurally could not find a collision, hidden by fixtures that all
    shared the target's name;
  - a build failure reported as `DOES_NOT_HOLD`.

  All were fixed and pinned, and each pin was shown to fail before its fix.
- **On the device, final build.** A Settings crawl produced **39 of 39** screens with the block in
  both files and 0 unreadable captures. The replay-arrival check fired **0** times, and there were
  0 screen-preparation failures. The run itself ended `partial_abort` at "Audio will play on", which
  leaves a SystemUI output-switcher panel on top. Crawls from June and September 8, made before this
  branch existed, fail the same way. It is tracked with a reproducer on `a2h-btf`.
- **The settle loop, run on real captures:**
  - fresh → `UNSETTLED`
  - hand-settled → `HOLDS`
  - trait broken → `DOES_NOT_HOLD`, naming the trait
  - colliding sibling → `NOT_UNIQUE`, naming both screens
  - tightened → `HOLDS`
  - one file edited alone → `INVALID_INPUT`, naming both values
- **Evidence for `a2h-c2b.3`** (Settings and Maps). Across two crawls of Settings, **17%** of screens
  changed their element set. **0%** of id-bearing elements churned, but ~88% of identity elements
  have no id. `has-list` stays stable because it reads container and row ids out of the tree. The
  write-up is on the bead.

Evidence lives under `.spear/a2h-u68/evidence/` (git-ignored by design, and reproducible).

**How to check it yourself**

```bash
./gradlew test --rerun-tasks
```

```powershell
.\.claude\skills\validate-screen-identity\scripts\validate-screen-identity.ps1 -Crawl 'E:\Logs\com.android.settings\crawl-20260922_114748'
```

## About the "16 defects" in the SPEAR output

`spear resolve` reports 16 open defects. All of them are in the vendored `tools/spear-cli` source:
`console.log` calls and one `: any`, plus a generic "score against the rubric" item. They are listed
as known-acceptable in `ASSESS.md`, and vendored code is read-only under `factory/FUNCTION.md`.
**None of them points at this change.** The "stuck since round 1" warning is the same count not
moving.

## Follow-ups filed

- `a2h-a3m` (P2): pin the A4c wiring at the coordinator level. The seam is pinned; the call site is
  not.
- `a2h-9gj` (P2): the weak-identity diagnostic misses id-less elements that have volatile labels.
- `a2h-cgx` (P3): the skill's exit-status mapping is verified by transcript, not by a test.
- `a2h-ai2` (P3): the name half's XML/HTML comparison goes through the normalizing dedup key.
- `a2h-jq6` (P3): `-Strict` only fires when the headline is already `HOLDS`.
- `a2h-scm` (P3): `parseName` recomputes confidence from the normalized title. This is inert today.
- `a2h-27l`, `a2h-5g6` (P4): page indentation shift; the survey re-parses a crawl once per screen.

## Rollback

Revert the merge commit. Captures made on this branch use the new `<screen-identity>` format, and
no backward compatibility is kept (`CLAUDE.md`). After a revert, recapture rather than resume a crawl
that was made on this branch. No data migration is involved.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
