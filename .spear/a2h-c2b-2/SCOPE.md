# SCOPE

Bead: `a2h-c2b.2` — *Structured screen identity with an explicit comparison policy*
(parent epic `a2h-c2b`; depends on closed `a2h-c2b.1`; blocks `a2h-u68`)

## Goal

Replace the flat screen-fingerprint **string** with one **structured screen identity** produced at
capture for every screen — root and child alike — and make every screen-level comparison an
**explicitly named policy** over that structure instead of `==` on a string.

**Behavior-preserving.** This cycle makes today's rules explicit rather than accidental. It changes
**how** the crawler decides "am I on this screen?", not **what** it decides. No comparison outcome
moves.

## Audience

The crawler itself (every replay, restore and dedup decision), and the next bead `a2h-u68`
(*validate whether a screen fingerprint holds against a live screen*), which cannot be built at all
while identity is a flattened string.

## Why now

The current representation only permits one question. Identity is built by fingerprinting every
pressable element on a screen, sorting the encodings, joining them with `||`, and prefixing the root
class (`ScrollScanCoordinator.kt:344`). Once flattened, the only expressible question is *"are these
two strings equal?"*. The useful question on a volatile screen — *"are the things I recorded still
present here?"* — is not askable, because there is nothing left to look for.

The single place in the system that asks the useful question, entry-screen restore, is also the only
place that **decodes the string back into a set first** (`EntryScreenFingerprintMatcher.kt:45`). That
is the tell: the structure has to be reconstituted before the good question becomes possible.

Three known defects are consequences of the flattening, and dissolve with it:

- **`replayFingerprint` is polymorphic.** The root screen holds the back-affordance-**filtered**
  variant (`DeepCrawlCoordinator.kt:119`); every child holds the **unfiltered** one
  (`DeepCrawlCoordinator.kt:576`). Same field name, two incompatible kinds of value. No consumer can
  read that field without also knowing the screen's depth.
- **Back-affordance filtering is duplicated.** `looksLikeEntryBackAffordance` and
  `normalizeFingerprintToken` exist as character-identical copies in
  `EntryScreenFingerprintMatcher.kt:116` **and** `ScrollScanCoordinator.kt:388`, hard-coded
  `top <= 300` and all.
- **"What differed" is not answerable.** Every non-matching comparison reports only *different*.
  Recovering the missing and extra elements means decoding both strings by hand after the fact.

## Approach

**One type.** A `ScreenIdentity` value carrying, as separately addressable fields:

| Field | Source today |
|---|---|
| package name | `ScreenIdentityCodec` (Family B) |
| screen name (normalized title) | `ScreenIdentityCodec` (Family B) |
| title disambiguators (max 2) | `ScreenIdentityCodec` (Family B), today called `hints` |
| root class name | viewport fingerprint prefix (Family A) |
| element identity set, **each flagged `isBackAffordance`** | viewport fingerprint payload (Family A) |

The name half and the content half stay **distinct fields, not blended into a score**, so dedup can
weight the name while replay ignores it. The back affordance stays **in** the set, flagged —
filtering moves from build time to comparison time, which is what removes the polymorphic field.

**Named policies.** Each comparison site names the policy it wants. The mapping below is
behavior-preserving — each policy reproduces exactly what that site does today:

| Policy | Rule (unchanged from today) | Sites |
|---|---|---|
| `EntryRestore` | package must match; root class must match; back affordances **not counted**; exact **or** observed-enriches-expected **or** Dice >= 2/3 | S1 |
| `SameScreen(countBackAffordances)` | whole element set equal; back counted **iff** the screen is not the crawl root | S2, S4, S5 |
| `NavigatedAway` | negation of `SameScreen` against the before-click and top identities | S3, S6 |
| `Dedup` | package + name + disambiguators equal, **and only when** the name identity is `STRONG` | S7 |
| `SameName` *(added in round 2)* | name key (package + name + disambiguators) equal, **ungated** by confidence | replay-name check before expanding a replayed screen |

**Every comparison returns a result that carries the missing and extra element identities**, so
"what differed" is a field, not a forensic exercise.

### The comparison sites this cycle must convert

| # | Site | Today |
|---|---|---|
| S1 | `ScrollScanCoordinator.kt:122` — entry-screen restore | `EntryScreenFingerprintMatcher` over decoded sets |
| S2 | `DeepCrawlCoordinator.kt:971` — pre-open top validation | `!=` on a string, variant chosen by `usesEntryFingerprint` |
| S3 | `DeepCrawlCoordinator.kt:1396` — "the click did not navigate" | `==` against before-click and top strings |
| S4 | `DeepCrawlCoordinator.kt:1417` — route-step replay validation | `==` on a string, **zero** tolerance |
| S5 | `DestinationSettler.kt:48` — known-destination match | `==` on a string |
| S6 | `DestinationSettler.kt:126` — changed-from-before / changed-from-top | `!=` on a string |
| S7 | `CrawlRunTracker.kt:165` — dedup / graph linking | `LinkedHashMap` key lookup on the Family-B string |
| S8 | `AppToHtmlAccessibilityService.kt:604,627,640` — root recovery | **dead code** — see below |

**S8 is dead and is deleted, not converted.** `recoverToRootAfterEdgeFailure` has zero callers, and
`navigateBackToRoot` is called only from it. Both are `private`, so nothing else can reach them.
Migrating unreachable code would inflate this cycle for no behavior.

## Where identity is stored, and what is actually read back

Established by inspection, and it decides how far this cycle reaches:

| Artifact | Written | Read back by production code |
|---|---|---|
| `crawl-index.json` (`screenFingerprint`, `replayFingerprint`) | every screen | **never** — the only reference in `app/src/main` is the writer, `CaptureFileStore.kt:44` |
| XML `<screen-identity>` package / title / disambiguators | every screen | **yes** — `ScreenXmlReader.kt:134` on resume, rebuilds the dedup map |
| XML `<step><expected-replay>` | route steps | **yes** — drives route replay after resume |
| XML `<step><expected-destination-identity>` (now `<expected-destination>`) | route steps | **yes** |
| XML `<merged-elements element fingerprint=…>` | every element | no — recomputed from that element's attributes |
| HTML `fingerprint=` | every link | no |
| in-memory `CrawlScreenRecord.replayFingerprint` | every screen | yes, live |

So **`crawl-index.json` is a debug log with no functional role**. The bead's criterion *"crawl-index
no longer stores two kinds of value under `replayFingerprint`"* is therefore satisfied as a
**consequence** of fixing the in-memory representation, not as a goal in its own right: once one
builder produces every screen's identity, the log prints one kind of thing. The functional
persistence surface that must round-trip correctly is the **XML**.

## Rename: `hints` → `titleDisambiguators`

Decided at Gate 2. The current name is actively misleading — it reads as *distinctive elements on
the screen*, which is the opposite of what the field holds.

What it actually holds: the **losing candidates from the screen-naming contest**. They come from the
same scored pool as the title — static text nodes (`ScreenNaming.kt:431`, threshold 120, heavily
penalised inside a scrollable) plus visible resource-id segments (`ScreenNaming.kt:416`) — with the
chosen title and any generic title removed. Their only job is to break ties between two screens that
ended up with the same title. They are text, never elements, and are never clicked.

The rename is **pure**: same values, same cap of 2, same selection rule, same dedup outcomes. It
covers the Kotlin identifiers (~31 references across 8 files), the XML attributes
`hint-1`/`hint-2` → `title-disambiguator-1`/`title-disambiguator-2`, and the dedup-key segment
`:hint:` → `:disambiguator:`, whose format prefix bumps `v2` → `v3`.

**The cap stays at 2.** A Gate-1 proposal to widen it to 10 was withdrawn at Gate 2: hints 3–10 would
be filled almost entirely by resource-id candidates returned in **tree order with no score
threshold** (`collectIdentityHints`, `ScreenNaming.kt:391`), so the set would shift whenever any
visible node appeared or disappeared, and dedup would stop linking on content-rich screens. Changing
the cap or the selection rule is out of scope and wants its own cycle.

## Affected surface

**New:** the `ScreenIdentity` structure, its comparison policies, and its canonical
serialize/deserialize (the XML still needs a string form).

**Rewritten:** `ScrollScanCoordinator` (identity builders replace the four string variants),
`DeepCrawlCoordinator` (S2–S4 and both capture paths), `DestinationSettler` (S5–S6),
`CrawlRunTracker` (S7), `ScreenNaming` (the Family-B identity folds into the new structure),
`ScreenIdentityCodec` / `AccessibilityXmlSerializer` / `ScreenXmlReader` / `SavedCrawlLoader`
(one canonical serialized form, renamed disambiguators), `CrawlManifestStore` (prints the canonical
form), `SnapshotCrawlState` and `CrawlerModels` (the rename).

**Absorbed / deleted:** `EntryScreenFingerprintMatcher` (its algorithm becomes the `EntryRestore`
policy), the duplicated `looksLikeEntryBackAffordance` / `normalizeFingerprintToken` pair, the dead
`recoverToRootAfterEdgeFailure` / `navigateBackToRoot` pair, and the
`logicalEntryViewportFingerprint` / `geometrySensitiveEntryViewportFingerprint` build-time filtering
variants.

**Kept unchanged, deliberately:** `ElementFingerprint` (the element atom — bead says out of scope),
and `geometrySensitiveViewportFingerprint` used for scroll-loop detection at
`ScrollScanCoordinator.kt:53,99` (bead says out of scope).

**Also removed (round-3 grading).** The `<expected-replay fingerprint="…">` raw fallback attribute,
which the writer emitted when a stored value could not be decoded. There is no decode step at write
time any more, so the branch had no reachable input. A step element that cannot be parsed now yields
an empty identity rather than a raw string, which fails its comparison — the same outcome the raw
string produced.

**Crawls saved before this change cannot be resumed — decided at the device check, 2026-09-10.**
The consequence is harsher than the dedup-key note below suggests. A resumed pre-cycle screen whose
saved identity carried a `hint-N` disambiguator gets a name key with no disambiguator, so the replay
name check fails on its first expansion and the crawl ends `partial_abort` (evidence 10). The
operator decided not to support old crawls: after upgrading, start a **New Crawl** per app. No
`hint-N` fallback is added.

**Debug-log shape (round-4 grading, informational).** A screen loaded by `SavedCrawlLoader` has
an empty content half until it is re-scanned, so `crawl-index.json` and the graph JSON now print its
`replayFingerprint` as `"::"` (root class and elements both empty) where HEAD printed `""`. Nothing
reads either file.

**Comparison-outcome changes, named after round-2 grading.** The SCOPE's "behavior-preserving,
without exception" clause is amended to these two, both of which unify a pre-existing inconsistency
rather than choosing a new tolerance:

- **Entry restore's *observed* set is now built the same way as its *expected* set.** Before, the
  expected side deduplicated on `ElementFingerprint` and *then* dropped back affordances, while the
  observed side dropped them *first* and never deduplicated. Because back-ness is decided from
  `bounds`, which is not part of `ElementFingerprint`, two pressables sharing a fingerprint but at
  different heights resolved differently on the two sides. One builder now serves both. This can
  move `EXPECTED_LOGICAL_NOT_FOUND` ↔ `MATCHED_COMPATIBLE_LOGICAL` at the Dice boundary on a screen
  carrying two identically-fingerprinted controls, one of them inside the top band — for example a
  list with two "Backup" rows, since the back predicate matches any resource id *containing*
  `back`. Pinned by `ScreenIdentityCharacterizationTest.entryIdentity_dedups_before_flagging_...`.
- **`matchedExpectedLogical` (a logged field only) now also requires the package to match**, because
  it is derived from the policy's `EXACT_FINGERPRINT_MATCH`. Before, a foreign-package screen whose
  encoded string happened to match would have logged `true`.

**Breaking changes (artifacts and module-internal API):**

Amended after round-1 grading, to record breaks that actually occurred rather than the ones
predicted:

- The XML step element `<expected-destination-identity>` becomes `<expected-destination>`, and both
  step elements now carry `package`, `root-class` and `<element … back="…">`. The old element name
  described a *name* identity it never actually held (see PLAN, "a latent bug this fixes").
- `ScreenIdentity`, `ScreenElementIdentity`, `ScreenNameIdentity`, `ElementFingerprint`,
  `ScreenDedupConfidence` and `ScreenIdentityCodec` are `public` rather than `internal`. Forced:
  they sit on `CrawlScreenRecord` and `CrawlRouteStep`, which are public. Widening only — no
  behavior change, and `ElementFingerprint`'s contents are untouched as the SCOPE requires.

**Breaking changes (artifacts only):** XML `hint-*` attributes become `title-disambiguator-*`, and
the dedup key's format prefix bumps to `v3`. A crawl saved before this change resumes with its
disambiguators unread, so its dedup keys differ. The root screen's serialized replay value now
carries the back affordance (the structure keeps it; the *policy* excludes it). `CLAUDE.md` states no
backward-compatibility requirement and crawl state is not persisted beyond on-disk artifacts, so no
migration is provided.

## Scope call still open

**`ScreenIdentity` is already a type name** (`ScreenNaming.kt:620`, the name-only Family-B value).
The new structure takes that name and the existing one folds into it as its name fields. Naming
detail; raised only so it is not a surprise in the diff.

## Inputs

- Bead: `bd show a2h-c2b.2`
- Decision brief: `thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md`
  (Part 4 — root cause; Part 5 — direction 1, and the *distinct fields, not a blended score* rule)
- Code map: `thoughts/shared/research/2026-08-18-fingerprint-flows.md` (Part 8 — the comparison
  matrix this cycle converts)
- Governing values: `factory/FUNCTION.md` — *deterministic transforms*, and *a refactor of uncovered
  code is pinned by characterization tests plus a mutation*

## Constraints

- **PR size:** no cap — set deliberately at Gate 1. One structure has to land across all seven live
  comparison sites plus the serialization boundary **in one step**; a half-migrated codebase would
  run two identity models at once, which is strictly worse than either endpoint. Size is therefore
  not a graded metric this cycle; coherence of the single migration is.
- **Tests required:** yes — characterization pins **plus** an injected mutation on both sides.
- **Type-check level:** strict (Kotlin compiler, `assembleDebug`).
- **Behavior-preserving, without exception.** No comparison outcome may change for any screen the
  pre-existing suite covers. The rename must not alter a single dedup decision within a run.
- **No tolerance value moves.** `2/3` stays `2/3`; `top <= 300` stays `top <= 300`; the
  disambiguator cap stays `2`; the disambiguator score threshold stays `120`.

## Done means

Executable checks (each is a test, a build, or a grep; inspection is the last resort):

- [ ] **`./gradlew test` green** at the final artifact state.
- [ ] **`./gradlew assembleDebug` green** at the final artifact state.
- [ ] **Characterization pins exist and are trustworthy.** Tests pinning today's **entry-restore
      outcome** (`EntryScreenResetOutcome` plus match reason) and today's **route-step replay
      outcome** (matched / replay-failure) across at least: exact match, enrichment, Dice just above
      and just below the threshold, root-class mismatch, and foreign package. They are run GREEN
      against **pre-refactor** code and GREEN after, with both runs archived under
      `.spear/a2h-c2b-2/evidence/`.
- [ ] **The pins bite, on both sides.** An injected mutation turns them RED against pre-refactor code
      **and** an equivalent mutation turns them RED against the refactored code; both archived, both
      reverted. A green suite over previously-uncovered code proves nothing without this.
- [ ] **The structure is addressable:** a test builds a `ScreenIdentity` from a synthetic tree and
      asserts package, screen name, title disambiguators, root class and element set are readable as
      **separate fields** — with no string parsing anywhere in the test.
- [ ] **Back affordance is in the structure, not filtered at build time:** a test asserts that a root
      screen with a visible back affordance yields an identity whose element set **contains** that
      element flagged `isBackAffordance`, **and** that the `EntryRestore` policy still matches the
      same screen captured without it.
- [ ] **One builder, every screen:** a test runs a crawl producing a root and a child and asserts
      both identities come from the same builder — concretely, that the root's identity now contains
      the back-affordance element, as the child's always did.
- [ ] **Difference is derivable:** a test asserts that a non-matching comparison returns the
      **missing** and **extra** element identities as sets, obtained from the result object — the
      test must not split, decode, or parse any encoded string.
- [ ] **XML round-trips the whole structure:** a test writes an identity carrying two title
      disambiguators and a flagged back affordance, reads it back, and asserts equality — proving
      neither the flag nor the renamed attributes are lost on resume.
- [ ] **Rename is complete and pure:** a repo grep over `app/src/main` finds no `hint` identifier or
      `hint-` XML attribute for this concept; the dedup key carries the `v3` prefix; and a test
      asserts the same screen produces the same dedup grouping as before the rename.
- [ ] **Every live site resolves through the one identity:** a repo grep over `app/src/main` finds
      **zero** occurrences of `logicalEntryViewportFingerprint`,
      `geometrySensitiveEntryViewportFingerprint`, `EntryScreenFingerprintMatcher`, and
      `usesEntryFingerprint`.
- [ ] **Duplication removed:** exactly **one definition** each where there were two —
      `looksLikeEntryBackAffordance` and its normalizer (now `BackAffordanceTokens.normalize`) live
      once, in `ScreenIdentity.kt`; the magic `top <= 300` is the single named constant
      `TOP_BAND_PX`, so the literal string appears zero times.
- [ ] **Dead recovery pair deleted:** a repo grep finds no `recoverToRootAfterEdgeFailure` or
      `navigateBackToRoot` in `app/src`.
- [ ] **Named policies, not booleans:** a repo grep over `app/src/main` finds no screen-level
      comparison expressed as `==` / `!=` between two fingerprint **strings**; every site calls a
      policy by name.
- [ ] **`crawl-index.json` stays a debug log:** a repo grep confirms `app/src/main` contains no
      **reader** of the manifest, only `CaptureFileStore.kt`'s writer.
- [ ] **No pre-existing test weakened:** every modified or deleted pre-existing test maps to a named
      behavior in "Breaking changes", or is a mechanical rename/signature change only. Assessed per
      test.
- [ ] **Thresholds untouched:** `git diff` shows no change to `2.0 / 3.0`, `top <= 300`,
      `minIdentityHintScore`, or the disambiguator cap of 2 **as values**.
- [ ] No commented-out code left behind; no `TODO` added.

## Non-goals

- Changing any tolerance value, the disambiguator cap, or how disambiguators are selected.
- Making dedup content-aware, or turning it into a ranked search.
- Making saved identity authoritative mid-crawl — redesign-brief direction 2, a separate bead.
- Touching `ElementFingerprint` or the geometry-sensitive scroll fingerprint (bead: out of scope).
- The settled-fingerprint / operator-case model (redesign-brief Part 5 target model) — this cycle
  builds the representation that model needs, not the model.

`MAX_ROUNDS = 10`
