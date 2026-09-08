# SCOPE

Bead: `a2h-c2b.1` — *Skip external packages entirely: remove the approval pause*
(parent epic `a2h-c2b`; blocks `a2h-c2b.2`)

## Goal

Make the deep crawler **never** follow a click that leaves the target app: when a click lands in a
foreign package, the crawler records the edge as `SKIPPED_EXTERNAL_PACKAGE` with the destination
package name and continues automatically — no operator prompt, no approval, no allow-list.

## Audience

The AppToHTML operator running a deep crawl on a device, and the downstream consumers of the crawl
artifacts (the epic `a2h-c2b` screen-identity work, and any agent reading the exported HTML/XML).
The operator's experience changes: they will no longer be asked to approve a package boundary, and
will no longer see an "Allowed packages" list on the saved-crawl screen.

## Why now

The external-package boundary is today *crossable on approval*: the crawl pauses, the operator can
say "continue", and the crawler then re-opens and captures a foreign app's screen. Technical
stakeholders decided (2026-09-08) that boundary should be uncrossable. This strengthens the
`factory/COMPANY.md` cross-app guardrail ("Safety over coverage") rather than contradicting it, so
no value-statement amendment is needed.

Removing the approval also removes its *only* consumer: the post-approval re-open and the
destination-compatibility matcher — one of three screen-level matchers, with exactly one production
call site (`DeepCrawlCoordinator.kt:632`). Deleting it now shrinks the surface that epic `a2h-c2b`
(one structured screen identity) has to redesign.

**Accepted cost (stakeholder-agreed):** cross-package subtrees become permanently uncapturable —
sign-in handoffs, system pickers, payment sheets.

## Mechanism (why "skip" is post-hoc, not pre-emptive)

Accessibility gives no way to know a control's destination *before* the click. So the boundary is
detected **after** the click: click → observe the package changed → decline to capture → record the
edge → move on. The crawler is briefly standing in the foreign app.

Recovery is **not new code**. Every edge already begins with `openChildFromScreen` →
`restoreLiveScreenForEdge` → `restoreToEntryScreenOrRelaunch`, and that helper's first probe is
`currentRoot?.packageName == selectedApp.packageName`. Standing in a foreign package fails that
probe and falls straight through to `host.relaunchTargetApp(selectedApp)`. This cycle **confirms
that path with a test** rather than assuming it (bead explicitly asks for this).

## Affected surface

**Deleted (external-package approval path):**
- `PauseCheckpointConfig.kt` — `PauseReason.EXTERNAL_PACKAGE_BOUNDARY`, `PauseDecision.SKIP_EDGE`,
  `ExternalPackageDecisionContext`
- `DeepCrawlCoordinator.kt` — the `awaitPauseDecision` call at the boundary (~L557–L700), the
  `PauseDecision.CONTINUE` branch (allow-list insert, post-approval re-open via a second
  `openChildFromScreen`, compatibility check, `external_boundary_restore_*` logging), the
  `allowedPackageNames` set and `formatAllowedPackageNames()`, and the now-unreachable
  `SKIP_EDGE ->` throw in `handlePauseCheckpointIfNeeded`
- `DestinationSettler.kt` — `compatibility()`, `DestinationCompatibilityResult`,
  `DestinationCompatibilityReason`, and the helpers left with no other caller
  (`meaningfulPressableIdentities`, `meaningfulPressableIdentity`, `normalizeCompatibilityToken`,
  `expectedLooksSparseForCompatibility`)
- `CrawlerSession.kt` — `skipExternalEdge()`, the `externalPackageContext` parameter
- `CrawlerModels.kt` — `CrawlEdgeApproval` and the `approval` field on the edge records;
  `withPausedForDecision`'s external-package fields
- `MainActivity.kt` — the external-package pause branches, `AllowedPackageRow`, the "Allowed
  packages" section
- `SavedCrawlLoader.kt` / `SavedCrawlRepository.kt` — `allowedPackages` / `AllowedPackage`
  derivation from `approval == EXPLICIT`
- `AccessibilityXmlSerializer.kt` / `ScreenXmlReader.kt` — the `approval` XML attribute and parser

**Kept:** `CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE` (now the automatic outcome), the edge's
`externalPackage` field, `PauseReason.ELAPSED_TIME_EXCEEDED`, `PauseReason.FAILED_EDGE_COUNT_EXCEEDED`,
`PauseDecision.CONTINUE`/`STOP`, and `handlePauseCheckpointIfNeeded` for those two reasons.

**Scope call for the human at this gate.** The bead's criterion *"no … allowed-package set remains"*
is read here at its **widest**: `CrawlEdgeApproval` can never be set to `EXPLICIT` again once the
approval is gone, so every derivation from it (`allowedPackages`, `AllowedPackage`, the XML
attribute, the operator UI row) is dead and is deleted too. `CLAUDE.md` states no backward-
compatibility requirement, so previously-saved crawls carrying an `approval` attribute are not a
constraint — the parser simply ignores the unknown attribute. **Say so at this gate if you want the
narrower cut** (leave `CrawlEdgeApproval` and the allow-list plumbing in place as dead code).

**Breaking changes:** the crawl XML loses its `approval` edge attribute; the operator UI loses the
external-package pause dialog and the "Allowed packages" list. No migration (crawl state is not
persisted beyond artifacts on disk).

## Inputs

- Bead: `bd show a2h-c2b.1`
- Governing values: `factory/COMPANY.md` (Safety over coverage), `factory/PRODUCT.md`
  ("asks before crossing app boundaries" — this cycle makes it *never* cross; PRODUCT.md wording is
  a flywheel candidate at Gate 3)

## Constraints

- **PR size:** ≤900 LOC diff. Larger than the 500 default *because it is predominantly deletion* —
  the approval path plus the compatibility matcher plus their tests. Net production LOC must go
  **down**.
- **Tests required:** yes — including a RED-first pin (below).
- **Type-check level:** strict (Kotlin compiler, `assembleDebug`).
- **No new abstraction.** This cycle only deletes and rewires an existing branch; introducing a new
  seam is out of scope.

## Done means

Executable checks (each is a test or a build; inspection is the last resort):

- [ ] **`./gradlew test` green** at the final artifact state.
- [ ] **`./gradlew assembleDebug` green** at the final artifact state.
- [ ] **Automatic skip:** a test drives a crawl where a click lands in a foreign package and asserts
      the edge ends `SKIPPED_EXTERNAL_PACKAGE` with `externalPackage` = the destination package,
      the crawl reaches a normal terminal state, and the test host recorded **zero**
      `awaitPauseDecision` calls.
- [ ] **RED-first pin:** the test above is shown FAILING against pre-fix behavior (the boundary
      still prompting) and passing after; both runs archived under `.spear/a2h-c2b-1/evidence/`.
- [ ] **Recovery confirmed, not assumed:** a test asserts that after skipping an external
      destination, the *next* edge on the same screen is attempted against the target app — the
      host observes a `relaunchTargetApp` for the target package following the skip, and that next
      edge captures normally.
- [ ] **No path can follow a click into a non-target package:** a test asserts that when a click
      leaves the target package, no child screen is captured and no edge reaches `CAPTURED` or
      `LINKED_EXISTING` for that click — under **any** host response, since no decision is asked.
- [ ] **The other two pause reasons still work:** existing/adapted tests show
      `ELAPSED_TIME_EXCEEDED` and `FAILED_EDGE_COUNT_EXCEEDED` still pause and still honor
      `CONTINUE` and `STOP`.
- [ ] **Deletion is real, not orphaned:** a repo grep finds no remaining reference to
      `EXTERNAL_PACKAGE_BOUNDARY`, `PauseDecision.SKIP_EDGE`, `ExternalPackageDecisionContext`,
      `DestinationCompatibilityReason`, or `.compatibility(` outside `.spear/` and `thoughts/`.
- [ ] **Net production LOC decreases** (`git diff --stat` on `app/src/main`).
- [ ] No commented-out code left behind; no `TODO` added.

`MAX_ROUNDS = 10`
