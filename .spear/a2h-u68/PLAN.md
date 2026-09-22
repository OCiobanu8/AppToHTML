# PLAN

Bead: `a2h-u68` — *Validate whether a screen fingerprint holds, against a live screen or a second
capture*. Branch `feat/validate-screen-identity` off `epic/structured-screen-identity` at `21ec46e`;
PR targets the epic branch.

- [x] User confirmed

> Rewritten 2026-09-18 against the SCOPE approved the same day. The previous PLAN (marked
> *SUPERSEDED — do not build from this*) planned against `EntryScreenFingerprintMatcher`, which
> `a2h-c2b.2` deleted. Nothing is carried forward from it.

## Shape

The work is one dependency chain, and the order is forced: **the identity has to reach the disk
before anything can read it, and be read before anything can act on it.**

```
format  ->  the identity lands in XML + HTML, one syntax, one parser   (A, A2)
load    ->  it survives the round trip and the crawler's rewrites      (A4a, A4b)
act     ->  the crawler consults it at replay arrival                  (A4c)
read    ->  a capture becomes a tree again                             (D)
answer  ->  the validator composes the production calls                (C)
ship    ->  host entry point, skill, docs, evidence                    (F)
```

Refactors come first (Steps 1–2) so every later step writes against the shared seam rather than
against a shape that is about to move.

## Load-bearing facts

Verified against the source at `21ec46e`, not assumed. Each is `file:line`; the plan depends on all
seven.

1. **The route-step writer already has the exact shape `<screen-identity>` must grow into.**
   `appendStepIdentity` ([AccessibilityXmlSerializer.kt:196](../../app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:196))
   is already tag-name-parameterized and writes `package`, `root-class` and `<element label
   resource-id class list-item checkable editable back />` children, **sorted by `it.encoded`**
   (deterministic). Its reader is `parseStepIdentity`
   ([ScreenXmlReader.kt:190](../../app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt:190)).
   Scope call A's "extracted, not copied" is therefore a *generalization* of code that already
   exists, not a new writer.
2. **`<screen-identity>` today is name-only.** `appendScreenIdentity`
   ([:91](../../app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:91))
   writes `package` / `title` / `title-disambiguator-N` from `ScreenIdentityFields`
   ([CrawlerModels.kt:464](../../app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:464)),
   a three-field record. It has to carry a whole `ScreenIdentity`.
3. **`<node>` serializes every field identity needs.** `appendNode`
   ([:236](../../app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:236))
   writes class, package, resource-id, text, content-description, clickable, click-action,
   scrollable, checkable, checked, editable, enabled, visible-to-user, bounds — and `fingerprint=`
   on every visible pressable node. So the tree reader is possible, **and** the device-written
   `fingerprint=` values are an independent oracle for the reader's fidelity pin.
   `childIndexPath` is *not* serialized; it is reconstructed from tree position and is not part of
   identity, so it cannot move a verdict.
4. **`HtmlRenderer.render` cannot see the identity.** It takes `(snapshot, resolvedChildLinks)`
   ([HtmlRenderer.kt:4](../../app/src/main/java/com/example/apptohtml/crawler/HtmlRenderer.kt:4)).
   `CaptureFileStore.saveScreen` *already receives* `crawlState` and passes it to the XML serializer
   only ([CaptureFileStore.kt:76](../../app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:76)),
   and `rewriteScreenHtml` ([:97](../../app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:97))
   does not receive it at all. **Four write sites** must be threaded: `CaptureFileStore` 76, 97, 151
   and `SnapshotFileStore` [:90](../../app/src/main/java/com/example/apptohtml/crawler/SnapshotFileStore.kt:90).
   Missing `rewriteScreenHtml` is exactly how a hand edit would be silently erased mid-crawl.
5. **The loader's blanking is an absence, not a policy.** `SavedCrawlLoader`
   ([:62](../../app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:62)) sets
   `elements = emptySet()` under the comment *"not recoverable from the artifacts"*. Step 3 makes it
   recoverable, which is what licenses A4a.
6. **The A4c site, and what it does on failure.** Replay arrival compares the **name half only**
   ([DeepCrawlCoordinator.kt:869](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:869)),
   under a comment stating content legitimately churns but the name does not. A non-match returns
   `ScreenPreparationResult.Failure` into `handlePreparationFailure`
   ([:880](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:880)) — a
   recovery path, not an abort.
7. **`capture-screen.ps1` returns what the live path needs.** It emits `HtmlFile` and `XmlFile`
   ([capture-screen.ps1:358](../../.claude/skills/capture-screen/scripts/capture-screen.ps1:358)),
   so `-Live` chains to it with no second device round trip written here.

## Steps

### Step 0 — Baseline

Archive to `evidence/00-baseline/`: `git status`, `./gradlew test`, `./gradlew assembleDebug`, and
the test count. Every later claim is measured against this.

### Step 1 — One element shape, one home *(refactor of covered code — mutation required)*

Generalize `appendStepIdentity` / `parseStepIdentity` into a single writer+reader pair for the
`package` + `root-class` + `<element …>` shape, and point the route-step expectations at it. No
behaviour change; the bytes are identical.

Covered by `CrawlerExportTest` and the route-step round-trip pins. **Pin:** run the existing suite
green, then inject one mutation (drop the `back` attribute from the shared writer) and archive it
**red** — a green suite alone does not prove the seam is load-bearing.

### Step 2 — One back-affordance rule, one home *(refactor of covered code — mutation required)*

Extract `countBackAffordances = !isRootScreen` into one named factory and point the **seven** derived
sites at it (`DeepCrawlCoordinator` 523, 969, 1032, 1057, 1090, 1337, 1398).

**The literal-`true` site at [:1424](../../app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1424)
is not touched** — its comment records that deriving it from the parent loosened the comparison for
depth-1 children. Write the SCOPE's exception pin first, then refactor: rewiring that site to the
factory must turn the pin red. Archive that mutation.

### Step 3 — The identity lands in the XML *(A)*

`ScreenCrawlState.screenIdentity` becomes a `ScreenIdentity` instead of `ScreenIdentityFields`;
`<screen-identity>` grows `root-class`, the Step-1 `<element>` children and a `<traits>` block
(`has-list`, `has-control`, `lacks-control`). Writers pass the identity they already hold
(`DeepCrawlCoordinator:1643`, `SnapshotCrawlState:29`). Captures carry **no traits** — proposing them
is `a2h-c2b.3`; the operator adds them by hand.

Trait serialization is new surface: `HasList` carries a container resource id, `minRows` and a row
schema, and its constructor **refuses** degenerate values — so the reader must map a refusal to
`INVALID_INPUT` naming the offending element, never to a crash or a verdict.

### Step 4 — The same block in the HTML *(A2)*

`<script type="application/xml" id="screen-identity">` in `<head>`, carrying byte-identical text to
the XML element. Thread the identity through all four write sites from fact 4 — including
`rewriteScreenHtml`, whose omission is the silent-erasure path.

### Step 5 — The loader stops blanking *(A4a, A4b)*

`SavedCrawlLoader` reads the persisted element set and traits into the record. Assert the name key
and every dedup key are unchanged from before — dedup must not shift.

**A4b is red→green and must be pinned in that order:** write the test asserting a resumed crawl's
manifest and graph JSON carry a real `replayFingerprint`, run it on pre-Step-5 code and archive it
**red** (today both are empty because the content half was blanked), then make it green.

### Step 6 — The crawler acts on a settled identity *(A4c)*

Add the trait condition beside the existing name check at fact 6's site, via the unmodified
`TraitEvaluator.verdict`.

Order matters: **write the `UNSETTLED` opt-in pin first.** It asserts that for an identity with no
traits the replay outcome is byte-identical to pre-change behaviour across a table of holding and
non-holding screens. Then add the condition. Then delete the `UNSETTLED` guard and archive that pin
**red** — that mutation is the whole safety argument for this step.

The divergence message names the failing trait (SCOPE pin; do not reproduce `a2h-c2b.6`'s
`Expected X but found X`). The name rule and its message are untouched.

### Step 7 — A capture becomes a tree again *(D)*

A read-only reader rebuilding an `AccessibilityNodeSnapshot` tree from `<scroll-steps>` step-0
`<node>`. Nothing reads `<node>` trees back today, so this is new surface with no prior contract.

Two pins, one synthetic and one real: round-trip synthetic trees (escaped text, nested lists,
checkable/editable, click-action-only, invisible, disabled) and assert `ScreenIdentity.fromRoot`,
trait verdicts and click-fallback candidates all equal the original's; then read a committed real
capture and assert its step-0 identity equals the `fingerprint=` values **the device wrote**
(fact 3) — an oracle the reader cannot fake.

### Step 8 — The validator *(C)*

Compose the production calls into one result: `TraitEvaluator.verdict`,
`TraitEvaluator.matchKnownScreens`, `SameScreenPolicy.compare`, the click-fallback collector plus
`ClickFallbackMatcher.selectMatches`, and the weak/collision diagnostics. Headline outcome is the
**trait** answer; the element-set answer travels beside it, never blended.

Then the differential table and the four SCOPE mutations (collapse `matchKnownScreens` to first
match; `SameScreenPolicy` ignoring root class; the Step-2 factory inverted; `ClickFallbackMatcher`
dropping the enabled check) — each must turn a validator pin red.

### Step 9 — Disagreement is loud *(A3)*

XML vs HTML block comparison, and the `INVALID_INPUT` taxonomy: blocks differ (naming the differing
elements/traits), HTML missing, no identity block (pre-change capture — asks for a recapture, never
regenerates), a `HasList` the constructor refuses, an unknown trait element, duplicate screen ids, no
step-0 tree.

### Step 10 — Host entry point and skill *(F)*

A Gradle entry point running the validator on the host JVM without changing what a plain
`./gradlew test` does, and `.claude/skills/validate-screen-identity/` wrapping it. `-Observed` needs
no device; `-Live` chains `capture-screen.ps1` (fact 7). Known screens default to the target's own
crawl directory. Exit status distinguishes the five outcomes plus usage error; `-Strict` additionally
fails on an element-set mismatch or any unresolved element.

### Step 11 — Operational contract

Inputs byte-identical after a run; output only in the output directory; two runs byte-identical
including under reordered known screens and reordered traits; end-to-end on committed fixtures with
**no device**; PowerShell parser check.

### Step 12 — Docs

A `documentation/` page for the identity block and the tool; pointers from `crawler-module.md` and
`data-and-state.md` (what is persisted changes).

### Step 13 — Gate 3 evidence

The human checks, including the `a2h-c2b.3` evidence deliverable on the scope-call-G apps. Written to
`.spear/a2h-u68/evidence/` and summarized into `a2h-c2b.3`'s notes.

## Risks

- **Size.** No LOC cap applies (removed at the owner's instruction, 2026-09-18) — the work lands in
  one PR at whatever size it takes, and size alone is not a defect. Recorded only as a sequencing
  note: the chain's one clean seam is between **Steps 1–6 (the identity persists and the crawler acts
  on it)** and **Steps 7–13 (the tool)**, since everything after Step 6 is read-only. If the cycle is
  ever split, that is where. Nothing is to be split silently or trimmed to hit a number.
- **Step 3 has the widest blast radius.** Changing `ScreenCrawlState` ripples into every XML pin.
  Expect legitimate churn in tests that assert exact serialized text; the SCOPE requires each such
  change be listed and justified at Assess.
- **Step 6 is the only behaviour change.** Its entire safety argument is the `UNSETTLED` guard, which
  is why that pin is written before the feature and mutated after.
- **Fixture weight.** Real captures are large; keep committed fixtures trimmed and under the SCOPE's
  budget.
