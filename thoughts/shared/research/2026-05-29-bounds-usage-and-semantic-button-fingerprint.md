---
date: 2026-05-29
researcher: OCiobanu8
git_commit: d8a7b1575a0b8dc59ce37432d5b7daab9974281d
branch: codex/issue-4-one-crawl-per-app
repository: OCiobanu8/AppToHTML
topic: "Where and why the app uses `bounds`, and a design for replacing bounds-based element identity with a purely semantic button fingerprint"
tags: [research, codebase, crawler, bounds, fingerprint, click-replay, cross-device]
status: complete
last_updated: 2026-06-05
last_updated_by: OCiobanu8
---

# Research: `bounds` usage and a semantic button fingerprint

## Research Question

1. Where and why does AppToHTML use `bounds`?
2. How can buttons be identified by a resolution-/device-independent **fingerprint** so that absolute pixel `bounds` are no longer used to find them?

Motivation (re-grounded 2026-06-05): the original framing was cross-device portability (capture on device A, resume on device B). **Cross-device resume is explicitly out of scope** — the saved-crawl resume flow (`d8a7b15 Add per-app saved crawl resume flow`) reads XML back from a per-app directory **on the same device** (`SavedCrawlLoader.kt:19-39`, which recomputes `replayFingerprint` live rather than trusting it from disk, `:69`). The real motivation is **same-device replay correctness**: the target app's own layout drifts between the capture pass and the replay click (dynamic content, different row data, reflow, scroll offset), and the 24px bounds tolerance in `ClickFallbackMatcher` (`:6,98-120`) let the crawler click the *wrong row* — the failure documented in `2026-04-28-sims-tmobile-crawl-loop-root-cause.md`. Dropping bounds from identity removes that fragile signal regardless of device portability.

## Summary

`bounds` is the on-screen rectangle of an accessibility node, captured from Android's `AccessibilityNodeInfo.getBoundsInScreen(Rect)` and stored as a **string** in `[left,top][right,bottom]` format (`Rect.toShortString()`).

It serves **two unrelated roles**:

- **Role A — Identity / matching (device-sensitive → the part that must change).** Re-finding a button to click during replay, edge equality, and geometry-sensitive viewport fingerprints.
- **Role B — Layout & ordering within a single capture (device-safe → leave as-is).** Stacking the synthetic merged tree, reading-order sort, title-position scoring, and XML/HTML export.

Role B always runs on one device within one session, so absolute pixels are correct there. **Only Role A needs a fingerprint replacement.**

The chosen direction (per design discussion) is a **purely semantic** fingerprint: **no geometry and no structural/index path** (because button *order* may differ across devices). This aligns identity with how the scan step already deduplicates elements.

## Detailed Findings

### What `bounds` is and where it originates

Captured once per node during snapshotting and stored as a string:

- `AccessibilityTreeSnapshotter.kt:43-66`
  ```kotlin
  val bounds = Rect()
  node.getBoundsInScreen(bounds)
  // ...
  bounds = bounds.toShortString(),   // "[left,top][right,bottom]"
  ```

Stored as `val bounds: String` on every model carrying geometry:

- `CrawlerModels.kt:282` — `PressableElement`
- `CrawlerModels.kt:294` — `CrawlRouteStep`
- `CrawlerModels.kt:317` — `PressableElementLinkKey`
- `CrawlerModels.kt:411` — `AccessibilityNodeSnapshot`
- `CrawlerModels.kt:487` — `CrawlEdgeRecord`

Because it lives as a string, nearly every consumer re-parses it with the same regex `\[(\d+),(\d+)]\[(\d+),(\d+)]`, **independently redefined in ~6 files**:

- `ClickFallbackMatcher.kt:8`
- `ScrollScanCoordinator.kt:418` and `:572`
- `ScreenNaming.kt:11`
- `TraversalPlanner.kt:70`
- `SyntheticAccessibilityTreeBuilder.kt:369`
- `ScreenXmlReader.kt` (reads `bounds` attribute back, `:171`, `:258`)

### Role A — Identity / matching (device-sensitive; target of the change)

**1. Click replay / element re-location — the most important use.**
During deep crawl the app must re-click an element it saw earlier. If `childIndexPath` no longer resolves on the live screen, it falls back to matching by attributes including geometry.

- `AppToHtmlAccessibilityService.kt:491-539` (`performClick`) — builds a `ClickFallbackMatcher.Target` from `element.bounds` (`:554-560`) and collects live candidates, each computing `getBoundsInScreen` (`:676-693`), then calls `ClickFallbackMatcher.selectMatches` with `clickBoundsTolerancePx`.
- `ClickFallbackMatcher.kt:98-120` — geometry is a fuzzy signal with a **24px tolerance** (`DEFAULT_BOUNDS_TOLERANCE_PX = 24`, `:6`). Two eligibility reasons depend on it: `CLASS_PLUS_BOUNDS_MATCH` and `BOUNDS_ICON_MATCH` (the latter for unlabeled icons, where bounds is the *only* identifier, `:103`). Bounds compatibility also adds `+200` to the rank score (`:117`).

**Why:** resource IDs and labels are often missing or non-unique (especially icons), so position acts as a tie-breaker. This is exactly the signal that breaks across devices.

**2. Crawl-edge equality.**
- `DeepCrawlCoordinator.kt:1842-1848` (`matchesElement`) includes `edge.bounds == element.bounds` in its equality check.

**3. Geometry-sensitive viewport fingerprints (loop / dedup detection).**
- `ScrollScanCoordinator.kt:280-372` builds two fingerprint flavors per viewport: geometry-sensitive (`includeBounds = true`) and logical (`includeBounds = false`).
- `EntryScreenBackAffordanceDetector` (`ScrollScanCoordinator.kt:440-573`) uses `top <= rootTop + 300` to detect top-of-screen back affordances.

### Role B — Layout & ordering within a single capture (device-safe; leave alone)

**4. Synthetic merged-tree layout** — `SyntheticAccessibilityTreeBuilder.kt`:
- sorts children by `bounds.top` (`:67`)
- vertically *stacks* deduplicated children by shifting bounds so they don't overlap (`shiftNode`/`shiftBounds`, `:86-94`, `:314-323`)
- expands container bounds to enclose children (`expandedBounds`, `:226-245`)
- uses a coarse `geometryBand` in the child merge key (`:261`)

**5. Reading-order sort** — `TraversalPlanner.kt:45-55`: orders pressable elements by `firstSeenStep`, then `bounds.top`, then `bounds.left`.

**6. Screen-name heuristics** — `ScreenNaming.kt:520-602`: scores candidate title nodes by position (`bounds.top <= 200 → +80`, etc.) and height; fallback name is literally `"Tap target $bounds"` (`:284`).

**7. Serialization / export / restore** — `AccessibilityXmlSerializer.kt:161,266,322` write `bounds="..."`; `HtmlRenderer.kt:76-78` emits `data-bounds="..."`; `ScreenXmlReader.kt:171,258` reads it back; `DeepCrawlCoordinator.kt:1426` uses placeholder `"[0,0][0,0]"` for synthetic continue-edges and logs bounds throughout (`:2166-2180`).

### Key insight: scan dedup is already (almost) purely semantic

`ScrollScanCoordinator.kt:644-664` (`ScrollScanAccumulator.toMergedKey`) deduplicates merged elements on:

```
label + resourceId + className + isListItem + checkable + checked + editable
```

**Bounds is NOT part of the merge key** — by design, so the same element at different scroll offsets merges into one (consistent with `CLAUDE.md`). This means semantically-identical buttons are *already* collapsed into a single `PressableElement` per screen. Making the replay/identity fingerprint the same kind of semantic key means identity (replay) and dedup (scan) finally agree.

## Design Decision: Purely Semantic Button Fingerprint

Chosen direction (no geometry, no structural/index path — order may differ across devices):

```
ElementFingerprint(
  resourceId,    // viewIdResourceName, or null
  label,         // normalized (see below)
  className,
  isListItem,
  checkable,     // element type/affordance (kept); `checked` is NOT included
  editable,
)
```

**Decisions made during design review:**

- **Toggle state — exclude `checked`, KEEP `checkable`** (revised 2026-06-05). `checked` is transient on/off *state* — a toggle is "the same button" whether on or off, and the value can change between capture and replay, so it is excluded. `checkable` is the element's *type/affordance* — a checkbox/switch is a genuinely different control from a plain button, and the flag is stable for a given widget — so it stays part of identity.
  - Consequence: two elements that differ *only* by `checkable` remain **distinct** (a toggle and a button with the same label do not merge). Only elements differing solely by `checked` merge.
- **Label normalization — trim → lowercase → strip trailing count/badge.** Conservative regexes such as `\s*\(\d+\)\s*$` and a trailing bare `\s+\d+$`, so `"Messages (3)"` ≡ `"Messages (5)"` ≡ `"messages"`.

**Matching algorithm (replaces geometry path):** among live clickable candidates, keep those whose fingerprint equals the target's; rank `resourceId`-present > `label`-present; click the top. No `Bounds`, no tolerance, no normalization-to-pixels.

### Requirement: persist the fingerprint as a custom attribute — in THREE places

The computed `ElementFingerprint` is surfaced as a **custom `fingerprint="..."` attribute**, **duplicated onto three representations** of the same button — two in the per-screen XML and one in the exported HTML:

**1. The merged element** — `<element>` inside `<merged-elements>` (the deduplicated identity list).
- **Serialize:** add `fingerprint="..."` alongside the existing element attributes at `AccessibilityXmlSerializer.kt:316-328` (the `<element>` writer in `appendMergedElements`). Precedent for a raw `fingerprint="..."` attribute already exists in this schema — `<expected-destination-identity fingerprint="...">` (`:189`) and `<expected-replay fingerprint="...">` (`:215`).
- **Parse:** read it back in `parsePressableElement` at `ScreenXmlReader.kt:250-267`. On resume the stored fingerprint is the source of truth for the `ClickFallbackMatcher.Target` (replacing the bounds-derived target). **This is the copy that round-trips for replay.**

**2. The original node** — the raw `<node>` the element was derived from, written by `appendNode` inside `<scroll-steps>` (`AccessibilityXmlSerializer.kt:392` → `:249-296`).
- Emit the **same** `fingerprint="..."` attribute, but **only on nodes that qualify as pressable** — i.e. `visibleToUser && (clickable || supportsClickAction)`, the exact predicate `AccessibilityTreeSnapshotter.collectPressableElements` uses (`AccessibilityTreeSnapshotter.kt:92`). Non-pressable container/text nodes get no fingerprint.
- **Inspection-only, does not round-trip.** `ScreenXmlReader.readFull` truncates the document at the `<scroll-steps` marker (`:42`, `:33-34`), so node-level fingerprints in this section are never parsed back on resume. Their value is making the per-node raw tree self-describing / greppable and letting a reader confirm a merged element and its source node share one identity. The replay path still relies solely on the merged-element copy (#1).

**3. The exported HTML anchor** — the `<a>` tag that represents each button in `HtmlRenderer`.
- Add `fingerprint="..."` to the anchor built in `renderAnchor` (`HtmlRenderer.kt:69-79`), alongside the existing `data-resource-id` / `data-class-name` / `data-bounds`. The renderer already works from the same `PressableElement` list that feeds `<merged-elements>` (`:35-67`), so it carries the **merged copy** of the fingerprint.
- **Inspection-only, does not round-trip.** Nothing parses HTML back; resume reads only XML (`SavedCrawlLoader` consumes `*.xml`, `:21-24`). The HTML attribute makes the rendered artifact self-describing and lets a button in the HTML be cross-referenced to its `<merged-elements>` entry by identity.
- **Attribute-name note:** the request is a bare `fingerprint=""`. The other custom attributes on this `<a>` use the `data-*` convention (`data-bounds`, etc.); `data-fingerprint` would match that convention and keep the markup HTML5-valid. Documented as the user asked (`fingerprint`); `data-fingerprint` is the consistency-preserving alternative if desired.

**Implementation note for the duplicated copies.** Computing the fingerprint inside `appendNode` needs two things the collector has but `appendNode` does not: the resolved label (`resolveElementLabel`, `AccessibilityTreeSnapshotter.kt:116-127`) and `isListItem`, which depends on the **ancestor chain** (`isInsideListLikeContainer`, `:99,157`). `appendNode` currently recurses with only a depth counter. Cleanest approach: precompute `collectPressableElements(root)` once, build a `childIndexPath → fingerprint` map (childIndexPath uniquely locates a node in the tree), and have `appendNode` look up and emit the attribute when the node's `childIndexPath` is present in the map. This guarantees the node-level and merged-element fingerprints are produced by one code path and can never drift. The HTML anchor (#3) needs no such lookup — `renderAnchor` already holds the `PressableElement`, so it computes the fingerprint directly via the shared encoder.

**Shared encoder.** All copies (in-memory key, merged-element XML attribute, original-node XML attribute, HTML anchor attribute, live replay fingerprint) reuse one canonical string encoder so they are byte-for-byte identical. Because identity is intentionally bounds-free, the existing bounds attributes — `bounds="..."` on `<element>` (`:322`) and `<node>` (`:266`), and `data-bounds` on the HTML `<a>` (`HtmlRenderer.kt:76`) — stay for Role B (layout/ordering/export) but are no longer read on the identity path.

**Open sub-decision:** the synthetic merged tree written to the separate `_merged_accessibility.xml` file (the `serialize(screenName, packageName, root)` overload, `:33-50`, also via `appendNode`) is a *third* node representation. "Original one" most naturally means the raw scroll-step nodes (#2), not this synthetic tree. Defaulting to **not** annotating `_merged_accessibility.xml` unless explicitly wanted; flagged here so it's a conscious choice, not an oversight.

### Known limitation (named, not a regression)

Unlabeled icons with **no resourceId** collapse to just `className`. Several icons share a className (`ImageView`/`ImageButton`), making them mutually indistinguishable. Today they are *already* merged into one element during scan, so the crawl only follows one — this remains a known limitation under the new scheme, not a new regression.

### Consequence — RESOLVED (sign-off received 2026-06-05): unify four drifting identity keys

Audit of the current code shows element identity is **not** defined once — there are **four** semantic keys, and they already disagree with each other:

| Key | Location | Fields |
|---|---|---|
| `toMergedKey` (scan dedup) | `ScrollScanCoordinator.kt:644-654` | label, resourceId, className, isListItem, **checkable, checked**, editable |
| `mergedElementFingerprint` (viewport `distinctBy`) | `ScrollScanCoordinator.kt:312-321` | label, resourceId, className, isListItem, **checkable**, editable — *no `checked`* |
| `buildElementFingerprint` (loop-detection content) | `ScrollScanCoordinator.kt:356-372` | label, resourceId, className, isListItem, **checkable, checked**, editable [+bounds] |
| `ReplayFingerprintCodec` (persisted replay) | `ReplayFingerprintCodec.kt:6-14` | label, resourceId, className, isListItem, **checkable, checked**, editable |

Separately, `ClickFallbackMatcher.Target` (`ClickFallbackMatcher.kt:44-51`) is the lone identity consumer that keys on **bounds** at all — every key in the table is already bounds-free for its logical variant.

**Decision:** unify all four onto the single `ElementFingerprint` (dropping **`checked`** only, **keeping `checkable`**, applying label normalization), and feed the same definition to `ClickFallbackMatcher`. This is shipped **together**, not phased — leaving them un-unified means the matcher defines "same element" differently from the dedup that produced the elements it matches against, which is exactly the disagreement the change removes. (Note: `mergedElementFingerprint` already keys on `checkable` without `checked`, so it is the closest to the target shape; the only change it needs is label normalization.)

The only externally visible behavior delta is that two buttons differing *only* by `checked` state merge into one scan element (`checkable`-differing elements stay distinct). **This is accepted:** two same-label buttons on one screen that differ only by on/off state is not a real screen.

## Proposed Change Set (for a future implementation plan)

bounds is removed **only from the identity path**; the `bounds` field stays on the models because Role B (layout/ordering/export) still needs it.

1. **New** `ElementFingerprint` + label normalizer + a single canonical string encoder in one location (retires the identity-only copies of the `[l,t][r,b]` regex). The encoder is shared by the in-memory key, the persisted XML attribute, and the live replay fingerprint so all three are byte-for-byte identical.
2. **`ClickFallbackMatcher.kt`** — rewrite to semantic-only; delete `Bounds`, `Bounds.parse`, tolerance, `isBoundsCompatible`, and the `CLASS_PLUS_BOUNDS_MATCH` / `BOUNDS_ICON_MATCH` reasons. Keep `RESOURCE_ID_MATCH` / `LABEL_MATCH`.
3. **`AppToHtmlAccessibilityService.kt`** — stop computing `Rect`/`ClickFallbackMatcher.Bounds` when building click candidates (`:676-693`); drop `clickBoundsTolerancePx`.
4. **`DeepCrawlCoordinator.kt:1842-1848`** — `matchesElement` keys on `bounds` **and** the order-sensitive `childIndexPath` / `firstSeenStep`. Switch it to `ElementFingerprint` equality so it doesn't become a fifth divergent identity definition.
5. **(Consequence — signed off, ships together)** Unify the four identity keys onto `ElementFingerprint`: `ScrollScanCoordinator.toMergedKey` (`:644-654`), `mergedElementFingerprint` (`:312-321`), `buildElementFingerprint` (`:356-372`), and `ReplayFingerprintCodec` (`ReplayFingerprintCodec.kt:6-14`) — all drop **`checked`** (keep `checkable`) and apply the same label normalization.
6. **NEW — surface the fingerprint in the output artifacts, duplicated in three places.**
   - **(a) Merged element XML (round-trips):** add `fingerprint="..."` to the `<element>` writer in `AccessibilityXmlSerializer.appendMergedElements` (`:316-328`) and read it back in `ScreenXmlReader.parsePressableElement` (`:250-267`). On resume this is the source of truth for the `ClickFallbackMatcher.Target`.
   - **(b) Original node XML (inspection-only):** emit the same `fingerprint="..."` from `appendNode` (`:249-296`, used by the `<scroll-steps>` raw trees at `:392`), **only** for pressable nodes (`visibleToUser && (clickable || supportsClickAction)`). Precompute a `childIndexPath → fingerprint` map from `collectPressableElements(root)` so `appendNode` (which lacks the ancestor chain + resolved label) can look it up. Not parsed back — `readFull` truncates at `<scroll-steps` (`ScreenXmlReader.kt:42`).
   - **(c) HTML anchor (inspection-only):** add `fingerprint="..."` to the `<a>` built in `HtmlRenderer.renderAnchor` (`HtmlRenderer.kt:69-79`), beside the existing `data-*` attributes. `renderAnchor` already holds the `PressableElement`, so it computes the fingerprint directly. Nothing parses HTML back. (Convention note: `data-fingerprint` would match the surrounding `data-*` attributes / HTML5 validity; using bare `fingerprint` per request.)
   - `bounds`/`data-bounds` stays in all three for Role B but is dropped from the identity path.
   - Sub-decision (default no): do **not** annotate the synthetic `_merged_accessibility.xml` tree unless explicitly requested.
7. **Tests** — `ClickFallbackMatcherTest` is entirely bounds-driven today and would be rewritten; add round-trip coverage for the merged-element `fingerprint` attribute (serialize → `ScreenXmlReader` → identical fingerprint), assert the node-level, HTML-anchor, and merged-element fingerprints all match for a shared pressable element, and assert non-pressable nodes carry no `fingerprint`. Update related crawler tests (and `HtmlRendererTest` if present).

## Code References

Permalinks pinned to commit `d8a7b1575a0b8dc59ce37432d5b7daab9974281d`.

- [`AccessibilityTreeSnapshotter.kt:43-66`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/AccessibilityTreeSnapshotter.kt#L43-L66) — bounds capture from `getBoundsInScreen` → `toShortString()`
- [`CrawlerModels.kt`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt) `:282,294,317,411,487` — `bounds: String` fields on the domain models
- [`ClickFallbackMatcher.kt:98-120`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/ClickFallbackMatcher.kt#L98-L120) — bounds-based matching (24px tolerance, `:6,8`) — **Role A**
- [`AppToHtmlAccessibilityService.kt:491-560`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/AppToHtmlAccessibilityService.kt#L491-L560) — replay click; live candidates at `:676-693` — **Role A**
- [`DeepCrawlCoordinator.kt:1842-1848`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L1842-L1848) — edge equality includes bounds — **Role A**
- [`ScrollScanCoordinator.kt:280-372`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt#L280-L372) — geometry/logical fingerprints; back-affordance `:440-573`; merge key `:644-664`
- [`SyntheticAccessibilityTreeBuilder.kt:55-113`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/SyntheticAccessibilityTreeBuilder.kt#L55-L113) — layout math (`:226-245,314-323`) — **Role B**
- [`TraversalPlanner.kt:45-70`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/TraversalPlanner.kt#L45-L70) — reading-order sort — **Role B**
- [`ScreenNaming.kt:520-602`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt#L520-L602) — title scoring; fallback name `:284` — **Role B**
- [`AccessibilityXmlSerializer.kt:161`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt#L161) `:266,322` — export — **Role B**
- [`HtmlRenderer.kt:76-78`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/HtmlRenderer.kt#L76-L78) — `data-bounds` export — **Role B**
- [`ScreenXmlReader.kt:171`](https://github.com/OCiobanu8/AppToHTML/blob/d8a7b1575a0b8dc59ce37432d5b7daab9974281d/app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt#L171) `:258` — restore — **Role B**

## Architecture Insights

- **bounds is a string, not a typed rectangle.** Stored as `[l,t][r,b]` and re-parsed wherever needed via a regex (`\[(\d+),(\d+)]\[(\d+),(\d+)]`) that is independently redeclared in ~6 files, each pairing it with its own small `Bounds`/`ParsedBounds`/`ScreenBounds` data class. There is no shared geometry utility.
- **Two roles, one field.** The same `bounds` value serves both device-sensitive *identity* (Role A) and device-safe *layout/ordering* (Role B). The refactor cleanly separates them: identity moves to a semantic fingerprint; the `bounds` field stays on the models for Role B.
- **Dedup already leads the way.** Scan-time merging (`toMergedKey`) is already bounds-free and almost purely semantic, so a semantic identity fingerprint is the natural convergence point — it makes "what counts as the same element during scan" and "what counts as the same element during replay" one definition.
- **Replay is layered.** `performClick` first tries the recorded `childIndexPath` and only falls back to attribute matching (where bounds currently lives) when the path diverges. The fingerprint change affects only that fallback layer.

## Historical Context (from thoughts/)

- `thoughts/shared/research/2026-05-08-one-crawl-per-app-state-source-of-truth.md` — establishes per-screen XML as the crawl's source of truth and defines screen-level `<screen-identity>` fingerprints and a decomposed `<expected-replay>` element list. The element-level identity discussed here is the natural complement at the button level; the resume flow it enables is precisely the cross-device motivation for dropping pixel bounds.
- `thoughts/shared/research/2026-04-28-sims-tmobile-crawl-loop-root-cause.md` — documents an oscillation caused partly by low-confidence fallback clicking: candidates received a positive base score even when label/resourceId/class/bounds did not match, letting the crawler click the wrong row. This is direct evidence that bounds-tolerant fallback matching is fragile; a stricter semantic-equality fingerprint addresses the same failure mode.

## Related Research

- `thoughts/shared/research/2026-05-08-one-crawl-per-app-state-source-of-truth.md`
- `thoughts/shared/research/2026-04-28-sims-tmobile-crawl-loop-root-cause.md`
- `thoughts/shared/research/2026-04-28-google-continue-entry-restore.md`
- `thoughts/shared/research/2026-04-28-google-services-destination-settling.md`

## Resolved Decisions (2026-06-05)

All three former open questions are resolved:

1. **Ship the key-alignment together with the matcher change — RESOLVED: together.** The audit found four already-divergent identity keys (see *Consequence — RESOLVED* above), so this is a single unification, not an optional follow-up. Phasing it would leave the matcher defining "same element" differently from the dedup that produced its candidates. The only behavior delta (elements differing only by `checked` merge into one element; `checkable` is kept, so toggles stay distinct from plain buttons) is accepted as not a real screen.
2. **Localized labels across devices — RESOLVED: moot.** Cross-device resume is out of scope; resume is same-device, same-locale, same app build. Label normalization is **kept**, but justified solely by same-device *dynamic* labels (counts/badges like `"Messages (3)" → "Messages (5)"`), not localization.
3. **Aspect-ratio differences — RESOLVED: no action.** Only relevant if geometry were retained in identity (it isn't) and only as a cross-device concern (out of scope).

**New requirement added 2026-06-05:** the `ElementFingerprint` is surfaced as a custom `fingerprint="..."` attribute, **duplicated** onto three representations of each button: (1) the merged `<element>` in `<merged-elements>` XML — round-trips for replay; (2) the original raw `<node>` in `<scroll-steps>` XML — inspection-only, pressable nodes only; (3) the `<a>` anchor in the exported HTML — inspection-only. See *Requirement: persist the fingerprint as a custom attribute — in THREE places* and change-set item 6.
