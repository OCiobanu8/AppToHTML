# PLAN

Bead: `a2h-c2b.13`. Branch `feat/operator-settles-identity-cases`, cut from
`epic/structured-screen-identity` at `e9c6fe5`. The PR targets the epic branch.

- [x] User confirmed

## Load-bearing facts

Verified against the source at `e9c6fe5` on 2026-10-16. The plan depends on each of them.

1. **The JDK HTTP server is usable only from the unit-test source set.**
   `com.sun.net.httpserver.HttpServer` compiles and serves in `app/src/test`. A probe test bound to
   `127.0.0.1:0`, served, and stopped; the probe was deleted afterwards. It is **not** on the Android
   main compile classpath (`android.jar`). So the server, the pages and the real `adb` runner live in
   the test source set, beside the existing `ScreenIdentityValidationEntryPoint`. The engine (check,
   settle, push decision) lives in `main`, next to `ScreenIdentityReport`, so it shares the
   crawler's classes and is unit-tested like them.
2. **The tool's properties reach the test JVM only when they are listed.** `app/build.gradle.kts`
   forwards a fixed list of `a2h.*` keys and, when any is set, marks the test task never up to date
   and shows its output. The new keys must be added to that list.
3. **One producer, two files.** `HtmlRenderer.renderIdentityBlock` embeds
   `AccessibilityXmlSerializer.screenIdentityBlock(identity)`, indented four spaces, inside
   `<script type="application/xml" id="screen-identity">`. The XML writes the same block through
   `appendScreenIdentity` at `<crawl>` depth + 1. A settle can therefore replace exactly that block
   in each file with output from the same producer.
4. **Traits are parsed in three places, through one function.**
   `ScreenIdentityXml.parseTraits` serves:
   - `<screen-identity>`, through `ScreenXmlReader.parseScreenIdentity` and
     `CaptureIdentitySource.readHtmlIdentity`;
   - route-step identities, which never carry traits;
   - the case file's rejected proposal, through `IdentityCaseStore.parseCase`.

   A stamp rule passed per call site covers all three: required on a screen identity, forbidden in
   the case file.
5. **The crawler's resume silently skips a screen whose identity is refused.**
   `ScreenXmlReader.readFull` goes through `parseHeadSafely`, which swallows
   `MalformedScreenIdentity` and returns null. `SavedCrawlLoader` then logs "Skipping malformed crawl
   XML" and drops the screen. `readHeadReportingRefusals` already exists for the tool. The loader
   never reads the `.html`.
6. **Only machine settlement writes traits in a crawl.** `DeepCrawlCoordinator.settleNewScreen`
   returns `proposal` (traits proposed) on `Settled`, and `named.copy(traits = emptyList())`
   otherwise. Every other identity the crawler writes comes from `CrawlScreenRecord.identity`,
   through `persistedIdentity`, which was loaded or settled earlier. No path copies a new identity
   onto an existing record.
7. **Pointers come from the tracker.** `CrawlRunTracker.openCaseIdsFor` feeds the serializer, through
   `ScreenCrawlState.openCaseIds`, and `IdentityCasePointers.refresh`. The loader refuses a pointer
   unless the case file holds that case **open** for that screen. `closeCase` exists, but only
   tests call it.
8. **Replay arrival already re-checks a settled identity live.** `prepareScreenForExpansion` runs
   `ReplayArrivalCheck.check(record.identity, live, firstViewport)`. `TraitsDoNotHold` becomes a
   `SETTLED_DOES_NOT_HOLD` pending case on `ScreenPreparationResult.Failure`. That case is raised,
   and then `handlePreparationFailure` recovers and **adds a FAILED edge to the parent**. For a screen
   that is already expanded, that extra edge would be wrong, so a confirm-only visit must not take
   that path.
9. **The root is not replayed at resume.** It is re-scanned, and `settledRootDivergence` asks
   whether its settled identity still holds on the re-scanned first viewport. That is where a
   handled root is confirmed.
10. **The resume frontier comes from expansion status.** `seedResumeFrontier` queues `IN_PROGRESS`
    and then `NOT_STARTED` screens for `ContinueAuto`, a reachable subset for `ResumeFromScreen`,
    and one screen for `ReExpand`. A `COMPLETED` screen is never queued today. When the frontier is
    empty, the crawl completes immediately.
11. **The tool's screen id is the file base name.** `CaptureIdentitySource.load` names a capture
    `screen_00009_battery`, while cases name `screen_00009`. The engine maps them through each
    capture's own `ScreenXmlReader.readHead(...).screenId`.

## Design

### Engine (`app/src/main`, pure Kotlin, no Android APIs)

- **`IdentityOrigin`** is `MACHINE("machine")` or `OPERATOR("operator")`. `ScreenIdentity` gains
  `settledBy: IdentityOrigin?`. It takes part in `equals` and `hashCode`.
- **The stamp.**
  - `ScreenIdentityXml.appendTraits(…, settledBy)` writes `<traits settled-by="…">`.
  - `parseTraits(parent, stamp)` returns the traits together with their origin. A `REQUIRED` stamp
    refuses a missing or unknown `settled-by`, and a `FORBIDDEN` stamp refuses any.
  - `appendScreenIdentity` fails fast (`check`) on traits without an origin, so a missed path is a
    loud bug, not an unreadable file.
  - `settleNewScreen` stamps `MACHINE`.
- **The case file.**
  - `IdentityCaseStatus.HANDLED("handled")`.
  - `IdentityCase.replaced: ReplacedIdentity?`, holding traits and an origin, written as
    `<replaced settled-by="…"><traits>…</traits></replaced>`. The `<traits>` inside is unstamped,
    and so is the rejected proposal.
  - "Active" means open or handled. Pointers name active cases.
- **The tracker.**
  - `activeCaseIdsFor` replaces `openCaseIdsFor`.
  - `closeCase` becomes a production path.
  - New: `reopenCase(id, detail)` and `handledCasesOn(screenId)`.
- **The loader's safety nets.**
  - A new `RefusedSavedCrawl` base exception, with `MalformedIdentityCases` re-parented under it,
    plus `RefusedScreenIdentity`.
  - The loader reads with refusals reported. It wraps a `MalformedScreenIdentity` as
    `RefusedScreenIdentity`, naming the file.
  - It asks `CaptureIdentitySource` for an **agreement check**. That is the existing
    `differenceBetween`, extended with origin and exposed as `internal`. It runs only when the
    `.html` exists and carries an identity block. A refused or disagreeing page raises
    `RefusedScreenIdentity`, naming both files.
  - A missing page is not a disagreement: the next rewrite recreates it from the XML, and no edit
    is lost.
  - `SavedCrawlRepository` catches `RefusedSavedCrawl`.
- **`CaseCrawl`** is the crawl folder as the tool sees it:
  - every case;
  - every screen as a `LoadedCapture`, mapped to its head screen id (fact 11);
  - the captures that could not be loaded, with their problems.
- **`OperatorSettlement.check(crawl, caseId, traitsText)`** returns a `SettleVerdict`:
  - `Holds`;
  - `AssertsNothing`;
  - `FailsOnOwnScreen(failing)`;
  - `NotUnique(forward, reverse)`;
  - `CannotCheck(screenIds)`;
  - `NotSettleable(reason)`, for an unknown case, a closed case, an unparseable or stamped traits
    text, or the raised-on screen's capture not loading.

  It composes:
  - **Own and reverse:** `IdentitySettlement.decide(candidate, ownContext, others)`. The others are
    every other screen, with its tree.
  - **Forward:** `TraitEvaluator.matchKnownScreens(ownContext, others' identities + candidate)` must
    be `One(candidate)`.

  The verdict names screens by id. Determinism comes from those functions' own sorting.
- **`SettleWriter.write(crawl, caseId, candidate)`.**
  - It replaces **only** the identity block:
    - in the XML, the `<screen-identity>` element at its own depth;
    - in the HTML, the `<script id="screen-identity">` contents at four spaces;
    - both with `AccessibilityXmlSerializer.screenIdentityBlock(candidate, depth)`.
  - Block location is shared with `IdentityCasePointers.refresh`, which already finds this element.
  - It sets the case `HANDLED`, recording `replaced` only on the first settle of a case whose screen
    had traits.
  - It writes the three new texts into a temporary directory inside the crawl folder, under the
    same names. It verifies them with `CaptureIdentitySource.load` on the temporary `.xml`, which
    must parse to the candidate. Only then does it rename all three into place.
- **`PushDecision.decide(localBefore, device)`** returns `Allowed` or `Refused(differing names)`. A
  file missing on one side but not the other differs.
- **`DeviceCrawl`** is an interface: `available`, `sha256(name)` and `push(file, name)`.
- **`CaseSettleService.settle(crawl, caseId, traitsText, push, device)`** runs, in order:
  1. check;
  2. if pushing, hash the local files, read the device hashes and decide;
  3. write;
  4. push;
  5. re-hash the device and report.

  A refusal at any step before the write writes nothing anywhere. The command line and the server
  both call it.
- **`CaseOverview.rows(crawl)`** is the shared data behind the text survey and the overview page.

### Crawler resume (`DeepCrawlCoordinator`)

- **Root.** At the re-scan, a root with handled cases is confirmed:
  - `TraitVerdict.HOLDS` closes them.
  - `DOES_NOT_HOLD` reopens them with the live reason and pauses on them. No new
    `SETTLED_DOES_NOT_HOLD` case is opened for that root.
  - With no handled case, `settledRootDivergence` behaves as today.
- **Frontier.** After `seedResumeFrontier`, in every resume mode, each non-root screen raised on by a
  handled case is appended, sorted by id, if not already queued. Screens already `COMPLETED` go into
  a `confirmOnly` set.
- **Confirming.**
  - `prepareScreenForExpansion` returns `Success` → the screen's handled cases are closed: the case
    file is written, the pointers are refreshed on every involved screen, and the manifest is
    saved. A `confirmOnly` screen then stops there; any other screen is expanded as usual.
  - `Failure` with `TraitsDoNotHold` on a screen with handled cases → those cases are reopened and
    paused on, with no new case. After that:
    - a `confirmOnly` screen recovers without adding an edge to its parent (fact 8), and aborts the
      run if recovery fails, as today;
    - any other screen takes the existing failure path.
  - Any other `Failure` (route or name) → the cases stay `handled`. The same split applies:
    - `confirmOnly` recovers without adding an edge;
    - any other screen takes the existing failure path.
- `raiseIdentityCase` is split: **record** (open a case and write it) and **pause** (write the case
  file, refresh pointers, save the manifest, await the decision, and Stop if asked). A reopened case
  uses pause alone.
- Logs: `identity_case_closed`, `identity_case_reopened` and `resume_confirm_queued`.

### Tool shell (`app/src/test`, beside the entry point)

- **`ToolMode.select(property)`** is a pure function, unit-tested:
  - `a2h.settle` → command-line settle;
  - `a2h.crawl` with `a2h.survey` → text survey;
  - `a2h.crawl` alone → server;
  - `a2h.target` → one screen.
- **`CaseToolServer`**, on the JDK `HttpServer` (fact 1):
  - bound to `127.0.0.1` on a given port;
  - every route checks the token, in a query parameter or a header;
  - an idle timeout, with its length in code;
  - routes `GET /health`, `GET /`, `GET /case/<id>`, `POST /case/<id>/check`,
    `POST /case/<id>/settle` and `POST /done`.
  - The crawl is loaded once and reloaded after each settle, so the overview shows `handled`.
- **`CasePages`** renders the overview and the case page as self-contained HTML, with inline CSS and
  JavaScript. Every value is HTML-escaped.
  - The case page carries the editor pre-fill (the rejected proposal, or the current identity for a
    "settled identity does not hold" case) and the commented examples.
  - The screen's identity block and first-viewport tree come from its XML, with a *copy as
    has-control* action per pressable.
  - The JavaScript posts the editor text and renders the JSON verdict. It shows *Settle + Push* only
    when the last passing check was of the current text, and offers *Settle* alone when no device
    is available.
- **`AdbDeviceCrawl`** implements `DeviceCrawl` with `ProcessBuilder`, through `adb -s <serial>`:
  - `shell sha256sum <remote>/<name>` for hashes;
  - `push` for files.
  - The remote root is `/sdcard/Android/data/com.example.apptohtml/files/html/<package>/crawl`, the
    package read from the crawl's screens, overridable.
- **The entry point** gains `serve the case tool` and `settle a case`. `survey a crawl` now needs
  `a2h.survey` and prints a cases section. The single-screen report prints the origin.
- **`validate-screen-identity.ps1`**:
  - new switches `-Survey`, `-Settle`, `-Traits`, `-DryRun` and `-Push`;
  - `-Crawl` alone picks a free port and a token, starts Gradle in the background with output to a
    log, polls `/health`, opens the browser, and waits for Gradle to exit;
  - it resolves `adb` the way the pull-crawl script does.
- **`app/build.gradle.kts`** forwards the new keys: `a2h.survey`, `a2h.settle`, `a2h.traits`,
  `a2h.dryRun`, `a2h.push`, `a2h.port`, `a2h.token`, `a2h.adb`, `a2h.serial` and `a2h.remoteRoot`.

## Steps

1. **Stamp the origin.** Add `IdentityOrigin` and the stamp read and write, and have the crawler
   stamp `MACHINE`. Leave the case file unstamped. Run the suite. Catalogue each red pre-existing
   test **by the behaviour it asserts**, in `evidence/01-stamp-fallout/`. Each one is a test that
   builds trait-bearing identities and serializes them, or pins exact bytes. Fix them only by adding
   the stamp. Add the origin tests from SCOPE "Origin (6)".
2. **The loader's safety nets, red first.** Write `resume_refusesAScreenWhoseTraitsAreRefused` and
   `resume_refusesAScreenWhoseXmlAndHtmlIdentitiesDisagree` against the pre-change loader. Both must
   be red: the first skips the screen, the second loads. Archive the runs as `evidence/02-red-loader/`.
   Then implement the refusals, the agreement check including origin, and the repository catch.
   Green, archived as `evidence/03-green-loader/`, plus the panel test.
3. **Status `handled`.** Add the status, the `replaced` record, the tracker's close, reopen and
   active ids, the pointer rule and the loader's pointer rule. Tests from "Status and pointers (4)".
4. **The engine.** Add `CaseCrawl`, `OperatorSettlement.check`, `SettleWriter`, `PushDecision`,
   `DeviceCrawl` and `CaseSettleService`, with tests from "Offline settle (1, 2)". Those cover:
   - every refusal leaves the bytes unchanged;
   - the write touches only the identity block;
   - determinism;
   - the check agrees with the crawler's own two-way check (`IdentitySettlement.decide` plus
     `matchKnownScreens`, computed independently in the test);
   - a fake device for push.
5. **Resume confirms handled screens, red first.** Write the coordinator test
   `resume_overAHandledCase_revisitsItsScreenAndClosesTheCaseWhenItHoldsLive` through `FakeHost`.
   Run it against the coordinator with the new model but **no** confirm logic: it must be red,
   because the case stays `handled`. Archive as `evidence/04-red-resume/`. Then implement the
   frontier, confirm, reopen and root changes. Add the rest of "Resume revisits handled screens
   (5)" and "Never overwritten (7)" (the byte-for-byte resume). Green, archived as
   `evidence/05-green-resume/`.
6. **The tool shell.** Add `ToolMode`, the survey's cases section, the origin line, the settle
   command line, `CaseToolServer`, `CasePages`, `AdbDeviceCrawl`, the script and the Gradle keys.
   Server tests run against the real JDK server on localhost, from "Case page and server (9)" and
   "Survey and overview (8)". Run the script by hand once against a copy of the Settings crawl in
   the scratchpad, never the `E:\Logs` original, and record the transcript as
   `evidence/06-tool-smoke/`.
7. **Documentation.** Add the skill's *Settling a case* section: the overview and case page first,
   then the scriptable switches, the push safety and "settle while stopped". Add the pointers in
   `documentation/crawler-module.md` and `documentation/data-and-state.md`. File the follow-up bead
   for unreadable screen XML.
8. **Mutations.** Apply SCOPE's mutations 1–11 one at a time. Run each, archive it in
   `evidence/07-mutations/` (one file per mutation, with the red test named), and revert. Use
   `git diff --stat` after each revert to show a clean tree.
9. **Final state.** Run `./gradlew test` and `./gradlew assembleDebug` at the final artifact.
   Archive both as `evidence/08-final-state/`, with the warning count.
10. **Assess loop.** Each round uses a fresh adversarial grader subagent, stricter every round. It
    gets `ASSESS.md`, the artifact paths and the grade contract, gathers its own proof, and attacks a
    dimension the previous round did not.
11. **Gate 3 device run,** per SCOPE. Evidence goes under `evidence/09-device/`.

## Amendments during the Assess loop

Each one was made in answer to a grading round's finding. Evidence is in `evidence/10`–`14`. The
items that change something SCOPE fixed are marked **(SCOPE)** for Gate 3.

- **Round 1.**
  - The settle keeps the originals and rolls back a failed move. If the rollback itself fails, the
    settle reports HALF WRITTEN and keeps the originals (`SettleFileOps` seam).
  - New verdict `DoesNotNameScreen`: the forward direction must land on the screen itself.
  - The first identity a case replaced is never overwritten.
  - The idle timer is refreshed only by authorized requests.
- **Round 2.**
  - **(SCOPE: "the case raising rules" were listed as not modified.)** `CrawlRunTracker.openCase`
    gained one dedup branch. A SETTLED_DOES_NOT_HOLD divergence on a screen that already has an open
    case raised on it refreshes that case, whatever its kind, keeping its proposal; it does not open
    a second case. Without it, a reopened case that is left open gains a duplicate case on every
    resume (failure mode I). The branch can also absorb such a failure into an open collision case,
    but that case only arises after a hand edit.
  - The push refusal names both causes. A settle with no device lists the `adb push` commands for
    its files (`toPush`, `PushCommands`); the CLI's exit code 6 covers a failed write or push.
  - The adb timeout is enforced.
  - A refused case file is reported through `-Settle`, `-Survey` and the server.
  - Shared helpers: `locatePageBlock`, `parseXmlElement`.
- **Round 3.**
  - Case ids are restricted to letters, digits, `_` and `-` (`IdentityCaseStore.isCaseId`). The case
    file refuses any other id, and the writer refuses one before building a path.
  - **(SCOPE: "the entry point loads the crawl folder once".)** The server reads the folder on every
    request instead, so the overview always shows what is on disk. The folder is small, so the cost
    is negligible.
  - The crawl-wide read follows the loader's missing-page rule (`requirePage = false`).
  - A settle keeps every other line ending.
  - adb read failures are told apart from missing files.
  - The token is no longer printed.
- **Round 4.**
  - Pointer refresh keeps every other line ending, so a close no longer rewrites a mixed-ending file.
  - A settle refuses while a HALF WRITTEN settle's originals are kept.
  - The check requires the raised-on screen's own page, so a dry run never promises an impossible
    settle.
  - The token is passed through a one-time file, so Gradle never records it.
  - The page script reports non-JSON answers.
- **Round 5.**
  - A settle that writes takes an exclusive lock per crawl folder (`CaseSettleService.lockFileFor`, a
    file in the system temporary folder named after the folder's canonical path). A second settle on
    the same folder, from another process, is refused instead of losing the first one's `handled`.
  - An empty `<traits>` block with an unknown stamp is refused, as SCOPE call 6 states.
  - `a2h.token` is no longer forwarded by Gradle, so the recorded route cannot be used; the entry point
    documents `a2h.tokenFile`.
  - Recorded here, because round 5 found it undocumented: the round-2 `openCase` change also made
    the **pre-existing** same-kind refresh keep a case's proposal when the new divergence proposes
    nothing (`rejectedProposal.ifEmpty { existing }`). Before, an empty proposal replaced it. Only
    SETTLED_DOES_NOT_HOLD divergences propose nothing, and their cases carry no proposal, so a crawl
    with no operator settle behaves exactly as before.
  - Design limit, documented in SKILL.md and filed as `a2h-c2b.25`: a screen that changed completely
    cannot be settled against its old capture.
- **Round 6.** This round and round 5 both failed on the same class of gap: operator-visible output
  and wiring with no pin. The class was closed as a whole, not one finding at a time.
  - The survey and settle jobs moved out of the inert entry point into `CaseToolJobs`, so every line
    the script reads, and the closing `status=`, is tested.
  - Every part of the case page and the overview is now pinned: header, related screens, device line,
    tree, snippet escaping, row order, and each row's columns.
  - An empty, unstamped `<traits>` on a screen identity is refused, as SCOPE call 6 says.
  - A lock that cannot be opened is reported as an outcome.
  - A settle that stopped part-way blocks every later settle, and the message points at its kept
    originals.
  - The server binds 127.0.0.1 exactly, which is where the script looks.
- **Round 7.** The owner chose to fix the findings and run one final round (2026-11-02).
  - A settle refuses, rather than rewrite, an identity block that shares its line with other text.
  - The crash-leftover rule moved into the shared check (`CaseCrawl.stoppedSettle`), so the page and
    the command line agree.
  - Backups are completed under a temporary name before they are named `.before`.
  - A push with no known package is reported.
  - New pins: the survey's refused-case-file status and its unreadable-capture lines; exact token
    matching; no push without `a2h.push`; handled-screen expansion in ReExpand; a failing adb push.
  - SKILL.md's resume-refusal text now says exactly what is refused.
