# PLAN — a2h-c2b.4

Screen traits: identity as assertions checked against the screen. Scope approved at Gate 1 with all
five scope calls as recommended (A library-only, B no XML yet, C `isIdentifying` derived, D
`LacksControl` implemented not produced, E name participates when the observed screen is named).

## Target design

### Files

| File | Change |
|---|---|
| `crawler/ScreenTraits.kt` | **new** — `Trait` hierarchy, `TraitEvaluationContext`, `TraitVerdict`, `ScreenTraitMatch`, `TraitEvaluator` |
| `crawler/ScreenIdentity.kt` | `ScreenIdentity` gains `traits` (default empty) and derived `identifyingElements` |
| `test/.../ScreenTraitsTest.kt` | **new** — every pin in SCOPE "Done means" |

Nothing else under `app/src/main` changes (scope call A). No pre-existing test changes.

### The hierarchy

```kotlin
sealed interface Trait {
    fun holds(context: TraitEvaluationContext): Boolean
}

class HasList(
    val containerResourceId: String,
    val minRows: Int,
    rowChildResourceIds: Set<String>,
) : Trait {
    val rowChildResourceIds: Set<String>  // a read-only copy of the argument
    // init: containerResourceId not blank, minRows >= 1, rowChildResourceIds not empty, no blank id
    // equals written out over the three fields; hashCode consistent with equals;
    // toString is diagnostic only, with no promised format
}

data class HasControl(val element: ScreenElementIdentity) : Trait
data class LacksControl(val element: ScreenElementIdentity) : Trait
```

*(Corrected in round 4. Gate 2 approved `data class HasList`. Round 1 (D7) and round 2 (D9) made it a
plain class holding a **read-only copy** of the schema, with written-out equality, because a data class
keeps the caller's set as given — a caller could empty it after the construction check and make the
trait vacuous. Round 5 narrowed the equality comment: the round-4 wording promised a `toString`
format and a `hashCode` field list, neither of which is a contract.)*

**Vacuous-truth traps, all refused** (the bead's rule: a weak identity fails at creation, not 300
screens later):

| Trap | Why it is vacuous | Guard |
|---|---|---|
| empty trait list, **or negations alone** | `emptyList().all {} == true` matches every tree; an identity of `LacksControl` alone holds on a blank screen *(negations-only added in round 1, D6 — pending owner confirmation at Gate 3)* | evaluation verdict `UNSETTLED`, never `HOLDS` (below) |
| `minRows = 0` | `count >= 0` is always true — any container with that id that has rows qualifies, whatever its rows carry *(wording corrected in rounds 1, 2 and 6: it does not hold when no such container exists)* | `require(minRows >= 1)` |
| empty `rowChildResourceIds` | `row.containsAll(emptySet())` is always true — any child counts as a row | `require(rowChildResourceIds.isNotEmpty())` |

The empty trait list cannot be refused at construction — every identity built today has one, and it
legitimately means *not settled yet* — so that guard lives in evaluation.

### HasList semantics

- **Container:** any node whose `viewIdResourceName` equals `containerResourceId` **exactly** — the full
  `package:id/name` string as captured, no normalisation. The operator copies it from the XML; the
  bead's `'cart_items'` is shorthand. One rule, no suffix matching.
- **Rows:** the container's **direct children** in the tree `HasList` is given. On a scroll-merged
  capture that tree is exactly what `SyntheticAccessibilityTreeBuilder` produced, which can differ from
  the rows on screen: the merge may collapse rows that look identical, and a list inside a scrolled
  wrapper may appear as one or more copies of the container. `minRows` is checked against the merged
  shape. The shapes observed so far are pinned by characterisation tests in `ScreenTraitsTest`.
  *(Corrected in round 5. Rounds 2, 3 and 4 each restated how the merge behaves, and each restatement
  was falsified by a shape it missed — last, a short list that stays fully visible across viewports
  merges into one copy. The merge's behaviour is owned by `SyntheticAccessibilityTreeBuilder` and
  `documentation/crawler-module.md`; this plan points to it instead of restating it. Changing the merge
  or pooling rows is out of scope — see `a2h-c2b.11`. Round 6: "one or more copies", since three
  viewports can yield two.)*
- **A row qualifies** when every id in the schema is the `viewIdResourceName` of some **descendant** of
  the row — the row itself excluded, since the schema is what a row *contains*. *(Reworded in round 5
  to state what `holds` observes. How the index stores blank ids, id-less rows or childless containers
  is not observable through `HasList.holds` — none of them can satisfy a non-empty, blank-free schema
  — and is not promised; the index's own contract is stated once, on `rowsOfContainersWithId`.)*
- **Holds** when some single container with that id has at least `minRows` qualifying rows. Rows are
  not pooled across two same-id containers.
- Visibility and enabled state are ignored: the assertion is structural.

### HasControl / LacksControl semantics

Compared on `ElementFingerprint` **only**, against the pressable elements `ScreenIdentity.fromRoot`
collects — the same builder every identity already uses, so "is this control here?" means exactly
what it means everywhere else. `isBackAffordance` never takes part: it is derived from position,
which identity is not (the same rule `SameScreenPolicy` follows, round-4 finding N1 of `a2h-c2b.2`).

### The evaluation context — built once per tree

```kotlin
class TraitEvaluationContext private constructor(
    val packageName: String?,
    val nameKey: String?,                                   // ungated DedupPolicy.nameKey; null for a probe
    internal val pressableFingerprints: Set<ElementFingerprint>,
    private val containerRowsById: Map<String, List<List<Set<String>>>>,  // id -> containers -> rows -> ids
) {
    internal fun rowsOfContainersWithId(resourceId: String): List<List<Set<String>>>
    companion object {
        fun of(root: AccessibilityNodeSnapshot, name: ScreenNameIdentity? = null): TraitEvaluationContext
    }
}
```

Built once per observed tree — two walks, one collecting pressables through `ScreenIdentity.fromRoot`
and one indexing containers — and never per candidate. Every trait on every candidate is then a set
lookup: `holds` receives the context and never sees a node. `nameKey` reuses `DedupPolicy.nameKey`
(ungated — a WEAK name still equals itself, the same reason `SameNamePolicy` is ungated), so no second
name-key rule exists. *(Sketch corrected in round 5 to the real field and accessor names.)*

### The verdict — the unsettled guard in one place

```kotlin
enum class TraitVerdict { HOLDS, DOES_NOT_HOLD, UNSETTLED }

object TraitEvaluator {
    fun verdict(identity: ScreenIdentity, context: TraitEvaluationContext): TraitVerdict
        // no trait asserts presence (none at all, or LacksControl only) -> UNSETTLED;
        // else all { holds } -> HOLDS / DOES_NOT_HOLD
    fun matchKnownScreens(
        context: TraitEvaluationContext,
        knownScreens: Map<String, ScreenIdentity>,        // screenId -> identity
    ): ScreenTraitMatch
}
```

*(Corrected in round 4. Gate 2 approved `traits.isEmpty() -> UNSETTLED`. Round 1 (D6) generalised the
guard to "no trait asserts presence", decided by an exhaustive `when` over `Trait` so a future kind
must say whether it is positive — pending owner confirmation at Gate 3.)*

A tri-state enum rather than a `Boolean` so an unsettled screen cannot be read as a match or as a
non-match by accident. `matchKnownScreens` routes **every** candidate through `verdict`, so the guard
exists exactly once — which is what mutation 1 removes.

`verdict` is the trait predicate alone. `matchKnownScreens` composes it with the rest of the identity:
a known screen is a **candidate** when

1. `verdict == HOLDS`, and
2. its `packageName` equals the context's `packageName` **and the context has one** — two missing
   packages never match, and a package that merely shares a prefix is another app *(corrected in
   round 4: tightened in round 1, D5 — pending owner confirmation at Gate 3)*, and
3. its name key equals the context's `nameKey` — **only when both are non-null** (scope call E).

Every known screen whose verdict is `UNSETTLED` is listed, whatever its package.

### The result

```kotlin
sealed interface ScreenTraitMatch {
    val unsettledScreenIds: List<String>                   // sorted
    data class None(override val unsettledScreenIds: List<String>) : ScreenTraitMatch
    data class One(val screenId: String, override val unsettledScreenIds: List<String>) : ScreenTraitMatch
    data class Ambiguous(val screenIds: List<String>, override val unsettledScreenIds: List<String>) : ScreenTraitMatch
        // require(screenIds.size >= 2); sorted — a uniqueness violation: a case for an operator
}
```

**Determinism:** both id lists are sorted, so a `Map` presented in any insertion order yields an
equal result. There is no score and no tie-break to make non-deterministic. `Ambiguous` has no
accessor that picks a winner — resolving it is `a2h-c2b.3`'s case workflow.

### `identifyingElements` — derived (scope call C)

```kotlin
val identifyingElements: Set<ScreenElementIdentity>
    get() = traits.filterIsInstance<HasControl>().mapTo(linkedSetOf()) { it.element }
```

On `ScreenIdentity`. `LacksControl` elements are not identifying — they are what the screen is *not*.
`ScreenElementIdentity` gains no field, so nothing new enters the set equality `EntryRestorePolicy`
relies on.

## Steps

1. **Baseline, forced.** `gradlew test --rerun-tasks` at `fe13060` → `evidence/01-baseline-rerun.txt`
   (the Gate-1 baseline reported `UP-TO-DATE` and did not execute tests).
2. **Types.** Add `traits` + `identifyingElements` to `ScreenIdentity`; write `ScreenTraits.kt` in
   full with KDoc contracts.
3. **Pins.** Write `ScreenTraitsTest.kt` over synthetic `AccessibilityNodeSnapshot` trees — no framework
   mocks — covering every SCOPE "Done means" test bullet, plus:
   - construction guards (`minRows = 0`, empty schema, blank container id each throw);
   - `HasList` holds on a **merged** root built by `SyntheticAccessibilityTreeBuilder` from two
     viewports of the same list;
   - `HasList` does not pool rows across two same-id containers;
   - `verdict` is `UNSETTLED` for an identity with no traits;
   - `identifyingElements` excludes `LacksControl` elements.
4. **Green.** `gradlew test --tests "*ScreenTraitsTest"` → `evidence/02-traits-green.txt`.
5. **Mutations, red-first.** Apply one at a time, run `ScreenTraitsTest`, archive, revert:
   - `03-mutation-1-empty-guard.txt` — drop the `traits.isEmpty()` guard in `verdict`;
   - `04-mutation-2-ambiguous-collapse.txt` — return `One(first)` when candidates ≥ 2;
   - `05-mutation-3-haslist-ignores-schema.txt` — count every row regardless of child ids;
   - `06-mutation-4-lacks-as-has.txt` — `LacksControl.holds` returns the `HasControl` answer.
   Each must show ≥ 1 failing pin. `git diff` after each revert must equal the pre-mutation diff.
6. **Final state.** `gradlew test --rerun-tasks` → `07-final-test.txt`; `gradlew assembleDebug` →
   `08-final-assemble.txt`; the scope-A diff check (`git diff --stat fe13060 -- app/src`) →
   `09-diff-scope.txt`; the greps (`isIdentifying`, `TODO`) → `10-greps.txt`.
7. **Assess loop** with a fresh adversarial grader each round, stricter each round. *(Round 5 onward
   graded against a bounded mutation set, by owner decision on 2026-09-14.)*

## Risks

- **A pin that tests the fixture, not the rule.** Mitigated by the four mutations — each targets one
  rule and must turn a named pin red.
- **Resource-id format drift.** Exact full-id matching means an app that changes its package or
  build-flavour id prefix breaks `HasList`. Accepted: an operator-settled trait is expected to be
  re-settled when the app changes, and a lenient suffix rule would let `a:id/title` satisfy
  `b:id/title`.

## Approval

`[x] User confirmed`
