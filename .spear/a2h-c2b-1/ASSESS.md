# ASSESS — Code · a2h-c2b-1

Adapted for a Kotlin/Android repo and for a cycle that is **predominantly deletion**. The generic
TS/Python template rows have been replaced with their real equivalents — a rubric that scores
`tsc` and Prettier in a Gradle project grades nothing.

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Compiles | yes | `./gradlew assembleDebug` exits 0. |
| 2 | Tests pass | yes | `./gradlew test` exits 0, at the **final** artifact state. |
| 3 | New pins present | yes | All three Step-1 pins exist and pass. |
| 4 | RED→GREEN archived | yes | Pins 1 & 2 archived failing pre-fix and passing post-fix. |
| 5 | Guard mutation archived | yes | Pin 3 shown RED under an injected capture-the-foreign-screen mutation, then reverted. |
| 6 | Deletion complete | yes | Grep finds no `EXTERNAL_PACKAGE_BOUNDARY`, `SKIP_EDGE`, `ExternalPackageDecisionContext`, `DestinationCompatibilityReason`, `CrawlEdgeApproval`, `.compatibility(` in `app/src`. |
| 7 | Net production LOC down | yes | `git diff --stat -- app/src/main` is net negative. |
| 8 | Diff size sane | yes | ≤900 LOC per SCOPE. |
| 9 | Other pause reasons intact | yes | The four `pauseCheckpoint_*` tests pass **unmodified** except for forced signature changes. |
| 10 | No leftover debris | yes | No commented-out code, no new `TODO`, no orphaned imports, no dead private helpers left behind. |

## Lettered failure modes

Generic modes kept where they apply (C, F, G, J, K, L); B, E and H re-pointed at this cycle's real
risks; A and D dropped as TS-specific and replaced.

A. **Orphaned declaration** — a type, helper, or parameter left behind with zero callers after the
   deletion (`DestinationSettler`'s private helpers are the specific risk).
B. **Vacuous pin** — a new test that passes against pre-fix code, or would pass with the guard
   removed. The pin proves nothing.
C. **Missing edge case** — the *delayed* external transition (external package appears only after a
   retry) must still classify as `SKIPPED_EXTERNAL_PACKAGE`, not `SKIPPED_NO_NAVIGATION`.
D. **Silent behavior widening** — the unconditional skip catches a package change that the old code
   would have treated as in-package, changing non-external behavior.
E. **Test deleted to get green** — an existing test removed because it failed, rather than because
   the behavior it pins was deliberately removed. Every deletion in Step 4 must map to a named
   removed behavior.
F. **Hidden side effect** — a purely-named function writing artifacts or mutating tracker state.
G. **Off-spec deviation** — implementation diverges from SCOPE (e.g. leaving the approval reachable
   by any path, or introducing a new abstraction the SCOPE forbids).
H. **Recovery unproven** — the "walks back to the target app" claim asserted in prose or by a log
   string rather than by an observed `relaunchTargetApp` and a subsequent successful capture.
I. **Double-write** — the edge status written twice (the redundant `IN_PROGRESS` stamp), producing a
   contradictory manifest or duplicated XML rewrite.
J. **String-typed enum** — a status or reason compared as a magic string.
K. **Leaked credential** — key or token in code, log, or test.
L. **Circular dependency** — introduced between crawler modules.
M. **Governance edited silently** — `factory/*.md` changed inside the loop instead of being raised
   as a Gate-3 flywheel item.

## Known acceptable

The `code` adapter scans the whole repo and will surface pre-existing hits unrelated to this cycle:

- `tools/spear-cli/**` — vendored third-party, read-only by `factory/FUNCTION.md`.
- `.spear/a2h-4iq/`, `.spear/a2h-u68/` — other cycles' spec files.
- `thoughts/**`, `documentation/**`, `.beads/**`, `tmp/**`, `.codex-temp/`, `.obsidian/` —
  in-flight working files and notes, untracked or out of scope.
- Occurrences of the deleted symbols inside `.spear/a2h-c2b-1/*.md` itself — this spec names them in
  order to require their removal.

Register the stop with `--allow-fast-convergence` if the adapter's whole-repo count is non-zero for
these reasons alone. **Never edit vendored source to force a zero count.**

## Convergence

PASS when metrics 1–10 all pass, zero failure modes A–M are open, and metrics 1, 2, 6 and 7 have
been **re-run at the final artifact state** — evidence gathered before a later edit is void.
