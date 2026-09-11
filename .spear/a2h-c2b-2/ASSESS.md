# ASSESS — Code · a2h-c2b-2

Adapted for a Kotlin/Android repo and for a cycle that is a **purely behavior-preserving
structural refactor**, plus one pure rename. The generic TS/Python template rows are replaced with their
real equivalents — a rubric scoring `tsc` and Prettier in a Gradle project grades nothing.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exits 0 at the **final** artifact state. |
| 2 | Tests pass | yes | `./gradlew test` exits 0 at the **final** artifact state. |
| 3 | Characterization pins present | yes | Entry-restore pins cover exact, enrichment, Dice-above, Dice-below, root-class mismatch, foreign package, no-expected. Route-step pins cover exact, one-extra-element, root-vs-child asymmetry. A dedup-grouping pin exists for the rename. |
| 4 | Pins archived pre and post | yes | GREEN pre-refactor and GREEN post-refactor runs both under `evidence/`. |
| 5 | Pins bite, **both sides** | yes | A mutation turns them RED pre-change (`02`) **and** post-change (`03`), both archived and reverted. A pin green on both sides of a mutation is worthless. |
| 6 | Assertions not laundered | no | Step-2 pins differ post-refactor **only** in how the expected value is constructed and in the renamed identifier. Any changed assertion is a defect unless reported as a scope deviation. |
| 7 | Structure is addressable | yes | A test reads package, screen name, title disambiguators, root class and element set as separate fields, parsing no strings. |
| 8 | Back affordance in the structure | yes | Root identity **contains** the back element flagged; `EntryRestorePolicy` still matches the same screen without it. |
| 9 | One builder, every screen | yes | Root and child identities come from the same builder — root's now carries the back affordance. |
| 10 | Difference derivable | yes | A non-matching comparison exposes `missing` and `extra` as sets; the test parses no encoded string. |
| 11 | XML round-trips the structure | yes | An identity with two disambiguators and a flagged back affordance survives write→read unchanged. |
| 12 | Rename complete | yes | Grep over `app/src/main`: no `hint` identifier or `hint-` attribute for this concept; dedup key carries the `v3` prefix. |
| 13 | Rename is **pure** | yes | The Step-2 dedup-grouping pin passes unchanged apart from the identifier; cap stays 2, `minIdentityHintScore` stays 120, `collectIdentityHints` selection untouched. |
| 14 | Sites converted | yes | Grep over `app/src/main`: zero `logicalEntryViewportFingerprint`, `geometrySensitiveEntryViewportFingerprint`, `EntryScreenFingerprintMatcher`, `usesEntryFingerprint`. |
| 15 | Duplication removed | yes | `looksLikeEntryBackAffordance`, `normalizeFingerprintToken` and the literal `top <= 300` each appear exactly once in `app/src/main`. |
| 16 | Dead recovery pair deleted | yes | No `recoverToRootAfterEdgeFailure` / `navigateBackToRoot` in `app/src`. |
| 17 | Thresholds untouched | yes | `git diff` shows no change to `2.0 / 3.0`, `top <= 300`, `minIdentityHintScore`, or the cap of 2 as values. |
| 18 | Manifest still a debug log | yes | `app/src/main` contains no reader of `crawl-index.json`, only `CaptureFileStore`'s writer. |
| 19 | Diagnostics preserved | no | The six coverage/overlap/Dice fields still reach the crawl log. |
| 20 | No pre-existing test weakened | no | Every modified/deleted pre-existing test maps to a named behavior in SCOPE "Breaking changes", or is a mechanical rename/signature change. Assessed per test. |
| 21 | No leftover debris | yes | No commented-out code, no new `TODO`, no orphaned imports, no dead private helpers left behind. |

Diff size is **not** graded — no cap, set deliberately at Gate 1.

## Lettered failure modes

Generic modes kept where they apply (F, G, J, K, L); the rest re-pointed at this cycle's real risks.

A. **Identity drift from builder reordering** — `distinctBy` and the back-affordance test applied in
   the wrong order, so a different element survives dedup. The specific trap named in PLAN.
B. **Vacuous pin** — a characterization test that passes with the policy gutted, or that was never
   shown RED under any mutation. Metric 5 exists because this is the likeliest failure here.
C. **Pin laundering** — an assertion quietly relaxed in Step 9 so the refactored code passes.
   Distinct from B: the pin was real and got weakened.
D. **Silent tolerance change** — a threshold, a set-comparison direction, or a policy default that
   differs from today without being named in SCOPE. Includes back affordances counted where they
   were excluded, the reverse, or the disambiguator cap/selection rule drifting under cover of the
   rename.
E. **Half-migration** — a comparison site still reading a fingerprint string alongside sites reading
   the structure, so two identity models run at once. Worse than either endpoint.
F. **Hidden side effect** — a purely-named function writing artifacts or mutating tracker state.
G. **Off-spec deviation** — implementation diverges from SCOPE (e.g. making dedup content-aware,
   or making saved identity authoritative on resume — both explicit non-goals).
H. **Diagnostic regression** — crawl log lines lose fields, or a match reason collapses several of
   today's nine distinguishable reasons into one.
I. **Round-trip loss** — the back flag or the title disambiguators dropped between XML write and
   read, so a resumed crawl silently gets a different identity than the one captured. Includes a
   writer/reader pair that disagree on the renamed attribute.
J. **String-typed enum** — a policy, reason or outcome compared as a magic string.
K. **Leaked credential** — key or token in code, log, or test.
L. **Circular dependency** — introduced between crawler modules.
M. **Governance edited silently** — `factory/*.md` changed inside the loop instead of being raised as
   a Gate-3 flywheel item.
N. **Scope creep into the node-level detector** — `EntryScreenBackAffordanceDetector` altered or
   merged with the element-level predicate. PLAN excludes it explicitly.

## Known acceptable

The `code` adapter scans the whole repo and will surface pre-existing hits unrelated to this cycle:

- `tools/spear-cli/**` — vendored third-party, read-only by `factory/FUNCTION.md`.
- `.spear/a2h-4iq/`, `.spear/a2h-c2b-1/`, `.spear/a2h-u68/` — other cycles' spec files.
- `thoughts/**`, `documentation/**`, `.beads/**`, `tmp/**`, `.codex-temp/`, `.obsidian/` — in-flight
  working files and notes, untracked or out of scope.
- Occurrences of the removed symbols inside `.spear/a2h-c2b-2/*.md` itself — this spec names them in
  order to require their removal.

Two design points a grader may read as defects and should not:

- **Dedup remains a hash-map lookup**, not a ranked search. `DedupPolicy` derives the key; the
  lookup stays O(1) exact-match because SCOPE leaves dedup's rule exactly as it is. Turning
  dedup into a ranked search is a named non-goal.
- **`EntryScreenBackAffordanceDetector` still exists** and still contains back-affordance token
  logic. It answers a different question over different inputs — see failure mode N.

## Convergence

PASS when metrics 1–21 all hold, zero failure modes A–N are open, and the headline checks have been
re-run at the **final** artifact state (green evidence predating the last edit is void).
