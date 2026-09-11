# PLAN — a2h-c2b.2

Structured screen identity with an explicit comparison policy. Scope approved at Gate 1; hint
widening withdrawn and the `hints` → `titleDisambiguators` rename added at Gate 2.

## Target design

### The type

```kotlin
internal data class ScreenElementIdentity(
    val fingerprint: ElementFingerprint,   // unchanged atom — bead says out of scope
    val isBackAffordance: Boolean,         // flagged, NOT filtered out
)

internal data class ScreenIdentity(
    // name half (Family B today)
    val packageName: String,                    // normalized token; "unknown" if blank
    val screenName: String,                     // normalized token; "unnamed" if blank
    val titleDisambiguators: List<String>,      // normalized, distinct, max 2 — today's `hints`
    val nameConfidence: ScreenDedupConfidence,
    // content half (Family A today)
    val rootClassName: String,
    val elements: Set<ScreenElementIdentity>,
)
```

Name and content stay **separate fields, never blended into a score** — the rule the redesign brief
sets in Part 5. Dedup reads the name half; replay reads the content half; nothing forces them
together.

### Behavior-preservation trap to respect

Today's builder is `collectPressableElements(root).distinctBy(ElementFingerprint)` **and then**
`.filterNot(::looksLikeEntryBackAffordance)` (`ScrollScanCoordinator.kt:352-358`). Dedup happens
**before** the back filter, so a first-occurrence survivor wins and only then is tested for
back-ness. The new builder must keep that order: `distinctBy` first (encounter order preserved,
first wins), *then* compute `isBackAffordance` on each survivor. Reversing it changes which element
survives and silently alters identities.

Serialization sorts the encoded elements, as today, so output stays deterministic
(`factory/FUNCTION.md` — deterministic transforms).

### The comparison result

```kotlin
internal data class ScreenIdentityComparison(
    val matched: Boolean,
    val reason: ScreenIdentityMatchReason,      // absorbs EntryScreenFingerprintMatchReason's 9
    val missing: Set<ScreenElementIdentity>,    // expected, not observed
    val extra: Set<ScreenElementIdentity>,      // observed, not expected
    val expectedCount: Int, val observedCount: Int, val overlapCount: Int,
    val expectedCoverage: Double, val observedCoverage: Double, val diceSimilarity: Double,
)
```

The count/coverage/Dice fields are carried forward deliberately: `EntryScreenResetResult` already
logs all six, and dropping them would regress crawl diagnostics.

### The policies

| Policy | Rule (byte-for-byte today's) |
|---|---|
| `EntryRestorePolicy` | package equal; root class equal; back affordances **excluded from both sides**; matched if exact, or observed ⊇ expected and \|observed\| >= \|expected\|, or Dice >= `2.0 / 3.0` |
| `SameScreenPolicy(countBackAffordances)` | root class equal and element sets equal, back included iff the flag says so |
| `NavigatedAwayPolicy` | composes `SameScreenPolicy` against the before-click and top identities; "navigated" = matched neither |
| `DedupPolicy` | derives the map key from package + screen name + title disambiguators; usable only when `nameConfidence == STRONG` |

`countBackAffordances` is set from the same condition that drives `usesEntryFingerprint` today —
`screenRecord.route.steps.isEmpty()`, i.e. "this is the crawl root". The boolean does not disappear;
it stops being an ad-hoc branch at six call sites and becomes one named policy argument.

### Serialization

`ScreenIdentityCodec` absorbs `ReplayFingerprintCodec` and becomes the single codec for the whole
structure, with two projections matching the two XML shapes that already exist:

- `<screen-identity package title title-disambiguator-1 title-disambiguator-2 />` — the name half.
- `<expected-replay root-class><element … back="true|false" /></expected-replay>` — the content
  half; `back` is the new attribute that lets the flag survive a round trip.

The dedup key becomes `v3:pkg:PKG:title:TITLE:disambiguator:D1|D2` — prefix bumped because the
segment name changed.

**A latent bug this fixes.** `expectedDestinationFingerprint` is written from
`openedChild.fingerprint` — a *viewport* fingerprint (`DeepCrawlCoordinator.kt:580`) — but
`AccessibilityXmlSerializer.kt:178` tries to decode it as a *name* identity via
`ScreenIdentityCodec.decode`, which always returns null, so the `package`/`title`/`hint-*` branch
there and its counterpart in `ScreenXmlReader.decodeDestinationIdentity` are **dead in practice**
and the element name `<expected-destination-identity>` lies about what it holds. Under one type the
two branches collapse into one honest serialization.

### In-memory model

- `CrawlScreenRecord.screenFingerprint: String` + `replayFingerprint: String` → one
  `identity: ScreenIdentity`.
- `CrawlRouteStep.expectedReplayFingerprint` / `expectedDestinationFingerprint` → `ScreenIdentity?`.
- `DestinationSettleRequest.fingerprint: (root) -> String` → `(root) -> ScreenIdentity`;
  `beforeClickFingerprint` / `topFingerprint` / `knownDestinationFingerprint` become identities.
- `CrawlRunTracker.screenFingerprintToId` keys on `DedupPolicy`'s derived key.
- `crawl-index.json` keeps both field names (`screenFingerprint`, `replayFingerprint`) as two
  projections of the one identity. It is a debug log with no reader — established at Gate 1 — so
  field-name churn there buys nothing.

### Explicitly not unified

`EntryScreenBackAffordanceDetector` (`ScrollScanCoordinator.kt:464`) stays as-is. It answers a
different question — *is there a back button I can press?* — over live **nodes** with toolbar and
ancestor context, where `looksLikeEntryBackAffordance` answers *is this element part of identity?*
over a flattened `PressableElement` with only `bounds.top`. Merging them is a behavior change and is
out of scope. Its `top <= rootTop + 300` is a different expression from the duplicated
`top <= 300` and is not covered by the de-duplication check.

## Steps

1. **Baseline.** Archive `./gradlew test assembleDebug` at pre-change HEAD →
   `evidence/00-baseline.txt`.

2. **Characterization pins, written against TODAY's code.** New
   `ScreenIdentityCharacterizationTest.kt` pinning outcomes at two stable seams:
   - `ScrollScanCoordinator.rewindToEntryScreen` → `EntryScreenResetOutcome` + match reason, for:
     exact match, observed-enriches-expected, Dice just above `2/3`, Dice just below `2/3`,
     root-class mismatch, foreign package, and no-expected-fingerprint.
   - Route-step replay through the existing `DeepCrawlCoordinator` test harness → matched vs
     replay-failure, for: exact match, one extra element (must FAIL — zero tolerance today), and
     root-vs-child back-affordance asymmetry.
   - Dedup grouping at the `ScreenNaming.dedupFingerprint` / `CrawlRunTracker` seam, so the rename
     in Step 7 has something to be proven pure against.
   Run GREEN → `evidence/01-characterization-pre.txt`.

3. **Prove the pins bite, pre-change.** Inject one mutation into today's code (move
   `DICE_SIMILARITY_THRESHOLD` to `0.95`); pins go RED → `evidence/02-mutation-pre-red.txt`;
   revert; confirm green.

4. **Introduce the type and the policies.** New `ScreenIdentity.kt` and `ScreenIdentityPolicy.kt`;
   the builder moves to `ScrollScanCoordinator` replacing the four `buildViewportFingerprint`
   variants (the geometry-sensitive one stays, unchanged, for scroll-loop detection).

5. **Convert the sites, S1–S7**, in dependency order: `DestinationSettler` (S5, S6) →
   `ScrollScanCoordinator` (S1) → `DeepCrawlCoordinator` (S2, S3, S4) → `CrawlRunTracker` (S7).
   Delete `EntryScreenFingerprintMatcher` and the duplicated helper pair as S1 lands.

6. **Delete the dead root-recovery pair** (`recoverToRootAfterEdgeFailure`, `navigateBackToRoot`).

7. **Rename `hints` → `titleDisambiguators`**, as a pure rename in its own commit-sized step: ~31
   Kotlin references across 8 files, the XML attributes `hint-1`/`hint-2` →
   `title-disambiguator-1`/`title-disambiguator-2`, and the key segment `:hint:` →
   `:disambiguator:` with the prefix bumped `v2` → `v3`. The cap stays 2, `minIdentityHintScore`
   stays 120, and `collectIdentityHints`' selection rule is untouched. Step 2's dedup-grouping pin
   must still pass unchanged apart from the identifier.

8. **Serialization boundary.** `ScreenIdentityCodec` absorbs `ReplayFingerprintCodec`; the XML writer
   and reader gain `back=` and the renamed attributes; `SavedCrawlLoader` rebuilds identities;
   `CrawlManifestStore` prints the two projections. Add the XML round-trip test: an identity with two
   disambiguators and a flagged back affordance survives write→read unchanged.

9. **Adapt the pins, assertions untouched.** The only permitted edit to Step-2 pins is how the
   *expected value* is constructed (`String` → `ScreenIdentity`) and the renamed identifier.
   Changing any assertion is a scope violation and must be reported, not absorbed. Add the
   new-capability tests: addressable fields, back-in-structure, one-builder-for-every-screen,
   missing/extra derivable.

10. **Prove the pins still bite, post-change.** Re-inject the equivalent mutation into
    `EntryRestorePolicy` (count back affordances) → pins RED → `evidence/03-mutation-post-red.txt`;
    revert.

11. **Re-verify at the final state.** `./gradlew test assembleDebug` plus every Gate-1 grep, run
    against the final artifact → `evidence/04-final.txt`. Green evidence from before Step 9 is void.

12. **PR description** — summary, the artifact-format break, test plan, rollback.

## Risks

- **Silent identity drift** from reordering `distinctBy` and the back filter — Step 4's specific
  trap, covered by the Step-2 pins.
- **Pin laundering**: adapting a pin's assertion in Step 9 to make it pass. Guarded by Step 10's
  post-change mutation and by ASSESS metric 5.
- **Impure rename**: Step 7 changing a dedup outcome while claiming to be cosmetic. Guarded by the
  dedup-grouping pin added in Step 2.
- **Diagnostic regression**: the six coverage/Dice fields silently dropped from crawl logs.
- **Scope creep into `EntryScreenBackAffordanceDetector`** — explicitly excluded above.

## Approval

`[x] User confirmed`
