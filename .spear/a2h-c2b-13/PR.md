# An operator settles the identity cases they choose, in a case page, and the resume closes them

Bead `a2h-c2b.13` · epic `a2h-c2b` · targets `epic/structured-screen-identity` (not `main`).

## Summary

Since `a2h-c2b.3`, a screen whose identity does not hold pauses the crawl on a case, and the pause
says to Stop, settle and resume. Until now nothing made that loop close. Settling meant hand-editing
two files that had to agree, a typo silently dropped the screen on resume, and a resume never closed a
case.

With this change:

- **The case overview and case pages.** `validate-screen-identity.ps1 -Crawl <dir>` starts the tool and
  opens a local page that lists every case with its status. Each case page shows a traits editor
  (pre-filled, with commented examples) beside the screen: its elements, each with *copy as
  has-control*, the recorded identity and the first viewport's tree.
- **Settling is checked, then written.**
  - *Check Traits* runs the crawler's own two-way rule against the crawl's saved captures. The traits
    must hold on this screen, no other settled identity may hold there, and they may hold on no other
    screen.
  - *Settle + Push* checks again, then writes the screen's `.xml` and `.html` identity block as one
    change, stamped `settled-by="operator"`, and marks the case **handled**. The write is atomic, with
    rollback and a per-folder lock.
  - It pushes the three files only if the device still holds what was pulled.
- **The resume confirms handled cases live.**
  - It revisits only handled screens. If the identity holds on the live screen the case is
    **closed**, its pointers go, and the crawl carries on along that screen.
  - If not, the case reopens with the live reason and the crawl pauses.
  - Open cases are never touched.
- **Who settled an identity is recorded** on its `<traits>` block: `machine` or `operator`. A missing
  or unknown stamp is refused, never guessed.
- **A resume never silently drops or overwrites an operator's edit.** A refused identity, or an `.xml`
  and `.html` that disagree, stops the resume and names the files.

The same engine also runs from the command line for scripts: `-Settle <case> -Traits <file>
[-DryRun] [-Push]`, plus a text survey with `-Survey`.

## What changed

- **Main (engine):**
  - `OperatorSettlement`: the offline check, the crawl-folder model and the overview rows.
  - `SettleWriter`: the atomic three-file settle, the push decision and `CaseSettleService`.
  - `IdentityOrigin` and the `handled` status.
  - Loader refusals (`RefusedScreenIdentity`).
  - Pointer refresh and settle preserve every byte outside the block, line endings included.
- **Crawler:** `DeepCrawlCoordinator` queues handled screens, confirms them at replay arrival or at
  the root re-scan, and closes or reopens. A confirm-only visit never adds an edge or re-expands.
- **Tool (host-only, test source set):**
  - `CaseToolServer`: the JDK HTTP server, on 127.0.0.1 only, with a one-time token passed through a
    file.
  - `CasePages`, `CaseToolMode`, `CaseToolJobs` and `AdbDeviceCrawl`.
  - The script's new switches.
- **Docs:** the skill's *Settling a case*, `documentation/screen-identity.md`, `crawler-module.md`
  and `data-and-state.md`.
- **Breaking:** `<traits>` needs a `settled-by` stamp, so a crawl saved by the `a2h-c2b.3` build will
  not resume until it is crawled again. No backward compatibility is required (`CLAUDE.md`).

### Scope amendments (recorded in `PLAN.md`)

- `CrawlRunTracker.openCase`: a settled identity that fails live, on a screen that already has an open
  case raised on it, refreshes that case instead of opening a duplicate. This touches the case-raising
  rules SCOPE froze. Without it, a reopened case would gain a twin on every resume.
- The tool reads the crawl folder on every request instead of once, so the overview always shows what
  is on disk.

## Test plan

- `./gradlew clean testDebugUnitTest assembleDebug`: **684 tests, 0 failures, 4 skipped** (the inert
  tool jobs; the baseline was 545). `assembleDebug` is green, with no new warnings.
- **Red→green pins**, each archived as a red run before its fix:
  - a resume over a refused identity fails loudly;
  - a resume over a disagreeing pair fails loudly;
  - a handled case is closed on resume;
  - a reopened case left open is still one case.
- **70 of 70 mutations RED** in one run (the harness is kept with the cycle's local evidence).
- **Eight independent adversarial grading rounds**, a fresh grader each time:
  - Rounds 1–4 found two majors (half a settle on a failed rename; a duplicate case) and many minors.
    All were fixed.
  - Rounds 5–8 failed only on unpinned operator-visible outputs, and each round's findings were fixed
    and pinned.
  - Round 7 checked base `e9c6fe5`'s suite against the new sources: no change for ordinary crawls.
- **Device** (owner, emulator): a fresh Settings crawl with machine stamps; Continue and Stop at case
  pauses; an offline settle of Battery that holds, with `case_000` handled.
  **Not yet shown:** the push of the settled files and the resume that closes `case_000`.

## Known limits, filed

- `a2h-c2b.25`: a screen that changed completely cannot be settled against its old capture.
- `a2h-c2b.24`: a screen XML that is not readable XML at all is still skipped on resume.
- `a2h-c2b.23`: a screenshot of the case's screen on the case page.

## Rollback

Revert the merge. A crawl that holds a `handled` case is refused by a reverted build, which does not
know that status, so that crawl has to be crawled again. A crawl with only machine-stamped traits
loads, and the stamp is ignored.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
