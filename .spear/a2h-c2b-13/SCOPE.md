# SCOPE

Bead: `a2h-c2b.13`, *Operator settles an identity case and resumes: origin marking, never
overwrite, re-validate on resume*. P2 feature under epic `a2h-c2b`. It was split from `a2h-c2b.3`
at that cycle's Gate 1, and its blocker `a2h-c2b.15` (lossless Stop and resume) is closed.

Branch: `feat/operator-settles-identity-cases`, cut from `epic/structured-screen-identity` at
`e9c6fe5`. The PR targets the epic branch, not `main`.

**Revised at Gate 1 (owner challenge, 2026-10-16).** The first draft re-checked every open case at
resume. The owner's direction replaces that:
- Whether an operator's identity holds is checked **offline**, against the captures already in the
  crawl folder.
- The **tool** writes a holding identity into both files and marks the case **handled**.
- The **resume** revisits only the handled screens, confirms them live, closes their cases, and
  carries on along their paths.
- The operator handles **only the cases they choose**.

**Second revision at Gate 1 (owner decision, 2026-10-16).** The operator works a case in a
**case page**: a local web page the tool serves. It shows the screen's XML beside a traits editor,
with a *Check Traits* button and a *Settle + Push* button. The screenshot of the case's screen is
split to the sibling bead `a2h-c2b.23`, which this bead blocks.

**Third revision at Gate 1 (owner decision, 2026-10-16).** The workflow starts at `-Crawl <dir>`.
That command starts the server and opens a **case overview** linking to every case, so the
operator picks a case from the page rather than on the command line. The text survey stays,
under `-Survey`.

## Goal

Feature: an operator settles the identity cases **they choose**, offline, in a case page served by
the `validate-screen-identity` tool.
- Settling a case means the operator writes, in the page, the traits they want the screen to
  assert, and checks them until they hold.
- The tool checks them against the crawl's own captures. When they hold, on that screen and on no
  other, it writes them into the screen's `.xml` **and** `.html` as one identical, valid identity,
  stamped as the operator's, and marks the case **handled**.
- On resume, the crawler revisits each handled screen. If the identity holds on the live screen,
  the case is **closed**, and the crawl continues along that screen's paths.
- Cases the operator did not handle are left exactly as they are.
- Every settled identity records who settled it, the crawler or the operator. No crawler path ever
  replaces an operator's identity.

## Audience

The operator running crawls on complex third-party apps. Since `a2h-c2b.3`, a screen whose identity
does not hold pauses the crawl on a case, and the pause says to Stop, settle and resume. Today:
- **There is no way to settle a case.** The only route is hand-editing two files that must agree.
  - A typo in `<traits>` makes the resume skip the whole screen without a word.
  - An edit to only one of the two files is overwritten later.
- **A resume never closes a case**, even when the screen is now correctly settled.
- **Nothing records who settled an identity.**

## Why now

- `a2h-c2b.3` raises, records and reloads cases. It wrote down the closing contract this bead builds
  on: set `status="closed"`, keep the case as history, and drop the pointers.
- `a2h-c2b.15` made Stop and resume lossless, so Stop at a case is now a safe operator path.
- The tool already answers "does this identity hold here, and only here?" against a crawl folder,
  with the crawler's own code. Only the write step and the case status are missing.

## What an operator does, after this cycle

```
crawl pauses on case_000 (Battery) ─► Stop ─► pull the crawl
     ─► validate-screen-identity.ps1 -Crawl <dir>
           the browser opens the case overview: every case, with its status, kind, screen and
           reason; open and handled cases link to their case page
     ─► pick case_000 ─► the case page for Battery:
           left: a traits editor with a template and examples; right: the screen's XML
     ─► write traits in the editor ─► [Check Traits]
           ├─ does not hold ─► the page says why; nothing is written; edit and check again
           └─ holds ────────► [Settle + Push] appears
     ─► [Settle + Push] ─► traits written to screen_00009's .xml and .html (settled-by="operator"),
                           case_000 marked "handled", the 3 changed files pushed to the device;
                           refused, with nothing written, if the device copy changed since the pull
     ─► back to the overview: case_000 now shows "handled"; pick another case, or
     ─► [Done] ─► the tool exits
     ─► Resume
           ├─ case_001 (SIMs), not handled ─► left open, untouched, never re-checked
           └─ revisit screen_00009 ─► identity holds live ─► case_000 closed, pointer gone,
                                      crawl continues along Battery's paths
                                 └─► does not hold live ─► case_000 back to open, crawl pauses on it
```

## Scope calls for Gate 1. Please confirm or change each.

Each call has a recommendation, and the rest of this SCOPE assumes it. The calls marked **(owner)**
come from the 2026-10-16 challenge. I wrote them down as I understood them, so please check that I
read you right.

**1. Holding is checked offline, by the tool. (owner)**
- The test is the crawler's own two-way rule. It composes `TraitEvaluator` and
  `IdentitySettlement`, and re-derives nothing.
- Against the crawl folder's saved captures, the operator's identity must:
  - hold on its own screen's capture;
  - be the **only** settled identity that holds there (forward direction);
  - hold on **no other** screen's capture (reverse direction).
- A capture that cannot be read back is never treated as a non-collision. It refuses the settle and
  names the screen.

**2. The tool writes the identity; the operator does not hand-edit screen files. (owner)**
- The engine has three operations: **check** (call 1), **settle** (write) and **push** (call 3).
  The case page (call 9) calls them. So does a scriptable command line, `-Settle <case-id>
  -Traits <file> [-DryRun] [-Push]`, where the file holds a `<traits>` block in the identity
  syntax. The page and the command line run the same code, so they cannot give different verdicts.
- The traits the operator writes carry **no stamp**: they say *what* the screen asserts, and the
  tool records *who*. Traits that carry a stamp are refused.
- Settle writes only when the identity holds, and it re-checks first, so an edit made after the
  last check cannot slip through. It then writes the screen's `.xml` and `.html` through
  the crawler's **one** identity producer, so the two files cannot disagree. It also marks the case
  `handled` in `identity-cases.xml`.
- Settle replaces **only** the identity block: `<screen-identity>` in the `.xml`, and the
  `<script id="screen-identity">` block in the `.html`. Every other byte of both files stays the same.
- When the identity does not hold, it writes **nothing** and says why, in the same words the
  survey uses.
- Check (`-DryRun` on the command line) never writes.
- Every write goes through a temporary sibling file. Both temporary screen files are read back and
  must parse to the **same** identity, and only then are all three files swapped into place.
- This **reverses** the tool's current "never writes to your captures" rule, deliberately and
  only for settling a case. Every other mode stays read-only.
- **What may be settled:**
  - A `handled` case may be settled again, which replaces the traits it was handled with: it has
    not been confirmed yet.
  - A `closed` case is refused.
  - A settle may **replace** the traits of the case's raised-on screen. That is how a
    "settled identity does not hold" case is fixed. The case file records the identity it
    replaced, so the replacement is visible and never silent.

**3. Push sends the change to the device, safely. (owner)**
- Push copies exactly the three changed files back to the device crawl folder, with `adb`.
- Before writing anything, it checks that the device copies of those files are still the ones the
  operator pulled, by comparing hashes.
- If the device moved on since the pull, it refuses and writes nothing, either locally or on the
  device. A settle must never clobber newer crawl state.
- After pushing, it hashes the device copies again and reports a mismatch.
- The compare-and-refuse decision is a unit-tested Kotlin function. `adb` runs behind a small
  interface: a fake in tests, and the real `adb` on the operator's machine.
- Settle while the crawl is stopped. The hash check catches changes made before the push, but not
  a crawl writing to the folder during it. The case page and `SKILL.md` both say so.

**4. A new case status: `open → handled → closed`. (owner)**
- **`handled`** means an operator settled it and the tool confirmed it holds offline. The screen's
  pointer stays, because the case is not finished.
- **`closed`** means the resumed crawl confirmed it on the live screen. The case stays in the file as
  history, and every pointer to it is removed immediately.
- A case the operator does not touch stays `open`. Nothing re-checks it at resume. **The operator is
  never required to handle every case.**

**5. On resume, handled screens are revisited and confirmed live. (owner, with recommendations on
the details)**
- Every handled case's raised-on screen joins the resume frontier, even a screen the first run had
  already finished expanding.
- **Revisiting** means replaying to the screen, or re-scanning it for the root. The replay-arrival
  check then runs with the operator's identity, which is the check that already exists.
- **Holds live:** the case is closed. A screen that was not yet expanded, the usual case after a Stop
  at the pause, is then expanded, so the crawl continues along its paths. A screen that was already
  expanded is not expanded again, because its paths were already crawled.
- **Does not hold live (recommended):** the case returns to `open` with the live reason in its
  detail, and the crawl **pauses** on it. An operator who just asserted something wants to know at
  once that the app disagrees.
- **Replay fails before reaching the screen,** for a route reason rather than an identity one: the
  case stays `handled`, and the existing replay-failure path runs. The next resume tries again.

**6. Origin marking, on the `<traits>` block. (Recommended.)**
- It is written as `<traits settled-by="machine">` (the crawler's settled proposals) or
  `<traits settled-by="operator">` (written by `-Settle`).
- An identity with no traits is unsettled, so it carries no stamp, and "settled by someone, but
  asserting nothing" cannot be written.
- The XML and HTML carry it alike. It takes part in identity equality and in the existing check that
  the two files agree.
- The case file's rejected proposal carries no stamp, because it was never settled.
- A `<traits>` block with no stamp, or an unknown one, is **refused**, naming the file. Only the
  crawler and `-Settle` write traits, and both stamp them, so a missing stamp means a hand edit.
- *Cost:* a crawl saved by the `a2h-c2b.3` build has unstamped machine traits, so it will not
  resume until it is crawled again. No backward compatibility is required (`CLAUDE.md`).

**7. "Never silently overwritten" is pinned as guarantees. (Recommended.)**
1. **No crawler path replaces or re-stamps a recorded identity.** That covers rewrites, the root
   re-scan, links, re-expansion, the live revisit and case closing. An operator identity survives a
   whole resume byte for byte, in both files.
2. **Safety nets for hand edits**, which are still possible even though `-Settle` is the supported
   path:
   - A resume over a screen whose identity is **refused** fails loudly, naming the file and the
     reason, instead of skipping the screen in silence.
   - A resume over a screen whose `.xml` and `.html` identities **disagree** fails loudly, naming
     both files and the difference, instead of letting a later rewrite overwrite one of them. This
     reuses the tool's existing agreement check.
   - Both refusals show in the saved-crawl panel, as case-file refusals do today. This reverses the
     "skip, don't crash" choice in `ScreenXmlReader`, but only on the crawler's resume path and only
     for identity.
3. **Out of scope:** a screen XML that is not readable XML at all is still skipped, as today. A
   follow-up bead is filed for it.

**8. `-Crawl` opens the case overview; the text survey moves to `-Survey`. (owner, with the
`-Survey` switch recommended)**
- `validate-screen-identity.ps1 -Crawl <dir>` starts the tool's server (call 9) and opens the **case
  overview** in the browser.
- Today `-Crawl <dir>` prints a text survey with an exit code, and scripts and agents rely on that.
  It is kept unchanged as **`-Crawl <dir> -Survey`**. That form gains a cases section listing each
  case with its status, screen and detail, and for a handled case it says the resume will confirm
  it live.
- The overview and the text survey are built from the same data, so they cannot disagree.
- The single-screen report names the identity's origin.

**9. The case overview and case pages, served by the tool. (owner)**

The flow: `validate-screen-identity.ps1 -Crawl <dir>` opens the case overview, and the operator
picks a case from it.

*How it runs.* A page opened from disk cannot run checks, so the tool serves it:
- The script picks a free port and a one-time token, then starts the tool's existing JVM entry
  point in the background.
- The entry point loads the crawl folder **once** and serves the page with the JDK's built-in HTTP
  server. There are no new dependencies.
- The script waits until the server answers, then opens
  `http://127.0.0.1:<port>/?token=<token>` in the default browser.
- Gradle starts once, so every *Check Traits* after the first answers immediately.

| Route | Does |
|---|---|
| `GET /` | the case overview |
| `GET /case/<id>` | the case page for one case |
| `POST /case/<id>/check` | check (call 1), returned as JSON: the verdict, the reason, and the failing traits |
| `POST /case/<id>/settle` | re-check, then settle, push, or both (calls 2 and 3); returns what was written and pushed, or why nothing was |
| `POST /done` | stops the server; the script exits |

*The overview* lists every case in the crawl, sorted by id. Each row shows:
- the id, the status, the kind, the raised-on screen and its name, and the case detail.

How a row behaves depends on the status:
- **Open** and **handled** cases link to their case page. A handled case also says it will be
  confirmed live on resume.
- **Closed** cases are listed as history, with no link.

The overview's header names the crawl folder, and whether a device is connected for push. The
overview reflects every settle made in this run: a case settled a moment ago shows `handled`. When
the crawl has no cases, the overview says so.

*Safety:*
- The server binds to `127.0.0.1` only.
- Every route needs the token, so no other page in the browser can call it.
- It writes only inside the crawl folder it was given.
- It stops on *Done*, on Ctrl+C, or after an idle timeout. The timeout's length lives in code.

*The case page* is a self-contained HTML page, with inline CSS and JavaScript and no external
libraries. The overview is built the same way. The case page has:
- A link back to the overview.
- **Header:** the case id, kind, raised-on screen, its name and the case detail. For a uniqueness
  case, the related screens are listed too.
- **Left:** a traits editor. It is pre-filled with the rejected proposal when there is one, plus
  commented-out `has-list`, `has-control` and `lacks-control` examples. For a "settled identity does
  not hold" case, it is pre-filled with the screen's current identity instead.
- **Right:** the screen's XML: the identity block, then the first viewport's tree, collapsible.
  Each pressable element has a *copy as has-control* action that puts the element in trait syntax
  on the clipboard.
- ***Check Traits*** shows the verdict in the page, in the same words as the command line.
- ***Settle + Push*** appears only after a passing check of the editor's **current** text, and
  disappears when the text is edited again. Its result is shown in the page: the files written,
  the files pushed, or why nothing was.
- When no device is connected, the page offers ***Settle*** alone and says the files still need
  pushing.

*What is tested where.* The routes are unit-tested against the real JDK server on localhost:
- the token is required;
- the verdicts equal the engine's;
- settle refuses when the identity does not hold;
- the overview and the case pages are served with the crawl's data.

The pages' look and behaviour in a browser are checked by hand at Gate 3.

## Affected surface

- **New (main):**
  - the offline settle decision (call 1);
  - the settle writer, which writes the `.xml`, `.html` and case file together (call 2);
  - `IdentityOrigin` on `ScreenIdentity`;
  - the `handled` case status.
- **Modified (main):**
  - `ScreenIdentityXml`: reads and writes the stamp.
  - `AccessibilityXmlSerializer` and `HtmlRenderer`: one producer for both files.
  - `CaptureIdentitySource`: agreement covers origin, and the check is shared with the loader.
  - `IdentityCase` and `IdentityCaseStore`: the `handled` status, and the replaced identity.
  - `IdentityCasePointers`: pointers for open and handled cases.
  - `SavedCrawlLoader`: the loud refusals.
  - `CrawlRunTracker`: closing and reopening cases become production paths.
  - `DeepCrawlCoordinator`: the machine stamp, handled screens in the resume frontier, closing or
    reopening at the live revisit, and the root re-scan.
  - `SavedCrawlRepository`: shows the new refusals.
- **Tool and documentation:**
  - New: the case-page server and its routes (call 9). The page is shipped as a resource of the
    tool.
  - New: the `adb` interface and the push decision (call 3).
  - `ScreenIdentityReport` and the tool's entry point: settle, check, push, the case-page mode, and
    the survey's cases section.
  - `validate-screen-identity.ps1`: `-Crawl` now opens the overview, and the text survey moves to
    `-Survey`. New switches: `-Settle`, `-Traits`, `-DryRun` and `-Push`. The script also starts the
    server and opens the browser.
  - `app/build.gradle.kts`: the new `a2h.*` properties forwarded to the entry point.
  - The skill's `SKILL.md`, and `documentation/`.
- **Not modified, deliberately:**
  - `TraitEvaluator` semantics, `TraitProposer`, the `IdentitySettlement` decision, every
    `ScreenIdentityPolicy`, and `ScreenRecognition`.
  - Entry restore, destination settling, and the click and back paths.
  - The case **raising** rules from `a2h-c2b.3`.
- **Breaking:**
  - `<traits>` needs the stamp.
  - The case file gains the `handled` status and the replaced identity.
  - The tool can now write, but only when settling a case.

## Inputs

- [x] Bead: `bd show a2h-c2b.13`, with its description and case-storage note (closing contract)
- [x] Owner challenge at Gate 1, 2026-10-16: check offline, the tool writes both files, a `handled`
      status, the resume revisits handled screens and closes their cases, and the operator handles
      only chosen cases
- [x] Owner decision at Gate 1, 2026-10-16: the case page and its local server go in this bead,
      and the screenshot goes to sibling `a2h-c2b.23`
- [x] Owner decision at Gate 1, 2026-10-16: `-Crawl` starts everything and opens a case overview
      that links to every case
- [x] The parent cycle: [`.spear/a2h-c2b-3/SCOPE.md`](../a2h-c2b-3/SCOPE.md) (calls B, C, D and F)
      and [`PR.md`](../a2h-c2b-3/PR.md)
- [x] The blocker, closed: [`.spear/a2h-c2b-15/`](../a2h-c2b-15/)
- [x] The operator tool: [`.claude/skills/validate-screen-identity/SKILL.md`](../../.claude/skills/validate-screen-identity/SKILL.md)
- [x] The current Settings crawl, `E:\Logs\com.android.settings\crawl-20261016_130954`: 19 screens,
      with `case_000` (Battery) and `case_001` (SIMs) open
- [x] Governing values: `factory/COMPANY.md` (safety over coverage; evidence over assertion) and
      `factory/FUNCTION.md` (red→green; deterministic transforms; pins found by behaviour)

## Constraints

- **One fact, one home.**
  - Holding is decided only by `TraitEvaluator` and `IdentitySettlement`. The settle decision
    composes them.
  - The identity block has one producer, and the crawler and settle both write through it.
  - The `.xml`/`.html` agreement rule stays in `CaptureIdentitySource`.
  - The case page and the command line call the same check, settle and push. The page computes no
    verdict of its own.
- **Deterministic.** The same folder and the same traits file always give the same verdict and
  byte-identical written files, whatever order the screens are listed in.
- **Never a silent overwrite.**
  - Settle writes only after the identity holds, and records any identity it replaced.
  - Push refuses when the device copy changed since the pull.
  - No crawler path rewrites an operator identity.
- **Safety unchanged on device.** The revisit uses the existing replay and arrival path. It adds no
  new click, back or relaunch path.
- **Tests:** unit tier only, with synthetic `AccessibilityNodeSnapshot` trees and the committed
  real-capture fixtures. No Android framework mocks.
  - The case-page server is tested in-process against the JDK server on localhost.
  - The real `adb`, and the page as seen in a browser, are checked by hand at Gate 3.
- **Pre-existing tests** stay unmodified, except those that pin an unstamped `<traits>` block, the
  silent skip that call 7 reverses, the tool's read-only promise, or exact serialized bytes that now
  carry the stamp. Each one is found by the behaviour it asserts, then listed and justified at Assess.
- **Type-check:** strict. `./gradlew assembleDebug` compiles with no new warnings.
- **PR size:** no hard cap, and it is not graded. It is expected to be medium to large.

## Done means

### Machine-checked (graded in the Assess loop)

**Build**
- [ ] `./gradlew test` is green at the final artifact state.
- [ ] `./gradlew assembleDebug` is green at the final artifact state.

**Offline settle (1, 2)**
- [ ] A test asserts that settling an open "proposal does not hold" case with traits that hold two
      ways writes the identity into **both** files. The two files parse to equal identities, stamped
      `operator`, and the case becomes `handled`.
- [ ] Tests assert that the settle **writes nothing**, leaving all three files byte-identical, and
      names the reason, for each of these:
      - the traits do not hold on the screen's own capture;
      - another settled identity holds on that capture (forward direction);
      - the traits hold on another screen's capture (reverse direction);
      - the traits assert nothing;
      - a known capture cannot be read back;
      - the case is `closed`;
      - the case id is unknown;
      - the traits file is malformed.
- [ ] A test asserts that `-DryRun` reports the same verdict and writes nothing.
- [ ] A test asserts that a `handled` case can be settled again, replacing its traits.
- [ ] A test asserts that settling a "settled identity does not hold" case replaces the screen's
      identity, and records the replaced identity on the case.
- [ ] A test asserts that the settle verdict equals the crawler's own two-way check on the same
      folder, so the tool and the crawler agree by construction.
- [ ] A test asserts determinism: the same folder and traits file, with screens listed in two orders,
      give the same verdict and byte-identical files.
- [ ] A test asserts that the decision about pushing refuses when any device-side hash differs from
      the pulled copy, and allows the push only when all three match. With a fake `adb`, a refused
      push writes nothing locally and pushes nothing.
- [ ] A test asserts that settle replaces only the identity block: apart from that block, both
      screen files stay byte-identical.

**Case page and server (9)**
- [ ] A test asserts that every route refuses a request without the right token, and that the
      server is bound to `127.0.0.1`.
- [ ] A test asserts that `GET /` serves the overview with every case. Open and handled cases link to
      their page, closed ones do not, and a crawl with no cases says so.
- [ ] A test asserts that after a settle in the same run, the overview shows that case as `handled`.
- [ ] A test asserts that `GET /case/<id>` serves the case page carrying the case's id, kind, screen
      and detail, the screen's XML, and the editor pre-fill: the rejected proposal, or, for a
      "settled identity does not hold" case, the current identity. An unknown or closed case gets a
      page saying so, not an editor.
- [ ] A test asserts that `POST /check` returns the same verdict and reason as the command-line check
      for the same traits, across holds, does not hold, not unique and asserts nothing.
- [ ] A test asserts that `POST /settle` re-checks: it writes nothing for traits that do not hold,
      even if a passing check came first. For traits that hold, it writes and reports the files.
- [ ] A test asserts that `POST /done` stops the server.

**Status and pointers (4)**
- [ ] A test asserts that `handled` round-trips through the case file and the loader, and that a
      handled case's screen keeps its pointer.
- [ ] A test asserts that the loader accepts pointers to open and handled cases, and still refuses a
      pointer to a closed or missing case.
- [ ] A test asserts that cases left `open` are untouched by a resume: the same status, detail and
      bytes.

**Resume revisits handled screens (5)**
- [ ] **Red→green:** a test through the coordinator asserts that a resume over a handled case
      revisits its screen and, when the identity holds live, **closes** the case. The case stays in
      the file, and the pointer is gone from every involved screen's XML. It **fails on pre-change
      code**, where the case is never closed. Both runs are archived.
- [ ] A test asserts that a handled screen that was not yet expanded is expanded after the close,
      and one that was already expanded is revisited and closed **without** being expanded again.
- [ ] A test asserts that a handled screen whose identity does **not** hold live returns the case to
      `open` with the live reason, and pauses the crawl.
- [ ] A test asserts that a handled root is confirmed at the resume re-scan.
- [ ] A test asserts that a replay that fails before reaching the handled screen leaves the case
      `handled`.
- [ ] A test asserts that, after the close, a revisit whose tree the operator identity holds on
      **links** to the screen.

**Origin (6)**
- [ ] A test asserts that a crawler-settled screen is written `settled-by="machine"` in both files,
      and that an unsettled screen has no `<traits>` block.
- [ ] A test asserts that `operator` round-trips: write, then the loader, then a byte-identical
      rewrite of both files.
- [ ] A test asserts that a missing or unknown stamp is refused, naming the stamp, by both the tool
      and the resume.
- [ ] A test asserts that files differing **only** in origin are reported as disagreeing.
- [ ] A test asserts that the case file's rejected and replaced identities are written with no stamp,
      and round-trip.

**Never overwritten (7)**
- [ ] A test through the coordinator asserts that an operator identity survives a whole resume byte
      for byte, in both files. That resume includes a re-scan, an expansion, a link and a close.
- [ ] **Red→green:** a test asserts that a resume over a screen whose `<traits>` is refused **fails,
      naming the file and the refusal**. It **fails on pre-change code**, which skips the screen.
      Both runs are archived.
- [ ] **Red→green:** a test asserts that a resume over an `.xml` and `.html` that disagree **fails,
      naming both files and the difference**. It **fails on pre-change code**. Both runs are archived.
- [ ] A test asserts that the saved-crawl panel shows each new refusal as its load error and does not
      crash.

**Survey and overview (8)**
- [ ] A test asserts that `-Survey`'s text lists every case with its status, screen and detail, and
      that a handled one is marked as confirmed live on resume. Its screen lines and exit status
      stay as today.
- [ ] A test asserts that the single-screen report names the origin.
- [ ] A test asserts that `-Crawl` without `-Survey` takes the overview path, and `-Crawl -Survey`
      takes the text path. These two forms are the script's argument contract, and they are pinned
      in the entry point's mode selection.

**Pins bite (each mutation applied, shown red, archived, then reverted)**
- [ ] (1) The settle check skips the reverse direction. (2) It skips the forward direction. (3)
      `-Settle` writes when the identity does not hold. (4) The settle writes the `.xml` but not the
      `.html`. (5) The resume closes a handled case without the live check. (6) The resume touches an
      `open` case. (7) The loader goes back to skipping a refused identity. (8) A missing stamp is
      read as `operator`. (9) The push decision ignores a hash mismatch. (10) The server skips the
      token check. (11) `POST /settle` trusts the earlier check instead of re-checking.

**Hygiene**
- [ ] No commented-out code, no `TODO` added, and no debug output. Every new public type and function
      has a KDoc contract.
- [ ] The skill's `SKILL.md` describes the process above: the case page first, then the scriptable
      `-Settle -Traits`, `-DryRun` and `-Push`.
      `documentation/crawler-module.md` and `documentation/data-and-state.md` point to it.
- [ ] The follow-up bead for unreadable screen XML is filed.

### Human-verified at Gate 3 (real device; evidence under `.spear/a2h-c2b-13/evidence/`)

- [ ] On the Settings crawl, run `-Crawl` and see the overview list `case_000` and `case_001`. Then
      open `case_000` (Battery):
      - Check a failing trait set first, and see the reason in the page.
      - Then check a holding one, and use *Settle + Push*.
      - Return to the overview and see `case_000` as `handled`.
      - Leave `case_001` (SIMs) alone, and resume. Show:
      - `case_000` going `handled`, then `closed`;
      - the pointer gone;
      - `settled-by="operator"` kept in both files after the resume;
      - Battery's paths crawled;
      - `case_001` untouched and still `open`.
- [ ] A push from the case page refused because the device crawl changed after the pull. The page
      says why, and nothing is written on either side.
- [ ] The case page judged by eye: it reads well, *copy as has-control* puts working trait syntax on
      the clipboard, *Settle + Push* disappears once the text is edited after a passing check, and
      *Done* ends the tool.
- [ ] One hand-edit mistake on device, either an unstamped `<traits>` or an `.xml` and `.html` that
      disagree. The saved-crawl panel names the file, and nothing is skipped or overwritten.

## Non-goals

- Handling a case without an identity that holds, such as dismissing or force-closing it.
- Re-checking, at resume, cases the operator did not handle, or hand edits to screens no case
  involves. These are still caught lazily: a settled identity that fails
  raises a case at replay arrival, and one that is not unique raises a collision at recognition.
- Merging two screens through a case (deciding that a collision was a revisit).
- Re-proposing a machine identity that stopped holding.
- Carrying identities across crawls (`a2h-c2b.14`).
- Unreadable screen XML (follow-up bead).
- A screenshot of the case's screen (`a2h-c2b.23`, blocked by this bead).
- Proposing traits for the operator in the page. The page offers examples and copy actions, and
  the operator writes the traits.
- Editing more than one crawl folder per run.
- Any UI in the Android app beyond the existing pause card and progress line.
- `a2h-c2b.16` to `.22`.

`MAX_ROUNDS = 10`

Rounds are Assess/fix iterations: a circuit breaker, not a budget. If the same defect survives two
consecutive rounds, stop and escalate, however many rounds remain.
