# Screen traits: identity as assertions checked against the screen

Bead `a2h-c2b.4` · epic `a2h-c2b` · targets `epic/structured-screen-identity` (not `main`).

## Summary

A `ScreenIdentity` can now carry **traits**. A trait is an assertion about the screen that is
checked against the live accessibility tree, instead of comparing two element sets. Asking "which
known screens' traits hold here?" returns **zero, one, or more than one** screen. When more than one
screen matches, the result is a case that lists every match, never a silent merge.

This is what lets a list screen be recognised twice. The trait *"a `cart_items` list whose rows carry
`title`, `price` and `quantity`"* stays true while every row changes. An exact element set can't say
that, because on a list screen the rows are the data.

## What changed

| File | Change |
|---|---|
| `crawler/ScreenTraits.kt` | **new**: the sealed `Trait` hierarchy, `TraitEvaluationContext`, `TraitVerdict`, `ScreenTraitMatch` and `TraitEvaluator` |
| `crawler/ScreenIdentity.kt` | +17 lines: a `traits` field that defaults to empty, and a derived `identifyingElements` |
| `ScreenTraitsTest.kt` | **new**: 50 pins over synthetic trees; nothing mocks the framework |

- **`HasList(containerResourceId, minRows, rowChildResourceIds)`** matches a repeating group by its
  row schema. Ids are compared exactly, and rows are never pooled across two containers that share
  an id. Construction refuses shapes that would always be true: `minRows < 1`, an empty schema, and
  blank ids. The trait keeps its own read-only copy of the schema.
- **`HasControl(element)` / `LacksControl(element)`** compare on `ElementFingerprint` only. The
  back-affordance flag and geometry never take part.
- **`TraitEvaluationContext.of(root, name?)`** walks the tree once per observed screen. After that,
  checking any number of candidates is just set lookups.
- **`TraitEvaluator.verdict`** returns `HOLDS`, `DOES_NOT_HOLD` or `UNSETTLED`. An identity with no
  traits, or with only negations, is unsettled and never matches. This guards the Kotlin trap where
  `emptyList().all {}` is true.
- **`TraitEvaluator.matchKnownScreens`** evaluates every known screen and returns `None`, `One` or
  `Ambiguous`. Each result carries the sorted list of unsettled screens. A known screen is a candidate
  only when its traits hold, its package matches, and its name key matches (the name counts only when
  both sides have one). A missing package never matches, and a package that merely shares a prefix is
  another app.
- **`identifyingElements`** is derived from the `HasControl` traits, not stored on each element. So no
  new field enters the `ScreenElementIdentity` equality that `EntryRestorePolicy` compares directly.

## Not in this PR (deliberately)

- **Not wired into any crawl decision.** Nothing produces traits until `a2h-c2b.3` (propose and
  settle) and `a2h-u68` (the operator's validate tool). Every identity therefore has empty traits, so
  no crawl outcome can change. Dedup, replay, entry restore and destination settling are untouched.
- **Traits are not yet persisted in the crawl XML.** The format will be designed together with
  operator settling in `a2h-c2b.3`.

## Decisions taken during the cycle

- **Gate 1, scope calls A–E:** library only; no XML yet; `isIdentifying` derived rather than stored;
  `LacksControl` implemented but not produced; the name takes part when the observed screen is named.
- **Accepted at Gate 3, meaning changes made during the Assess loop** (see the SCOPE amendment
  block):
  - an identity made only of negations is unsettled;
  - a missing package never matches;
  - an unnamed known screen can match a named observed one;
  - "walked once" means once per tree, never once per candidate.
- **2026-09-14, owner decision:** grading switched to a bounded criterion from round 5 onward.

## Verification

- `./gradlew test --rerun-tasks`: **347 tests, 0 failures** (297 before). `./gradlew assembleDebug`
  is green, with no compiler warnings in the changed files.
- **Seven rounds of independent adversarial grading.**
  - Rounds 1–4 ran unbounded mutation sweeps. They found 8, 9, 12 and 10 defects. Every one was a
    missing pin or wrong wording; none was a wrong answer.
  - Rounds 5–7 were bounded: 156 archived mutants plus 112 systematic operator mutants.
  - Round 7: **CONVERGED**.
- Every fix was proven either by a run that failed before the fix or by a mutant that is now killed.
- Evidence lives under `.spear/a2h-c2b-4/evidence/` (git-ignored by design and reproducible). The
  grader reports are files `11`, `18`, `25`, `31`, `37`, `43` and `48`.

**How to check it yourself**

```bash
./gradlew testDebugUnitTest --tests "com.example.apptohtml.crawler.ScreenTraitsTest"
```

```bash
./gradlew test --rerun-tasks
```

Note: `./gradlew test --tests …` fails on this project. Use the per-variant task, as above.

## About the "16 defects" in the SPEAR output

`spear resolve` reports 16 open defects. All of them are hits from the vendored `tools/spear-cli`
source: `console.log` calls and one `: any`, plus a generic "score against the rubric" item. They are
listed as known-acceptable in `ASSESS.md`, and vendored code is read-only under `factory/FUNCTION.md`.
**None of them points at this change.**

## Follow-ups filed

- `a2h-c2b.10`: normalize trait order in identity equality before settled identities are persisted.
- `a2h-c2b.11`: the scroll merge reshapes lists. It collapses identical rows and may copy a wrapped
  list, so a proposer must not derive `minRows` from a merged row total.
- `a2h-c2b.12`: trait follow-ups surfaced by the bounded rounds.

## Rollback

Revert the commit. `traits` defaults to empty and has no production caller, so nothing depends on it.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
