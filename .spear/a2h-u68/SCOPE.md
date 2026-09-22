# SCOPE

Bead: `a2h-u68` — *Validate whether a screen fingerprint holds, against a live screen or a second
capture* (P2, feature; parent epic `a2h-c2b`; depends on closed `a2h-c2b.2` and builds on closed
`a2h-c2b.4`; blocks `a2h-c2b.3`; absorbs `a2h-c2b.5`)

Branch: `feat/validate-screen-identity`, cut from `epic/structured-screen-identity` at `21ec46e`.
PR targets the epic branch, not `main`.

> Rewritten from scratch on 2026-09-16. The previous SCOPE (pre-redesign: three matchers, inert
> hand-edited strings, an "authority table") is superseded in full and is not carried forward.
> It also named a route-step XML element that `a2h-c2b.2` renamed — that stale reference is gone
> with it, which is all `a2h-c2b.5` asked for.
>
> **Revision 2 (Gate 1 feedback, 2026-09-16):** the screen identity, with all its content, lives
> **inside each screen's own XML and HTML** — not in a separate file. Scope calls A and B are
> rewritten accordingly; A2–A4 are new questions that decision raises.

## Goal

Feature: every captured screen carries its **whole identity** — name, element set and traits —
inside its own XML and HTML, and one command tells an operator whether that identity **holds**: does
it match the screen in front of it, and does it match **only** that screen among the crawl's known
screens — against a live emulator screen or a capture on disk, using the crawler's own matching code,
and printing what the screen actually shows wherever the answer is not a clean match.

## Audience

The operator settling fingerprints by hand on complex third-party apps. In the target model this is
not a diagnostic bolted onto a working mechanism: the machine proposes, and where a proposal does not
hold **a human settles it — by editing the screen's identity in place and checking it with this
tool**. It is a component of the redesign.

Secondary: `a2h-c2b.3` (propose / validate / settle). Its open questions — what "holds" should mean on
a dense screen, whether uniqueness is achievable there, how often an operator gets pulled in — are
to be answered from the evidence this cycle produces, not designed in the dark.

## Why now

`a2h-c2b.2` gave a screen one structured identity and named comparison policies; `a2h-c2b.4` gave that
identity **traits** — assertions checked against a tree — and a known-screen evaluator that returns
zero, one or more than one match. Neither is usable by a person yet:

- **The identity is not in the artifacts.** A screen's XML carries only the name half in
  `<screen-identity>`; `SavedCrawlLoader` states the content half is not recoverable. The HTML carries
  none of it. Traits have no on-disk form at all.
- **There is no way to run them against a real screen.** Both policies and the trait evaluator are
  exercised only by synthetic unit trees. Nothing turns a capture back into the tree they need.

Owner decisions 2026-09-16: this bead pulls forward a minimal on-disk form for screen identities
including traits, designed against this real consumer; and that form lives **in the screen's own XML
and HTML**. `a2h-c2b.3` extends it later (the crawler checking it, operator cases, canonical trait
order).

## What the command answers

Given a **target screen** (its XML + HTML, carrying the identity), the **known screens** (the other
screens of the same crawl, or a folder of captures), and an **observed screen** (live, or a capture on
disk):

| # | Question | Produced by (production code, unmodified in behaviour) |
|---|---|---|
| 1 | Do the target's traits hold on this screen? — *holds / does not hold / unsettled* | `TraitEvaluator.verdict` |
| 2 | Is the target the **only** known screen whose traits hold? — *one / none / ambiguous (all listed) / unsettled screens listed* | `TraitEvaluator.matchKnownScreens` |
| 3 | Does the target's element set match, and how many known screens' element sets match? — *reason, missing, extra* | `SameScreenPolicy.compare` |
| 4 | Can each of the target's elements be re-clicked? — *resolved / ambiguous / unresolved*, independent of 1–3 | the live click-fallback candidate collector + `ClickFallbackMatcher.selectMatches` |
| 5 | Why might it fail to hold? — weak identities (no resource id **and** blank label) and collisions (distinct pressables sharing one fingerprint), on both sides | `AccessibilityTreeSnapshotter.collectPressableElements` + `ElementFingerprint` |
| 6 | What should I paste? — for everything missing, unresolved or ambiguous, the observed screen's actual elements in the identity block's own syntax | the identity block format below |

The headline outcome (scope call **C**): **HOLDS** · **NOT_UNIQUE** · **DOES_NOT_HOLD** ·
**UNSETTLED** · **INVALID_INPUT** — with the element-set answer (3) always reported beside it, never
blended into it.

## Scope calls for Gate 1 — please confirm or change each

Each has a recommendation; the SCOPE is written assuming it.

**A. The identity lives in the screen's XML, in the existing `<screen-identity>` element. (Decided by
owner; placement recommended.)**
`<crawl><screen-identity>` already holds the name half (package, title, disambiguators). It grows to
hold the whole identity: a `root-class` attribute, the element set as `<element label resource-id
class list-item checkable editable back>` children — **exactly** the shape the route-step
expectations already use, read and written by the **same** code (extracted, not copied) — and a
`<traits>` block (`has-list`, `has-control`, `lacks-control`). The crawl-root flag is the existing
`is-root` on `<crawl>`. Every writer of a screen's XML writes it: the crawl (first capture and every
rewrite) and `capture-screen` snapshots. What is written at capture is the identity the crawler
already holds — first-viewport elements + name — with **no traits** (proposing traits is
`a2h-c2b.3`). The operator adds traits by hand.

**A2. In the HTML: the same identity block, byte-identical, embedded as a data block. (Recommended.)**
`<script type="application/xml" id="screen-identity">…</script>` in the page head carrying exactly the
text of the XML's `<screen-identity>` element. One syntax, so an operator pastes the same text into
both files and a reader parses both with the same code. The alternative — a visible HTML rendering
(e.g. a `<section>` of `data-*` attributes) — is friendlier in a browser but is a second syntax for
the same fact, with its own parser and its own way to drift.

**A3. XML and HTML must agree; disagreement is INVALID_INPUT naming the difference. (Recommended.)**
Two copies of one fact can drift — an operator edits one file and forgets the other. Picking one as
authoritative would silently ignore an edit made to the other. Refusing loudly, with the differing
elements or traits named, is the guardrail. A missing HTML sibling is also INVALID_INPUT.

**A4. Hand edits survive the crawler's rewrites — and the crawler acts on them. (Revised at Gate 1,
2026-09-18, on owner objection: an identity that survives but that nothing reads is inert, which is
the very failure this epic exists to end.)**

*Why the crawler wipes it today:* it is not a decision, it is an absence. `SavedCrawlLoader` blanks
the content half with the comment *"not recoverable from the artifacts"* — nothing ever wrote it.
Scope call A writes it, so the reason disappears. Three parts follow:

**A4a — Load the whole identity.** `SavedCrawlLoader` reads the persisted name, element set **and**
traits into the screen record instead of blanking the content half, and every rewrite writes back
what the record holds. Edits to a paused crawl survive resume.

**A4b — One decision already improves for free.** The record's content half feeds the debug manifest
and the graph JSON (`ScreenIdentityCodec.encodeContent`). Today a *resumed* crawl writes an **empty**
`replayFingerprint` into both, because the loader blanked it. Loading it for real fixes that with no
behaviour risk; pinned below.

**A4c — The settled identity decides at replay arrival.** One named site: route-replay validation in
`DeepCrawlCoordinator`, which today compares the **name half only**. It gains a second condition —
the record's **traits must hold** on the screen the replay landed on. Three properties make this the
right site and a safe change:

- **Traits are what belongs here, and the element set is not.** That site's own comment records why
  it is name-only: *a screen's content legitimately churns between visits, but what it is called does
  not.* A byte-equal element set is exactly the brittleness the epic is removing. A trait is
  churn-tolerant by construction — `has a list with ≥ 3 rows carrying these ids` survives a row
  appearing. So this adds the one signal that is both stronger than the name and stable under churn.
- **Opt-in by construction — zero change to existing crawls.** An identity with no traits is
  `UNSETTLED`, and the trait condition is skipped entirely: behaviour byte-identical to today.
  Nothing proposes traits automatically (that is `a2h-c2b.3`), so the only identities carrying traits
  are ones an operator deliberately settled. No machine-proposed identity can newly fail a replay.
- **It degrades into an existing path, not a new mechanism.** A non-holding trait yields the same
  `ScreenPreparationResult.Failure` → `handlePreparationFailure` recovery a name divergence already
  takes. No case queue, no pause, no new state — `a2h-c2b.3` replaces that path with cases later.

This is what makes the tool honest: the question the operator settles by hand is now *the same
question the crawler asks at replay*, checked by the same `TraitEvaluator` call. Without A4c the tool
certifies something no crawl consults.

Still out, unchanged: dedup reading traits, proposing traits, operator cases, and editing a screen's
files **while** a crawl is running (the in-memory record wins) — all `a2h-c2b.3`.

**B. The capture is the proposal; the tool never writes. (Replaces the earlier "emit a proposed
document".)** Because every capture now writes its identity (A), there is nothing extra to emit. The
tool writes only its report, to its own output directory; paste text is printed in the identity block
syntax (A2), ready for both files.

**C. "Holds" is the trait answer; the element-set answer is reported beside it. (Recommended.)**
HOLDS = the target's traits hold **and** `matchKnownScreens` returns exactly the target. NOT_UNIQUE =
they hold but another known screen's do too. UNSETTLED = the target asserts nothing present (no
traits, or negations only) — which is what every fresh capture is until traits are added. Element-set
matching is today's replay rule; the operator needs to see both, but a settled identity is its traits,
so they are not combined into one score.

**D. Both answers are evaluated against the first viewport (scroll step 0). (Recommended.)**
That is the tree every production identity is built from (`stepSnapshots.first().root`), so the
observed side is the same kind of thing the crawler sees on arrival. Consequence stated up front: a
`has-control` for a control below the fold does **not** hold. The alternative — traits against the
scroll-merged tree — would let the tool certify an identity the crawler cannot observe without
scrolling.

**E. Back-affordance counting gets one named home. (Recommended.)**
The rule `countBackAffordances = !isRootScreen` is written inline at **seven** `DeepCrawlCoordinator`
call sites — four building `SameScreenPolicy`, two building `NavigatedAwayPolicy`, one passed to
`ScreenIdentityCodec.encodeLogical`. The tool needs the same rule, keyed off `is-root`. Rather than
restate it, extract it into one named factory on the policy and point both the crawler sites and the
tool at it — behaviour-preserving, pinned by the existing suite **plus** an injected mutation shown
red.

**The eighth site is a deliberate exception and stays literal.** `SameScreenPolicy(countBackAffordances
= true)` at the route-step destination comparison is `true` *on purpose*: the screen under comparison
there is the destination child, which is never the crawl root, and its comment records that deriving
it from the parent loosened that comparison for depth-1 children — a tolerance change this SCOPE
forbids. Sweeping it into the factory would silently reintroduce that regression, so the factory is
applied to the seven derived sites only, and a `Done means` pin holds that site at `true`.

**F. Host-side, on the JVM, wrapped by a skill; the live path calls `capture-screen`. (Recommended.)**
One command in a new `.claude/skills/validate-screen-identity/` skill. With `-Observed <capture>` it
needs no device; with `-Live` it runs the existing `capture-screen` script first and validates the
capture it returns — no second device round trip is written. The known screens default to the
target's own crawl directory. An on-device validation broadcast stays rejected (rebuild + install per
run, and the capture already is the crawler's own view of the tree). A host-side reimplementation of
any matching rule stays rejected.

**G. Evidence apps — your pick, please.** 2–3 dense third-party apps installed on the emulator (a
shopping app with a cart list, a feed, a settings-heavy app are the shapes that matter). Name them
here or at Gate 2.

**H. Not folded in:** `a2h-c2b.10` (canonical trait order in identity equality) stays with
`a2h-c2b.3`. The writer keeps the record's trait order; the report prints traits and screen ids in a
sorted, deterministic order. `a2h-c2b.9` (crawler-module route-replay doc) is not touched.

## Affected surface

- **Modified (main) — the artifact format:** `AccessibilityXmlSerializer` writes the full identity in
  `<screen-identity>`; `HtmlRenderer` embeds the same block; `ScreenXmlReader` reads it back;
  `ScreenCrawlState` carries a `ScreenIdentity` instead of name-only fields; the crawl's and the
  snapshot's crawl-state builders pass the identity they already hold; `SavedCrawlLoader` keeps the
  persisted content and traits (A4).
- **Modified (main), behaviour-preserving refactors:** the route-step `<element>` read/write shape
  extracted to one shared home (A); the back-affordance counting rule extracted to one named factory
  used by the existing crawler call sites (E).
- **New (main, read-only):** a reader that rebuilds an `AccessibilityNodeSnapshot` tree from a
  capture's `<scroll-steps>` step-0 `<node>` XML (nothing reads `<node>` trees back today); the
  validator composing the production calls in the table above into one result; the report formatter.
  The click-fallback candidate collector is reused for snapshot trees (it is generic over the node
  type today).
- **New (test):** format round-trip, reader, loader and validator tests; small real-capture fixtures
  under `app/src/test/resources/`.
- **New (tooling):** the skill (`SKILL.md` + PowerShell script) and a Gradle entry point that runs the
  validator on the host JVM without changing what a plain `./gradlew test` does.
- **Docs:** a `documentation/` page for the identity block and the tool; pointers from
  `documentation/crawler-module.md` and `documentation/data-and-state.md` (what is persisted changes);
  the `capture-screen` skill's layout notes mention the identity block.
- **Modified (main), one crawl decision (A4c):** route-replay arrival validation in
  `DeepCrawlCoordinator` gains a trait condition beside its existing name check, skipped when the
  record's identity is `UNSETTLED` — so no existing crawl changes behaviour. `SavedCrawlLoader` stops
  blanking the content half (A4a).
- **Not modified, deliberately:** `ElementFingerprint`; every policy's and the trait evaluator's
  semantics; every tolerance; `ScreenXmlReader`'s truncation behaviour; dedup, entry restore and
  destination settling; the replay **name** rule.
- **Modified (main), a scope exception agreed at Gate 3 (2026-09-18):** `AndroidManifest.xml` gains a
  `<queries>` declaration for `ACTION_MAIN` + `CATEGORY_LAUNCHER`. The manifest was listed here as
  deliberately untouched, and this is a knowing departure: without the declaration, Android 11+
  package visibility hides most installed apps from the operator's app picker (three of twenty-odd
  on an API 36 emulator), so **no second or third target app can be selected** and scope call G's
  evidence deliverable is unreachable. Filed as `a2h-4ah`; fixed here rather than in its own cycle at
  the owner's direction, because it blocks this cycle's own Gate 3. Nothing else in the manifest
  changes, and `QUERY_ALL_PACKAGES` is deliberately not used.
- **Breaking changes:** the screen XML and HTML formats change. No backward compatibility is kept
  (`CLAUDE.md`): a capture made before this change has no identity block, and the tool reports it as
  INVALID_INPUT asking for a recapture — it never regenerates one silently.

## Inputs

- [x] Bead: `bd show a2h-u68` — acceptance criteria and the 2026-09-16 owner decision (notes)
- [x] Gate 1 feedback 2026-09-16: identity lives inside the screen's XML + HTML, not a separate file
- [x] Epic: `bd show a2h-c2b`; blocked follow-on `bd show a2h-c2b.3` (the open questions this feeds)
- [x] Redesign brief Part 5 (target model; direction 2 "leverage the saved XML/HTML fingerprints") and
      Part 7 (this bead's role):
      [`thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md`](../../thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md)
- [x] Prior cycles: [`.spear/a2h-c2b-2/`](../a2h-c2b-2/) (structured identity, policies) and
      [`.spear/a2h-c2b-4/`](../a2h-c2b-4/) (traits, `matchKnownScreens`)
- [x] Pre-redesign design doc, **advisory only** for its rejected alternatives:
      [`thoughts/shared/plans/2026-08-14-validate-screen-fingerprint-skill.md`](../../thoughts/shared/plans/2026-08-14-validate-screen-fingerprint-skill.md)
- [x] Real captures available for tree-reader fixtures: `E:\Logs\Snapshots\`, `E:\Logs\com.android.settings\`
      (pre-change format; identity-carrying fixtures are produced during Execute)
- [x] Governing values: `factory/FUNCTION.md` — deterministic transforms; red→green pins; document a
      dependency by reference and pin it, never restate it

## Constraints

- **One fact, one home — non-negotiable.** Every verdict comes from the production call named in the
  table. No second definition of identity, label normalization, list-item detection, back affordance,
  click eligibility, trait evaluation or uniqueness comes into existence. The XML and HTML copies of
  the identity share one syntax and one parser, and are required to agree (A2, A3).
- **Observes, never tunes.** No change to any matching rule's semantics or tolerance: `TraitEvaluator`,
  `SameScreenPolicy`, `SameNamePolicy` and `EntryRestorePolicy` are used as they are. A bad rule the
  tool exposes becomes a bead, not an edit. The one decision change (A4c) adds a condition built from
  the **unmodified** `TraitEvaluator`, and adds it only where the stored identity is settled — it
  retunes nothing.
- **The tool never writes to its inputs.** Its report goes only to its own output directory.
- **Deterministic.** Same inputs → byte-identical report; the same identity → byte-identical block.
- **Tests:** unit tier only, synthetic trees plus small real-capture fixtures; no Android framework
  mocks; no device in the automated tier.
- **Pre-existing tests:** unmodified, except where a test pins the exact serialized text of a screen's
  XML or HTML or the name-only shape of `ScreenCrawlState`; every such change is listed and justified
  at Assess.
- **Type-check:** strict — `./gradlew assembleDebug` compiles with no new warnings.
- **Refactors of covered code** (A's shared element shape, E's factory) are pinned by the existing
  suite **and** one injected mutation each, shown red and archived.

## Done means

### Machine-checked (graded in the Assess loop)

**Baseline and build**

- [ ] `./gradlew test` green at the final artifact state.
- [ ] `./gradlew assembleDebug` green at the final artifact state.
- [ ] A plain `./gradlew test` runs no validation and writes no report.

**The identity lives in the screen (A, A2, A3, A4)**

- [ ] A test writes a screen through the crawl writer and through the snapshot writer, and asserts
      each XML's `<screen-identity>` carries the name, root class and first-viewport element set
      (back affordance flagged) and each HTML embeds a byte-identical block.
- [ ] A test asserts write → read → equal for an identity with a name, a back affordance and all
      three trait kinds, from both the XML and the HTML.
- [ ] A test asserts a route-step expectation and a screen-identity element with the same attributes
      read to equal `ScreenElementIdentity` values **through the same function**: mutating the shared
      reader turns both the route-step and the screen-identity pins red.
- [ ] **A4a.** A test asserts traits added by hand to a saved crawl survive `SavedCrawlLoader` →
      rewrite byte-identical in both files; that the loaded record carries the persisted element set
      and traits rather than a blank content half; and that the loaded record's name key and every
      dedup key are unchanged from before this change.
- [ ] **A4b, red→green.** A test asserts a resumed crawl's manifest and graph JSON carry the real
      `replayFingerprint` for a loaded screen. It **fails on pre-change code**, where the blanked
      content half makes both fields empty; both runs archived.

**The settled identity decides at replay arrival (A4c)**

- [ ] A test asserts a record whose settled traits **hold** on the replayed screen yields
      `ScreenPreparationResult.Success` — as today.
- [ ] A test asserts a record whose settled traits **do not hold** yields
      `ScreenPreparationResult.Failure` routed to the existing `handlePreparationFailure` recovery —
      no new state, no pause, no case.
- [ ] **The opt-in guarantee.** A test asserts that for an `UNSETTLED` identity (no traits, or
      negations only) the replay-arrival outcome is **byte-identical to pre-change behaviour** across
      a table of holding and non-holding screens — the trait condition is not consulted at all.
      Deleting the `UNSETTLED` guard turns this pin red (mutation archived).
- [ ] A test asserts the trait-divergence failure message **names the failing trait**, and does not
      repeat the `a2h-c2b.6` shape where the message reads `Expected X but found X`.
- [ ] A test asserts the replay **name** rule is unchanged: a name divergence still fails on the name,
      with the same message, whether or not traits are present.
- [ ] A test asserts A4c consults `TraitEvaluator.verdict` — the same unmodified call the tool reports
      — so the crawler's replay question and the tool's verdict cannot diverge.
- [ ] A test asserts that an XML and HTML whose identity blocks differ yields INVALID_INPUT naming the
      differing elements or traits; a missing HTML is INVALID_INPUT.
- [ ] Tests assert each other malformed input is INVALID_INPUT **naming where**, never a verdict and
      never a crash: a capture with no identity block (pre-change format); a `has-list` the `HasList`
      constructor refuses (`min-rows="0"`, empty schema); an unknown trait element; duplicate screen
      ids among the known screens; an observed capture with no step-0 tree.

**Faithful tree (D)**

- [ ] A round-trip test serializes synthetic trees — escaped text, nested lists, checkable/editable,
      click-action-only nodes, invisible and disabled nodes — reads them back, and asserts
      `ScreenIdentity.fromRoot`, trait verdicts and the click-fallback candidates equal those of the
      original tree.
- [ ] A test reads a committed real capture fixture and asserts its step-0 identity equals the
      `fingerprint=` values the device wrote on that capture's step-0 pressable nodes.
- [ ] A test asserts the observed side is step 0 and not the merged tree: a control present only in a
      later scroll step makes `has-control` **not** hold.

**The answers**

- [ ] Tests assert each headline outcome — HOLDS, NOT_UNIQUE (listing **every** matching screen),
      DOES_NOT_HOLD, UNSETTLED (no traits; negations only; a fresh capture) — from synthetic captures.
- [ ] A test asserts the element-set answer is independent of the headline: traits HOLD while the
      element set does not match, and the report carries both, with `missing` and `extra` listed.
- [ ] A test asserts the Settings incident shape: an observed screen that gained exactly one row
      (`storage`) reports element set `ELEMENT_SET_DIFFERS`, missing = empty, extra = exactly that row.
- [ ] A test asserts `is-root="true"` excludes back affordances from the element-set comparison and
      `is-root="false"` counts them.
- [ ] A test pins the **exception** in scope call E: the route-step destination comparison still
      counts back affordances unconditionally. Rewiring that one site to the extracted factory (so it
      derives from the parent's `isRootScreen`) turns the pin red — proving the factory was applied to
      the seven derived sites only, and that the depth-1 tolerance regression cannot come back.
- [ ] A test asserts per-element re-clickability reports resolved / ambiguous / unresolved for every
      target element; that ambiguous names **all** candidates and which one the matcher ranks first;
      and that a target can HOLD while one of its elements is unresolved.
- [ ] A test asserts weak identities and collisions are reported and counted on both sides, and that
      they never change the headline.
- [ ] A test asserts every missing, unresolved or ambiguous entry is followed by the observed screen's
      actual elements in identity-block syntax, and that pasting that text into both files makes the
      element resolve on a re-run.

**Production code decides — proven, not claimed**

- [ ] **Differential:** for a table of synthetic cases the validator's trait verdict, known-screen
      match and element-set comparison equal the direct production calls on the same inputs.
- [ ] **Mutation, red then reverted, archived:** (1) `TraitEvaluator.matchKnownScreens` collapsing
      ambiguous to the first match; (2) `SameScreenPolicy` ignoring the root class; (3) the extracted
      back-affordance factory inverted; (4) `ClickFallbackMatcher` dropping the enabled check. Each
      turns at least one validator pin red.

**Operational contract**

- [ ] A test asserts input files are byte-identical after a run, and that output lands only in the
      output directory.
- [ ] A test runs the validator twice on the same inputs and asserts byte-identical reports,
      including when the known screens are presented in a different order and traits are listed in a
      different order.
- [ ] Exit status distinguishes HOLDS, NOT_UNIQUE, DOES_NOT_HOLD, UNSETTLED, INVALID_INPUT and usage
      error; a strict flag additionally fails on an element-set mismatch or any unresolved element.
      Asserted by testing the mapping directly.
- [ ] The host command runs end to end on committed fixtures with **no device attached** and exits
      with the expected status (archived transcript).
- [ ] The skill script parses clean (PowerShell parser check exits 0).
- [ ] No commented-out code, no debug output, no `TODO` added, no absolute paths in shipped code or
      scripts; every new public type and function has a KDoc contract.
- [ ] `documentation/` describes the identity block and the tool; `documentation/crawler-module.md`
      and `documentation/data-and-state.md` point to it.

### Human-verified at Gate 3 (real device; evidence under `.spear/a2h-u68/evidence/`)

- [ ] **The capture carries its identity.** A fresh `capture-screen` snapshot and a fresh crawl on the
      rebuilt app each show the identity block in the XML and the same block in the HTML.
- [ ] **The headline loop.** On a dense third-party screen: capture, validate live (UNSETTLED), add
      traits by hand to the XML and HTML so it HOLDS; break one `has-control` and get DOES_NOT_HOLD
      with the actual elements printed; paste back, re-run, get HOLDS. Transcripts of all runs.
- [ ] **Uniqueness caught.** Settle two sibling screens of one crawl with traits loose enough to both
      hold on one of them and get NOT_UNIQUE naming both; tighten, re-run, get HOLDS. Transcripts.
- [ ] **Edits survive a resume — and are acted on.** Add traits to a screen of a paused crawl, resume
      it, and find the traits intact in both files afterwards. Then show the crawler *using* them:
      with traits that hold, the replay proceeds as before; with one trait edited so it cannot hold,
      the resumed crawl reports the trait divergence by name and takes the existing recovery path.
      Transcripts of both.
- [ ] Navigating away and re-running yields DOES_NOT_HOLD with a diff naming what changed.
- [ ] A disk-only run (no device attached) against a second capture succeeds, and an identical re-run
      produces a byte-identical report.
- [ ] **Evidence deliverable for `a2h-c2b.3`.** 2–3 dense third-party apps (scope call G): per screen,
      weak-identity and collision counts, element-set churn between two captures of the same screen,
      and whether hand-settled traits could be made to HOLD uniquely and at what effort. Written up in
      `.spear/a2h-u68/evidence/` and summarized into `a2h-c2b.3`'s notes.

## Non-goals

- Any crawl decision reading the persisted identity **beyond A4c's replay-arrival trait check** —
  dedup, entry restore and destination settling still work exactly as today (`a2h-c2b.3`).
- Traits influencing a crawl in any way when the stored identity is `UNSETTLED` — which is every
  machine-proposed identity, since nothing proposes traits yet (`a2h-c2b.3`).
- Operator edits made while a crawl is running, and write-back precedence generally (`a2h-c2b.3`).
- Proposing traits automatically (`a2h-c2b.3`); operator case handling.
- Canonical trait order in identity equality (`a2h-c2b.10`).
- `EntryRestorePolicy` as a validated answer — the bead asks for element sets via `SameScreenPolicy`.
- Any change to `ElementFingerprint`, a policy's semantics or a tolerance.
- Backward compatibility with captures made before this change.
- Cross-device validation; an on-device validation broadcast; a standalone JVM tools module.

`MAX_ROUNDS = 10`

Rounds are Assess/fix iterations — a circuit breaker, not a budget. If the same defect survives two
consecutive rounds, stop and escalate regardless of rounds remaining.
