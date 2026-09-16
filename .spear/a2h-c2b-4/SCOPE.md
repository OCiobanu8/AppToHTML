# SCOPE

Bead: `a2h-c2b.4` — *Screen traits: identity as assertions checked against the screen*
(parent epic `a2h-c2b`; depends on closed `a2h-c2b.2`; feeds `a2h-u68` and `a2h-c2b.3`)

Branch: `feat/screen-identity-traits`, cut from `epic/structured-screen-identity` at `fe13060`.
PR targets the epic branch, not `main`.

## Goal

Feature: a `ScreenIdentity` can carry **traits** — assertions such as *"there is a list here whose
rows have a title, a price and a quantity"* or *"there is a checkout control"* — and the crawler can
ask **which known screens' traits hold against a live accessibility tree**, getting back
**zero, one, or more than one**, with more than one reported as a case instead of silently merged.

## Audience

The next two epic beads, which cannot be built without this: `a2h-u68` (the operator's *"does this
fingerprint hold?"* tool) and `a2h-c2b.3` (propose / validate / settle, where dedup becomes *"run
the known fingerprints against this screen"*). No end-user-visible change in this cycle.

## Why now

A list screen can never be recognised twice today. Every identity comparison is over **instances** —
every pressable element, exact labels, all-or-nothing — and on a list screen the rows *are* the data.
One more item in the cart and it is a different screen.

Traits compare **types**, not instances. *"A list whose rows carry `title`, `price`, `quantity`"* stays
true while every row changes, because an app's row layout has stable resource ids while the values in
it change every visit. No semantic typing of fields is needed — the row's child resource ids *are*
the schema.

This also answers redesign-brief Part 6 question 2 (*"what does it mean for a proposed fingerprint
to hold?"*) concretely: a fingerprint made of assertions is a predicate, and **holding means the
predicate evaluates true against the screen**.

## Approach

**One sealed `Trait` hierarchy, one list.** `ScreenIdentity` gains a third addressable part,
`traits: List<Trait>`, alongside the name half and the content half. The list is evaluated as a
**conjunction**: an identity holds when every trait holds.

| Trait | Asserts | Evaluated against |
|---|---|---|
| `HasList(containerResourceId, minRows, rowChildResourceIds)` | a container with that id has at least `minRows` rows, each carrying all of the listed child resource ids | the row structure of the tree — which exists only in `AccessibilityNodeSnapshot`, never in the flattened element set |
| `HasControl(element)` | a specific stable control is present | the tree's pressable elements, compared on `ElementFingerprint` only |
| `LacksControl(element)` | a specific control is **absent** | same — see scope call **D** |

Worked example, a shopping cart:
`[ HasList("…:id/cart_items", minRows = 1, {title, price, quantity}), HasControl(checkout) ]`

**The comparison direction inverts.** Traits are checked against the **live tree**, never against
another identity or a regenerated string (redesign brief, Part 5).

**Walk once, then set lookups.** The observed tree is walked **once** into an evaluation context —
the pressable-element fingerprint set, plus a container → rows → child-resource-ids index. Every
candidate screen is then evaluated by set lookups, not tree walks. Cost is negligible (~3 traits ×
~300 screens ≈ 900 set operations) and must not drive the design.

**Evaluate against every known screen, not only the expected one.** Checking only the expected screen
makes a uniqueness violation undetectable. The result is one of:

| Result | Meaning |
|---|---|
| **None** | no known screen's identity holds here — a new screen |
| **One** | exactly one does — a known screen |
| **Ambiguous** | more than one does — a uniqueness violation, carried as a **case** listing every matching screen, never collapsed to the first |

Screens whose trait list is **empty are reported separately as unsettled** and are never candidates.

**A known screen matches when all of these hold** (scope call **E**): its package equals the observed
package; its name key equals the observed screen's name key *when the observed screen has been
named* (a bare live probe has no name, so the name then does not participate); and every trait holds.

*Amended during the Assess loop — each amendment is **pending owner confirmation at Gate 3**:*
*(1) round 1: an identity whose traits are all negations (`LacksControl` only) is **also unsettled**,
because it would hold on a blank screen; (2) round 1: package equality never holds between two
missing packages — an observed tree with no package matches no known screen, and a known screen
with no package matches nothing; (3) PLAN: the name key takes part only when **both** the known and
the observed screen have one, so an unnamed known screen can still match a named observed one.*
*(4) round 5, a wording clarification only: "walked **once**" above means once per observed tree and
never per candidate — building the context walks it twice, once for pressables and once for
containers.*
Uniqueness is expected from the whole identity — package + screen name + title disambiguators +
traits together — so two screens with the same traits but different names are *not* ambiguous.

**The empty-list trap is guarded, explicitly.** In Kotlin `emptyList<Trait>().all { … } == true`, so a
screen with no traits would vacuously hold against every tree and match everything. An empty trait
list means **"not settled yet"**, never **"matches all"** — which is what makes a weak identity fail at
creation rather than 300 screens later.

**Determinism for free.** The rule is boolean — no score, no threshold, no tie-break — so the ranked
search non-determinism the redesign brief warns about cannot arise. The one ordering the result does
expose (the list of matching screens in a case) is made deterministic explicitly.

## Scope calls for Gate 1 — please confirm or change each

Each has a recommendation; the SCOPE above is written assuming the recommendation.

**A. Not wired into any crawl decision — library + tests only. (Recommended.)**
Nothing in production produces traits yet: proposing them is `a2h-c2b.3`, settling them by hand is
`a2h-u68`. So every captured screen has an empty trait list, and wiring evaluation into dedup, replay
or restore now would make every screen *unsettled* and break the crawl. This cycle therefore changes
**no crawl outcome**: dedup, replay, entry restore and destination settling are untouched, and the
existing suite pins that.

**B. Traits are not yet written to or read from the crawl XML. (Recommended: defer to `a2h-c2b.3`.)**
Nothing produces traits, so there is nothing to persist, and the persistence format is best designed
together with how an operator settles them. The cost of deferring is named here so it is not a
surprise: until then, a trait placed on an identity would not survive a resume.

**C. `isIdentifying` is derived from the traits, not stored on each element. (Recommended — a
deviation from the bead's notes, which say "add `isIdentifying` to `ScreenElementIdentity`".)**
The bead wants a policy to ask *"are the identifying controls present?"* instead of *"are the two sets
equal?"*. `HasControl` already records exactly which controls identify a screen, so a stored per-element
flag would be a **second home for the same fact** (`factory/COMPANY.md`: one fact, one home) and the
two could disagree. It would also enter data-class equality on `ScreenElementIdentity`, which
`EntryRestorePolicy` compares **directly** — a flag set on the expected side and never on a live probe
would silently turn entry-restore matches into mismatches. So: `ScreenIdentity.identifyingElements`
is derived from its `HasControl` traits, and `ScreenElementIdentity` is unchanged. If you want the
stored flag anyway, say so and I will scope the equality isolation it needs.

**D. `LacksControl` is implemented and tested, but produced nowhere. (Recommended.)**
The bead decides negation is *supported by the hierarchy but not used initially*. Implementing the one
negative kind now is ~5 lines plus pins, and proves the conjunction is not secretly positive-only —
cheap now, awkward to bolt on once `all {}` is assumed positive-only.

**E. The name half participates when the observed screen has a name. (Recommended.)** As written in
Approach. The alternative — traits and package only — would flag two differently-named screens with
the same traits as ambiguous, contradicting the bead's *uniqueness from the whole identity*.

## Affected surface

**New:** the `Trait` hierarchy and its evaluation context; the known-screen evaluator and its
None / One / Ambiguous result (with the unsettled list).

**Modified:** `ScreenIdentity` gains `traits` (default empty) and a derived `identifyingElements`.
Every existing constructor call keeps compiling unchanged.

**Not modified, deliberately:** `ElementFingerprint` (the element atom — epic says out of scope);
`ScreenElementIdentity` (scope call C); every existing comparison policy and every crawl call site
(scope call A); the XML writer and reader (scope call B); the scroll / loop-detection fingerprint.

**Breaking changes:** none. `traits` defaults to empty, so every identity built today is unchanged
and compares equal to what it compared equal to before.

## Inputs

- Bead: `bd show a2h-c2b.4` — decisions taken with the product owner on 2026-09-08 (inverted
  comparison direction; negation supported not used; non-uniqueness flagged for a human, not
  resolved automatically)
- Meeting brief presenting this design:
  https://claude.ai/code/artifact/a417a90f-c473-47e1-b8d1-400cafec8dd5
- Redesign brief: `thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md` — Part 5
  (target model: compared against the screen; uniqueness; weak identity fails at creation) and
  Part 6 Q2 (what "holds" means)
- Previous cycle: `.spear/a2h-c2b-2/` — the structured identity this builds on
- Governing values: `factory/FUNCTION.md` — deterministic transforms; behavior is pinned red→green

## Constraints

- **PR size:** no hard cap; expected small (one new file, one modified, tests). Not a graded metric.
- **Tests required:** yes — synthetic `AccessibilityNodeSnapshot` trees only, no framework mocks.
- **Type-check level:** strict (Kotlin compiler, `assembleDebug`).
- **No crawl outcome moves.** The pre-existing suite stays green with no pre-existing test modified.

## Done means

Executable checks — each a test, a build, or a grep; inspection is the last resort.

- [ ] **`./gradlew test` green** at the final artifact state.
- [ ] **`./gradlew assembleDebug` green** at the final artifact state.
- [ ] **One sealed hierarchy, one list:** `ScreenIdentity` exposes `traits` as a single list of one
      sealed `Trait` type with `HasList`, `HasControl` and `LacksControl` as its only subtypes; an
      exhaustive `when` over `Trait` compiles without an `else` branch.
- [ ] **A list screen is recognised across different rows:** a test captures a cart tree, builds
      `HasList(cart_items, minRows = 1, {title, price, quantity})` + `HasControl(checkout)`, and
      asserts it **holds** against a second tree whose rows have entirely different values and a
      different row count.
- [ ] **HasList reads the row schema, not just the container:** tests assert it does **not** hold when
      (a) a row is missing one of the listed child ids, (b) fewer than `minRows` rows qualify,
      (c) the container id is absent.
- [ ] **HasControl ignores the back flag and geometry:** a test asserts it holds against a tree where
      the same control sits at a different position and on the other side of the back-affordance band.
- [ ] **Negation works in the conjunction:** a test asserts an identity carrying `LacksControl(x)`
      fails on a tree containing `x` and holds on one without it.
- [ ] **Empty is guarded, never "matches all":** a test asserts a known screen with an empty trait list
      is never a candidate against any tree, and is listed as unsettled.
- [ ] **Evaluated against every known screen — zero / one / more-than-one:** tests assert the
      evaluator returns **None** for a tree no known screen holds against, **One** with the right
      screen id for a unique match, and **Ambiguous** listing **every** matching screen id (not just
      the first) when two screens' identities both hold.
- [ ] **Uniqueness is over the whole identity:** a test asserts two screens with identical traits but
      different screen names resolve to **One** when the observed screen is named, and to **Ambiguous**
      when it is an unnamed probe.
- [ ] **Deterministic:** a test asserts that evaluating the same tree against the same known screens
      presented in two different orders yields an identical result, including the order of ids in a
      case.
- [ ] **The pins bite (red-first):** each of these mutations is applied, turns at least one pin RED,
      is archived under `.spear/a2h-c2b-4/evidence/`, and is reverted:
      (1) the empty guard removed, so `traits.all {}` runs on an empty list;
      (2) Ambiguous collapsed to the first match;
      (3) `HasList` ignoring `rowChildResourceIds`;
      (4) `LacksControl` evaluated as `HasControl`.
- [ ] **`isIdentifying` derived, not stored (scope call C):** a test asserts
      `identifyingElements` equals the elements of the identity's `HasControl` traits; a grep over
      `app/src/main` finds no `isIdentifying` field on `ScreenElementIdentity`.
- [ ] **No crawl outcome moved (scope call A):** `git diff` against `fe13060` shows no change to any
      file under `app/src/main` other than `ScreenIdentity.kt` and the new trait file, and no
      pre-existing test file modified.
- [ ] **Walk once:** the known-screen evaluator takes a context built once from the tree; a grep shows
      it performs no tree traversal per candidate.
- [ ] No commented-out code left behind; no `TODO` added; every new public type and function has a
      KDoc contract.

## Non-goals

- Producing traits for a screen — machine proposal is `a2h-c2b.3`; hand-settling is `a2h-u68`.
- Wiring trait evaluation into dedup, replay, entry restore or destination settling (scope call A).
- Persisting traits in the crawl XML (scope call B).
- The operator case workflow — pausing, queuing, resolving a case. This cycle **reports** a case as a
  typed result; handling it is `a2h-c2b.3`.
- Semantic typing of row fields (price vs. title) — the resource ids are the schema.
- Changing `ElementFingerprint`, any existing policy, or any tolerance value.

`MAX_ROUNDS = 8`
