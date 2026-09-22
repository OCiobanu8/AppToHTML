# ASSESS — Code (a2h-u68: does this screen identity hold?)

Retargeted 2026-09-18 to the SCOPE approved that day. The previous version graded
`EntryScreenFingerprintMatcher`, an "authority table" and inert/live edit classification — all
superseded by the `a2h-c2b` redesign (that matcher is deleted). Kotlin/Android + PowerShell.

Grading is **independent and ratcheting**: a fresh adversarial grader each round, gathering its own
evidence, attacking a dimension the previous round did not.

Two risks define this unit of work:

1. **A confident verdict the crawler would not agree with.** A tool that certifies an identity the
   replay path then rejects is worse than no tool. Metrics 5–8 exist for that.
2. **A4c silently changing how existing crawls behave.** The trait condition at replay arrival is
   safe *only* because an `UNSETTLED` identity skips it entirely. If that guard is weak, every
   resumed crawl changes behaviour. Metrics 9–11 exist for that, and it outranks every other
   finding.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exit 0, no new warnings vs. Step-0 baseline. |
| 2 | Full suite green | yes | `./gradlew test` exit 0. |
| 3 | No test-count regression | yes | Count >= Step-0 baseline. Catches a "fix" that deletes a pin. |
| 4 | Criterion to test map complete | yes | Every SCOPE machine-checked line maps to a named test that exists and runs. Unmapped line = defect. |
| 5 | **No second identity definition** | yes | No normalization, dice math, threshold, back-affordance rule, list-item rule, trait predicate or identity-set construction outside the production classes. Grep new code for `2.0 /`, `0.66`, `lowercase()`, `trim()`, `"back"`, `intersect`, `all {` over traits; justify every hit. |
| 6 | **Verdicts come from production code** | yes | Trait verdict traces to `TraitEvaluator.verdict`; uniqueness to `TraitEvaluator.matchKnownScreens`; element set to `SameScreenPolicy.compare`; re-clickability to `ClickFallbackMatcher.selectMatches`. No hand-rolled comparison on any verdict path. |
| 7 | **Differential holds** | yes | For the synthetic case table the validator's three answers equal the direct production calls on the same inputs. |
| 8 | **Four validator mutations red** | yes | `matchKnownScreens` collapsed to first match; `SameScreenPolicy` ignoring root class; Step-2 factory inverted; `ClickFallbackMatcher` dropping the enabled check. Each archived red, then reverted. |
| 9 | **A4c opt-in guarantee** | yes | For `UNSETTLED` identities the replay-arrival outcome is byte-identical to pre-change across holding and non-holding screens. Deleting the guard turns the pin red (archived). **Highest severity in this cycle.** |
| 10 | **A4c uses traits, not the element set** | yes | The replay condition consults `TraitEvaluator.verdict` only. Any element-set comparison added at that site is a defect — it reintroduces exactly the brittleness the epic removes. |
| 11 | **A4c degrades into the existing path** | yes | A non-holding trait yields `ScreenPreparationResult.Failure` into `handlePreparationFailure`. No new pause, state, queue or case. The replay **name** rule and message are unchanged. |
| 12 | **Step-2 exception intact** | yes | The literal-`true` route-step destination site is untouched; rewiring it to the factory turns its pin red (archived). |
| 13 | **Refactor fidelity (Steps 1–2)** | yes | Existing suite passes unmodified **and** each archived injected mutation is shown red. A green suite alone does not satisfy this. |
| 14 | **A4b red then green** | yes | The resumed-crawl `replayFingerprint` pin archived failing on pre-Step-5 code, then passing. A pin never shown red proves nothing. |
| 15 | **Hand edits survive every rewrite** | yes | Including `rewriteScreenHtml` — the omission path. Test asserts traits intact after a load then rewrite in **both** files. |
| 16 | XML/HTML disagreement is loud | yes | Differing blocks yield `INVALID_INPUT` naming the difference; neither side is silently preferred. Missing HTML likewise. |
| 17 | Malformed input named, never crashed | yes | Each SCOPE `INVALID_INPUT` case names where, returns no verdict, throws nothing. |
| 18 | Tree reader fidelity | yes | Synthetic round trip preserves identity, trait verdicts and click candidates; the real-capture fixture matches the device-written `fingerprint=` values. |
| 19 | Headline never blended | yes | Trait answer and element-set answer independently reported; a test holds traits while the element set differs. |
| 20 | Determinism | yes | Two runs byte-identical, including reordered known screens and reordered traits. |
| 21 | Inputs unmodified | yes | `.xml`/`.html` byte-identical after a run; output only under the output directory. |
| 22 | Harness inert by default | yes | Plain `./gradlew test` runs no validation, writes no report, and the suite still caches. |
| 23 | Script parses | yes | `[ScriptBlock]::Create()` over the skill script exits 0. |
| 24 | Docs updated | no | `documentation/` carries the identity block and tool; `crawler-module.md` and `data-and-state.md` point at it. |
| 25 | No leftover debug | yes | No stray `println`, no commented-out code, no `TODO` added, no absolute paths (no `E:\Logs`) in shipped code or scripts; every new public type and function has a KDoc contract. |
| 26 | Evidence at final state | yes | Headline checks re-run **after** the last artifact edit. |

## Lettered failure modes

Generic template modes dropped as inapplicable (`any` types, snapshot churn, migrations, N+1,
credentials). These are the modes this feature can actually exhibit.

**A4c — the behaviour change (highest severity):**

A. **`UNSETTLED` guard defeated** — the trait condition consulted for identities carrying no traits,
   so machine-proposed identities can newly fail replay. Turns every resumed crawl into a behaviour
   change and falsifies the whole safety argument for A4c.
B. **Element set checked at replay arrival** — adding a `SameScreenPolicy` comparison at that site
   instead of (or beside) the trait condition. Reintroduces the byte-equality brittleness the epic
   exists to remove; the site's own comment explains why content must not be compared there.
C. **New mechanism smuggled in** — a pause, queue, case or crawl state added at the failure path
   rather than reusing `handlePreparationFailure`. That is `a2h-c2b.3`'s work.
D. **The name rule altered** — the replay name comparison, its tolerance or its message changed
   while adding the trait condition.
E. **Divergence message uninformative** — reproducing `a2h-c2b.6`'s `Expected X but found X` shape
   at the new site instead of naming the failing trait.

**Identity and verdict integrity:**

F. **Reimplemented identity** — any reconstruction of label normalization, the dice threshold, the
   identity-set construction, the list-item rule, the back-affordance filter or a trait predicate.
   The second definition this bead exists to prevent.
G. **Verdict from the wrong side** — the merged/scroll-merged tree used as the observed side instead
   of step 0, certifying an identity the crawler cannot observe on arrival.
H. **Headline and element set coupled** — the trait outcome derived from element-set overlap or vice
   versa, so a failing element set hides behind a passing headline or the reverse.
I. **Diagnostics leaking into the verdict** — weak-identity counts, collisions or paste suggestions
   consulted, even indirectly, when deciding the headline.
J. **Vacuous independence test** — the "traits hold while the element set differs" fixture built so
   it would pass even if the two *were* coupled.
K. **Uniqueness under-reported** — `NOT_UNIQUE` listing only the first colliding screen rather than
   every one, or an ambiguous match silently resolved to a winner.

**Format and persistence:**

L. **Two syntaxes for one fact** — the HTML block rendered differently from the XML element, or
   parsed by a second parser, so the two can drift.
M. **Drift silently resolved** — picking XML or HTML as authoritative on disagreement instead of
   `INVALID_INPUT`, so an operator edit to the other file is ignored.
N. **`rewriteScreenHtml` not threaded** — hand-added traits erased on the next edge resolution or
   resume. The silent-erasure path called out in PLAN fact 4.
O. **Dedup shifted** — the loader now producing different name or dedup keys than before, changing
   which screens link.
P. **Pre-change capture regenerated** — silently synthesizing an identity block for a capture that
   has none instead of reporting `INVALID_INPUT` and asking for a recapture.
Q. **Trait constructor refusal mishandled** — a degenerate `has-list` crashing the reader or
   yielding a verdict rather than a named `INVALID_INPUT`.
R. **Non-deterministic output** — trait or element ordering, map iteration or file listing order
   leaking into the report bytes.

**Process:**

S. **Framework mocking** — mocking `AccessibilityNodeInfo` instead of synthetic snapshots.
   Forbidden by `factory/FUNCTION.md`.
T. **Semantics tuned, not observed** — any change to a threshold, normalization, tolerance or policy
   semantics. Out of scope; a bad rule the tool exposes becomes a bead.
U. **Pin never shown red** — a red to green claim (A4b, Steps 1–2 mutations, the A4c guard) archived
   only green. Proves nothing.
V. **Device-only criterion graded as automated** — treating a Gate-3 human check as machine-passed,
   or substituting a unit test for the real-device settle loop.
W. **Convergence on superseded evidence** — declaring green from a run predating the final edit.
X. **Test churn unjustified** — pins asserting exact serialized XML/HTML text edited without being
   listed and justified, hiding a real format regression inside expected churn.

## Known acceptable (whole-repo adapter noise)

The `code` adapter scans the whole repo and surfaces pre-existing hits unrelated to this cycle.
These are **not** defects of this unit of work and must not be "fixed":

- `tools/spear-cli/**` — vendored third-party, read-only per `factory/FUNCTION.md`.
- `.beads/`, `.obsidian/`, `.codex-temp/`, `tmp/` — tracker state, editor config, scratch.
- `thoughts/shared/**` — working documents, deliberately uncommitted. Includes the pre-redesign
  design doc, which this SCOPE supersedes and which is **not** to be rewritten here.
- `documentation/**` files this cycle does not touch.
- `.spear/a2h-4iq/**`, `.spear/a2h-c2b-1/**`, `.spear/a2h-c2b-2/**`, `.spear/a2h-c2b-4/**` — other
  cycles' spec files.
- Other in-flight uncommitted work present at cycle start (see Step-0 `git status`).

Register the stop with `--allow-fast-convergence` rather than editing any of the above.

## Convergence

PASS when: metrics 1–26 all pass, zero lettered failure modes open, and the headline checks
(`./gradlew test`, `./gradlew assembleDebug`) were re-run **at the final artifact state**.

Metric 9 (the A4c opt-in guarantee) is a **blocking** metric: while it is open, the cycle cannot
converge regardless of every other metric, because a crawl behaviour change that was not asked for
is the one outcome this SCOPE forbids outright.

Device-dependent criteria are explicitly **out of the loop's authority** — the Gate-3 items in SCOPE
are graded by a human against evidence in `.spear/a2h-u68/evidence/`. The loop may not mark them
passed, and may not substitute a unit test for the real-device capture, settle, break, paste-back
loop or for the `a2h-c2b.3` evidence deliverable.
