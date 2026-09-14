# ASSESS — Code · a2h-c2b-4

Adapted for a Kotlin/Android repo and for a cycle that **adds a capability without wiring it into the
crawl** (scope call A). The generic TS/Python rows are replaced with their real equivalents. The
grader's job is to prove each metric from fresh evidence, not from this cycle's archived runs.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exits 0 at the **final** artifact state. |
| 2 | Tests pass | yes | `./gradlew test --rerun-tasks` exits 0 at the **final** artifact state (`UP-TO-DATE` is not evidence). |
| 3 | One sealed hierarchy, one list | yes | `ScreenIdentity.traits: List<Trait>`; `Trait` is sealed with exactly `HasList`, `HasControl`, `LacksControl`; an exhaustive `when` without `else` compiles in a test. |
| 4 | List recognised across rows | yes | Cart traits built from tree A hold on tree B with different row values **and** a different row count. |
| 5 | HasList reads the schema | yes | Does not hold when a row lacks a listed child id, when fewer than `minRows` rows qualify, when the container id is absent; does not pool rows across two same-id containers; holds on a merged root built by `SyntheticAccessibilityTreeBuilder`. |
| 6 | Vacuous construction refused | yes | `minRows = 0`, empty `rowChildResourceIds`, blank container id each throw. |
| 7 | HasControl ignores back flag and geometry | yes | A control flagged `isBackAffordance` in the top band still satisfies `HasControl` when it appears lower on the screen, and vice versa. |
| 8 | Negation works | yes | `LacksControl(x)` fails on a tree with `x`, holds without it, inside a multi-trait conjunction. |
| 9 | Empty is guarded | yes | `verdict` is `UNSETTLED` for no traits; such a screen is never a candidate and is listed in `unsettledScreenIds`. |
| 10 | Zero / one / more-than-one | yes | `None`, `One(id)`, and `Ambiguous` listing **every** matching id — tested with ≥ 3 matches so "first two" cannot pass. |
| 11 | Uniqueness over the whole identity | yes | Same traits, different names → `One` when the observed screen is named, `Ambiguous` for an unnamed probe; a foreign-package identity with holding traits is not a candidate. |
| 12 | Deterministic | yes | Same tree, same known screens in two insertion orders → equal results, including id order. |
| 13 | Pins bite | yes | Mutations 1–4 from PLAN step 5 each turn ≥ 1 pin RED, archived as `evidence/03`–`06`, and reverted. The grader re-applies **at least one mutation of its own choosing** and confirms RED. |
| 14 | `isIdentifying` derived | yes | `identifyingElements` equals the `HasControl` elements, excludes `LacksControl`; grep finds no `isIdentifying` on `ScreenElementIdentity`. |
| 15 | No crawl outcome moved | yes | `git diff fe13060 -- app/src/main` touches only `ScreenIdentity.kt` and `ScreenTraits.kt`; no pre-existing file under `app/src/test` modified. |
| 16 | Walk once | yes | `Trait.holds` takes a `TraitEvaluationContext`, never a node; `matchKnownScreens` performs no traversal per candidate. |
| 17 | Contracts documented | no | Every new public type and function has KDoc stating its contract, including the vacuous-truth guards. |
| 18 | No leftover debris | yes | No commented-out code, no new `TODO`, no unused imports or private helpers. |

Diff size is **not** graded — no cap, set at Gate 1.

## Lettered failure modes

A. **Vacuous truth** — any path where an empty collection makes an assertion true: empty trait list,
   empty row schema, `minRows = 0`, or an empty candidate set read as a match. The bead's named trap.
B. **Vacuous pin** — a test that still passes with the rule gutted. Metric 13 exists for this.
C. **Silent merge** — `Ambiguous` collapsed, truncated, or resolvable through an accessor that picks a
   winner; or a caller-facing API that returns a single id when several match.
D. **Equality contamination** — any new field entering `ScreenElementIdentity` equality, or
   `isBackAffordance` taking part in a `HasControl`/`LacksControl` comparison.
E. **Premature wiring** — any crawl call site (dedup, replay, restore, settling) invoking trait
   evaluation. Scope call A forbids it this cycle.
F. **Hidden side effect** — evaluation logging, writing, or mutating tracker state.
G. **Off-spec deviation** — implementation diverges from SCOPE/PLAN (e.g. suffix-matching resource
   ids, pooling rows across containers, persisting traits to XML).
H. **Non-determinism** — hash-set or hash-map iteration order leaking into any list in the result.
I. **Per-candidate tree walk** — a trait or the matcher traversing nodes instead of the context.
J. **String-typed enum** — a verdict or result kind compared as a magic string.
K. **Leaked credential** — key or token in code, log, or test.
L. **Circular dependency** — introduced between crawler files.
M. **Governance edited silently** — `factory/*.md` changed inside the loop instead of raised at Gate 3.
N. **Name-key drift** — a second name-key rule instead of `DedupPolicy.nameKey`, or the gated
   `keyFor` used so a WEAK-named screen stops matching itself.

## Known acceptable

- `tools/spear-cli/**` — vendored third-party, read-only by `factory/FUNCTION.md`.
- `.spear/a2h-4iq/`, `.spear/a2h-c2b-1/`, `.spear/a2h-c2b-2/`, `.spear/a2h-u68/` — other cycles.
- `thoughts/**`, `documentation/**`, `.beads/**`, `tmp/**`, `.codex-temp/`, `.obsidian/`, and the
  pre-existing working-tree edits to `HOW-TO-USE.md`, `factory/COMPANY.md`, `factory/SURFACE.md` —
  in-flight work predating this cycle, not touched by it.

Design points a grader may read as defects and should not:

- **`TraitEvaluator` has no production caller.** Deliberate — scope call A. Nothing produces traits
  until `a2h-c2b.3` / `a2h-u68`; wiring now would make every screen unsettled.
- **`LacksControl` is produced nowhere.** Deliberate — scope call D.
- **Traits are absent from the crawl XML.** Deliberate — scope call B.

## Convergence

PASS when metrics 1–18 all hold, zero failure modes A–N are open, and the headline checks (1, 2, 13,
15) have been re-run at the **final** artifact state — green evidence predating the last edit is void.
