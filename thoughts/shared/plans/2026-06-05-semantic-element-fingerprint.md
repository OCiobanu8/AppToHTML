---
date: 2026-06-05
author: OCiobanu8
branch: codex/issue-4-one-crawl-per-app
repository: OCiobanu8/AppToHTML
topic: "Replace bounds-based element identity with a purely semantic ElementFingerprint, unify the four drifting identity keys, and surface the fingerprint in the XML + HTML artifacts"
status: ready
related_research: thoughts/shared/research/2026-05-29-bounds-usage-and-semantic-button-fingerprint.md
tags: [plan, crawler, fingerprint, click-replay, bounds, xml, html]
---

# Implementation Plan: Semantic Element Fingerprint

## Overview

Replace **device-sensitive, bounds-based element identity** (Role A) with a single **purely semantic `ElementFingerprint`**, used everywhere "is this the same button?" is asked: scan dedup, viewport/loop-detection fingerprints, the persisted replay fingerprint, crawl-edge equality, and the live click-replay matcher. The same fingerprint is also surfaced as a custom `fingerprint="..."` attribute in the exported artifacts (per-screen XML — both the merged `<element>` and the raw/synthetic `<node>` — and the HTML `<a>`).

`bounds` is **not removed from the models**; it remains for Role B (layout, ordering, export). It is only removed from the identity path.

This plan implements the design in `thoughts/shared/research/2026-05-29-bounds-usage-and-semantic-button-fingerprint.md` (all open questions resolved there + in the planning session).

## Current State

Element identity is defined **five** different ways today, and they already disagree:

| Consumer | Location | Identity basis |
|---|---|---|
| Scan dedup | `ScrollScanCoordinator.toMergedKey` `:644-654` | label, resourceId, className, isListItem, **checkable, checked**, editable |
| Viewport `distinctBy` | `ScrollScanCoordinator.mergedElementFingerprint` `:312-321` | same minus **`checked`** |
| Loop-detection content | `ScrollScanCoordinator.buildElementFingerprint` `:356-372` | label, resourceId, className, isListItem, **checkable, checked**, editable [+bounds] |
| Persisted replay | `ReplayFingerprintCodec` `:6-14` | label, resourceId, className, isListItem, **checkable, checked**, editable |
| Edge equality | `DeepCrawlCoordinator.matchesElement` `:1842-1848` | label, resourceId, **bounds**, className, **childIndexPath, firstSeenStep** |
| Live click replay | `ClickFallbackMatcher` `:44-138` | resourceId / label / class **+ 24px bounds tolerance** |

- `bounds` is a `String` (`[l,t][r,b]`) re-parsed by a regex independently redeclared in ~6 files.
- Live click candidates (`AppToHtmlAccessibilityService.collectClickFallbackCandidates` `:668-700`) compute `Rect`/`Bounds` per node and **do not** carry `isListItem` or `editable`.
- The existing `normalizeFingerprintToken` (`ScrollScanCoordinator.kt:397-404`) strips **all** non-alphanumerics — suitable for resourceId tokens, too aggressive for labels (no trailing-badge concept).
- `ClickFallbackMatcherTest` is entirely bounds-driven (`ClickFallbackMatcherTest.kt`).

## Desired End State

- One `ElementFingerprint` type + one label normalizer + one canonical string encoder.
- All six consumers above key on `ElementFingerprint`. **`checked` is excluded** from identity; **`checkable` is kept** (element type/affordance); labels are normalized (trailing badges stripped).
- `ClickFallbackMatcher` is semantic-only: no `Bounds`, no tolerance, no `CLASS_PLUS_BOUNDS_MATCH`/`BOUNDS_ICON_MATCH`.
- The fingerprint string appears as `fingerprint="..."` on: merged `<element>`, raw `<node>` (scroll-steps), synthetic `<node>` (`_merged_accessibility.xml`), and the HTML `<a>`. These attributes are **inspection/redundancy**; identity is always recomputed live from fields via the shared encoder (no parse-back trust needed — consistent because the same function runs on freshly-scanned and reloaded elements).
- `bounds`/`data-bounds` stays in all artifacts for Role B.

### Key behavior change (signed off)

Two buttons differing **only** by `checked` state merge into one scan element (`checkable`-differing elements stay distinct — a toggle never merges with a plain button). Accepted: not a real screen. Dynamic-count labels (`"Messages (3)"` vs `"(5)"`) now merge and match across capture/replay.

## Design: `ElementFingerprint`

New file `app/src/main/java/com/example/apptohtml/crawler/ElementFingerprint.kt`:

```kotlin
internal data class ElementFingerprint(
    val resourceId: String?,   // null if blank
    val label: String,         // normalized
    val className: String?,    // null if blank
    val isListItem: Boolean,
    val checkable: Boolean,    // element type/affordance — KEPT (checked is NOT)
    val editable: Boolean,
) {
    val encoded: String = listOf(
        resourceId.orEmpty(), label, className.orEmpty(),
        isListItem.toString(), checkable.toString(), editable.toString(),
    ).joinToString("|")

    companion object {
        fun of(element: PressableElement): ElementFingerprint =
            ofFields(element.label, element.resourceId, element.className,
                     element.isListItem, element.checkable, element.editable)

        fun ofFields(
            label: String, resourceId: String?, className: String?,
            isListItem: Boolean, checkable: Boolean, editable: Boolean,
        ): ElementFingerprint = ElementFingerprint(
            resourceId = resourceId?.takeIf { it.isNotBlank() },
            label = normalizeLabel(label),
            className = className?.takeIf { it.isNotBlank() },
            isListItem = isListItem,
            checkable = checkable,
            editable = editable,
        )

        fun normalizeLabel(raw: String): String =
            raw.trim().lowercase()
               .replace(Regex("""\s*\(\d+\)\s*$"""), "")   // "messages (3)" -> "messages"
               .replace(Regex("""\s+\d+$"""), "")          // "messages 3"   -> "messages"
               .trim()
    }
}
```

- **Identity & equality** = the data class (structural). **Serialized form** = `encoded`.
- Matching does not need geometry; ranking uses `resourceId != null` then `label.isNotBlank()`.
- Excludes `checked` by construction; includes `checkable`.

## What is NOT changed

- `bounds: String` stays on every model (`PressableElement`, `CrawlRouteStep`, `CrawlEdgeRecord`, `AccessibilityNodeSnapshot`, `PressableElementLinkKey`).
- Role B uses of bounds: `SyntheticAccessibilityTreeBuilder`, `TraversalPlanner` reading-order sort, `ScreenNaming`, all `bounds=`/`data-bounds=` serialization.
- Cross-device resume is out of scope (resume is same-device).

---

## Phase 1 — Introduce `ElementFingerprint` + label normalizer ✅ COMPLETE

### Changes
- [x] **New** `crawler/ElementFingerprint.kt` as above.
- [x] **New** `ElementFingerprintTest.kt`:
  - `of` excludes `checked` (two elements differing only in `checked` are equal) but **includes** `checkable` (a checkable vs non-checkable element with the same label are NOT equal).
  - `normalizeLabel`: `"Messages (3)" == "Messages (5)" == "messages"`; `"Inbox 12" == "inbox"`; trailing bare numbers stripped, so `"Room 101" == "Room 202" == "room"` (accepted merge); genuinely *internal* numbers preserved (`"Level 3 settings"` keeps the `3`, distinct from `"Level 5 settings"`); blank/whitespace handling.
  - Blank resourceId/className normalized to null.
  - `encoded` round-trips stably (same fields → same string).

### Success criteria
- [x] **Automated:** `./gradlew testDebugUnitTest --tests "com.example.apptohtml.crawler.ElementFingerprintTest"` passes (8/8). *(Note: `test --tests` is rejected by the Android `test` lifecycle task; use `testDebugUnitTest`.)*

---

## Phase 2 — Rewrite `ClickFallbackMatcher` to semantic-only ✅ COMPLETE

> Implemented together with Phase 3 (compile-coupled: the matcher API change breaks the service until both land). Added a third `EligibilityReason.CLASS_MATCH` for the icon/class-only case (the plan's "retained generic reason"), so class-only matches are visible in diagnostics.

### Changes (`crawler/ClickFallbackMatcher.kt`)
- Delete `Bounds`, `Bounds.parse`, `BOUNDS_REGEX`, `isBoundsCompatible`, `DEFAULT_BOUNDS_TOLERANCE_PX`, `boundsTolerancePx`.
- `Candidate<T>`: drop `bounds`; **add** `fingerprint: ElementFingerprint`. Keep `visible/enabled/clickable/supportsClickAction/depth` and `resourceId`/`label` only as needed for ranking (or read them off `fingerprint`).
- `Target`: replace `label/resourceId/className/bounds/checkable/checked` with a single `fingerprint: ElementFingerprint`.
- `EligibilityReason`: keep `RESOURCE_ID_MATCH`, `LABEL_MATCH`; delete `CLASS_PLUS_BOUNDS_MATCH`, `BOUNDS_ICON_MATCH`.
- `evaluate`: eligible iff `candidate.visible && candidate.enabled && (candidate.clickable || candidate.supportsClickAction) && candidate.fingerprint == target.fingerprint`. Reason = `RESOURCE_ID_MATCH` if `target.fingerprint.resourceId != null` else `LABEL_MATCH` if `target.fingerprint.label.isNotBlank()` else still eligible (icon w/ class only) — represent as `LABEL_MATCH` fallback or a retained generic reason. Rank: `resourceId present → +1000`, `label present → +700`, `+ (100 - depth)`.

> Note the known limitation (carried, not new): unlabeled icons with no resourceId collapse to `className` only and remain mutually indistinguishable — already merged at scan time.

### Changes (`ClickFallbackMatcherTest.kt`) — rewrite
- Replace all bounds-based cases:
  - `doesNotMatch...` → fingerprints differ → empty.
  - `matchesSameLabelAfterPathShift` → identical fingerprint regardless of position → matched, `RESOURCE_ID_MATCH`.
  - icon cases → match by class-only fingerprint equality (no bounds).
  - keep invisible/disabled/non-clickable rejection.
  - depth tiebreak among equal fingerprints.
  - delete `parsesAndIgnoresInvalidBoundsStrings`.

### Success criteria
- [x] **Automated:** `./gradlew testDebugUnitTest --tests "com.example.apptohtml.crawler.ClickFallbackMatcherTest"` passes (7 cases); full `testDebugUnitTest` suite green.

---

## Phase 3 — Wire the matcher into the live click path ✅ COMPLETE (automated)

> Discovery beyond the plan: the live `resolveLiveLabel` lacked the snapshot's `ScreenNaming.chooseElementLabel` fallback (resource-id segment / bounds placeholder), so unlabeled-but-resource-id'd elements would have mismatched the recorded fingerprint. Added `resolveLiveElementLabel` mirroring the snapshot path exactly. Extracted `AccessibilityTreeSnapshotter.isListLikeContainerClass` as the shared `isListItem` predicate. Removed the now-dead `isNodeChecked`.

### Changes (`AppToHtmlAccessibilityService.kt`)
- `clickFallbackTargetFor` (`:554-563`): build `ClickFallbackMatcher.Target(ElementFingerprint.of(element))`.
- `collectClickFallbackCandidates` (`:668-700`):
  - Remove `Rect`/`getBoundsInScreen`/`ClickFallbackMatcher.Bounds`.
  - Compute `isListItem` from the live ancestor chain (thread an `ancestors: List<AccessibilityNodeInfo>` through `walk`, mirroring `AccessibilityTreeSnapshotter.isInsideListLikeContainer`) and `editable` from `node.isEditable`. `checkable` comes from `node.isCheckable` (already read today).
  - Build `candidate.fingerprint = ElementFingerprint.ofFields(resolveLiveLabel(node), node.viewIdResourceName, node.className?.toString(), isListItem, node.isCheckable, node.isEditable)`.
- `performClick` (`:510-517`): drop `boundsTolerancePx` argument; delete the `clickBoundsTolerancePx` field/usages.
- Update the `live_action_*` diagnostic lines that reference `element.bounds` to log `ElementFingerprint.of(element).encoded` instead (bounds logging may stay for Role B context if desired).

> The live ancestor/`isListItem` and `editable` computation must match `AccessibilityTreeSnapshotter`'s exactly, or live candidates won't equal stored targets. Extract `isInsideListLikeContainer`'s class-name predicate into a shared internal helper reused by both, to prevent drift.

### Success criteria
- [x] **Automated:** `./gradlew assembleDebug` compiles; full `testDebugUnitTest` suite green.
- [ ] **Manual:** Run a deep crawl on a list-heavy app (e.g. Settings); confirm in diagnostics that fallback clicks log `RESOURCE_ID_MATCH`/`LABEL_MATCH`/`CLASS_MATCH` (never the deleted bounds reasons) and land on the correct row.

---

## Phase 4 — Unify scan dedup + viewport/replay fingerprints ✅ COMPLETE

> Verified the critical invariant: `logicalViewportFingerprint` (= `buildElementFingerprint`, `includeBounds=false`) is the canonical replay fingerprint, and `ReplayFingerprintCodec` only round-trips that exact string through XML — so both were switched to `ElementFingerprint.encoded` (resourceId-first, 6 fields). Field **order changed** (label-first → resourceId-first) and labels are now **normalized**, so two brittle literal-string assertions in `DeepCrawlCoordinatorTest` (destination-settling `observedFingerprint`/`selectedFingerprint`) and the `ElementFields` test constructions in `AccessibilityXmlSerializerTest` were updated. Old saved `<expected-replay>` blocks still decode (attributes read by name; `checked` ignored).

### Changes (`ScrollScanCoordinator.kt`)
- `toMergedKey` (`:644-654`) → key on `ElementFingerprint.of(element)` (replace `MergedElementKey`).
- `mergedElementFingerprint` (`:312-321`) → `ElementFingerprint.of(element).encoded`.
- `buildElementFingerprint` (`:356-372`) → emit `ElementFingerprint.of(element).encoded`; the `includeBounds` flavor appends `element.bounds` **after** the encoded fingerprint (geometry-sensitive viewport variant retains bounds as a separate suffix, unchanged for loop detection).
- Remove the now-unused per-element `checked` token (keep `checkable`) and stop passing the label raw; normalization now lives in `ElementFingerprint`.
- Leave `normalizeFingerprintToken` (resourceId-oriented, used by `looksLikeEntryBackAffordance`) as-is.

### Changes (`ReplayFingerprintCodec.kt`)
- Reduce `ElementFields` to the 6 identity fields (drop **`checked`** only; keep `checkable`); `ELEMENT_FIELD_COUNT = 6`. Encode via the same field order as `ElementFingerprint.encoded`.
- Update `ScreenXmlReader.decodeReplay` (`:199-215`) and `AccessibilityXmlSerializer.appendExpectedReplay` (`:205-247`) to stop reading/writing **`checked`** on `<expected-replay><element>` (keep `checkable`).

> No backcompat: existing saved `<expected-replay>` blocks with the old 7-field encoding are simply re-derived on next capture; the dev philosophy allows the format break.

### Success criteria
- [x] **Automated:** `ScrollScanCoordinatorTest`, `AccessibilityXmlSerializerTest`, `ScreenXmlReaderTest` pass; full `testDebugUnitTest` suite green (198 tests, incl. updated `DeepCrawlCoordinatorTest` fingerprint assertions).

---

## Phase 5 — Edge equality via fingerprint ✅ COMPLETE

> Took option (a), and it was **not invasive** — no XML change needed. Edge identity fields are carried from the originating `PressableElement`, and on load are rebuilt from the parent `<element>` (which already persists `list-item`/`checkable`/`editable`). So the `<edge>` XML / `EdgeXmlView` were untouched.
>
> **Correction to the plan:** `CrawlEdgeRecord` lacked `checkable` too (not just `isListItem`/`editable`), so a complete fingerprint required adding **all three**. Added with `false` defaults at the end of `CrawlEdgeRecord` (the 2 production sites set them explicitly; the 3 test constructions keep compiling unchanged).

### Changes
- [x] `CrawlEdgeRecord` (`CrawlerModels.kt`): added `isListItem`, `checkable`, `editable` (default `false`).
- [x] `ElementFingerprint.of(edge: CrawlEdgeRecord)` overload (centralized encoder).
- [x] `DeepCrawlCoordinator.matchesElement`: now `ElementFingerprint.of(edge) == ElementFingerprint.of(element)` (drops bounds/childIndexPath/firstSeenStep from the match — fingerprint is the single identity, and post-Phase-4 dedup guarantees per-screen uniqueness).
- [x] `CrawlRunTracker.addEdge` + `SavedCrawlLoader`: populate the three fields from the source element.

### Success criteria
- [x] **Automated:** `DeepCrawlCoordinatorTest` and `CrawlerTraversalTest` pass; full `testDebugUnitTest` suite green.

---

## Phase 6 — Surface the fingerprint in XML + HTML artifacts ✅ COMPLETE

> All four sites write `ElementFingerprint.of(...).encoded`. The single `appendNode` change covers both the raw `<scroll-steps>` trees and the synthetic `_merged_accessibility.xml` tree. Exposed `AccessibilityTreeSnapshotter.resolveElementLabel` as `internal` and reused the already-shared `isListLikeContainerClass`, so node-level and merged-element fingerprints are byte-identical for the same button (asserted in a test). One existing assertion (`first-seen-step="0" />`) was updated since the fingerprint now sits before the self-close.

All four sites write `ElementFingerprint.of(...).encoded`. The attribute is **inspection/redundancy**; nothing reads it back for identity (identity is recomputed live).

### 6a — Merged `<element>` XML
- `AccessibilityXmlSerializer.appendMergedElements` (`:316-328`): add `fingerprint="${escape(ElementFingerprint.of(element).encoded)}"`.
- `ScreenXmlReader.parsePressableElement` (`:250-267`): no functional read needed; optionally assert-ignore the attribute. (Round-trip is validated by recompute equality in tests.)

### 6b — Raw + synthetic `<node>` XML
- `AccessibilityXmlSerializer.appendNode` (`:249-296`): thread `ancestors: List<AccessibilityNodeSnapshot>` through the recursion (it currently passes only depth). For nodes where `node.visibleToUser && (node.clickable || node.supportsClickAction)` (the `collectPressableElements` predicate, `AccessibilityTreeSnapshotter.kt:92`), emit `fingerprint="${escape(ElementFingerprint.ofFields(resolveElementLabel(node), node.viewIdResourceName, node.className, isInsideListLikeContainer(ancestors), node.checkable, node.editable).encoded)}"`.
- This covers **both** callers of `appendNode`: the `<scroll-steps>` raw trees (`:392`) and the synthetic `_merged_accessibility.xml` tree (`serialize(screenName, packageName, root)` `:33-50`) — satisfying the "annotate synthetic tree too" decision.
- Expose `AccessibilityTreeSnapshotter.resolveElementLabel` and `isInsideListLikeContainer` (or move the label/isListItem logic into a shared `internal` helper) so the serializer reuses the exact resolution `collectPressableElements` uses — guaranteeing the node-level fingerprint equals the merged-element fingerprint for the same button.

### 6c — HTML `<a>`
- `HtmlRenderer.renderAnchor` (`:69-79`): add `fingerprint="${escapeAttribute(ElementFingerprint.of(element).encoded)}"` (bare attribute name, per decision), beside `data-bounds`.

### Success criteria
- [x] **Automated:** New `AccessibilityXmlSerializerTest` cases (merged `<element>` carries `fingerprint=`; pressable `<node>` carries the **same** fingerprint; non-pressable nodes carry none — exactly one in the tree; synthetic-tree overload emits it) and new `HtmlRendererTest` (`<a ... fingerprint="...">` == `ElementFingerprint.of(element).encoded`) pass; full `testDebugUnitTest` suite green.

---

## Phase 7 — Full sweep + cleanup ✅ COMPLETE

> Sweep confirmed clean: all remaining `[l,t][r,b]` bounds parsing is **Role B only** — `TraversalPlanner` reading-order sort, `SyntheticAccessibilityTreeBuilder` layout math, and `ScrollScanCoordinator`'s within-capture back-affordance top detection (`parseFingerprintBounds`/`parseBounds`, still used). The removed identity symbols (`ClickFallbackMatcher.Bounds`, `Bounds.parse`, `*BOUNDS_TOLERANCE*`, `CLASS_PLUS_BOUNDS_MATCH`, `BOUNDS_ICON_MATCH`, `clickBoundsTolerancePx`) return **zero matches** across `app/src`. No dead helpers remained to delete (`clickBoundsTolerancePx` was already removed in Phase 3; `parseFingerprintBounds` is still live). Doc cleanup only.

### Changes
- [x] Verified no identity-path bounds usage remains; removed symbols grep-clean.
- [x] No dead helpers to remove (confirmed `parseFingerprintBounds` still used by back-affordance detection).
- [x] Updated `documentation/crawler-module.md` (rewrote "Click fallback eligibility"; replaced "Merge strategy" with "Element identity (`ElementFingerprint`)") and `documentation/data-and-state.md` (fingerprint attribute in HTML/XML output).

### Success criteria
- [x] **Automated:** full `testDebugUnitTest` green (200 tests); `assembleDebug` green.

---

## Testing Strategy

| Layer | Test |
|---|---|
| Fingerprint unit | `ElementFingerprintTest` — normalization, `checked` exclusion + `checkable` inclusion, encode stability |
| Matcher unit | `ClickFallbackMatcherTest` — rewritten semantic-only |
| Scan dedup | `ScrollScanCoordinatorTest` — toggle-merge behavior, normalized-label merge |
| Serialization | `AccessibilityXmlSerializerTest`, `ScreenXmlReaderTest` — fingerprint attribute on element + pressable nodes (raw & synthetic), reduced `<expected-replay>` fields |
| HTML | `HtmlRendererTest`/`CrawlerExportTest` — `<a fingerprint>` |
| Edge/traversal | `DeepCrawlCoordinatorTest`, `CrawlerTraversalTest` — fingerprint edge equality |
| Cross-copy consistency | assert merged-element, raw-node, synthetic-node, and HTML fingerprints are byte-identical for one shared element |

## Success Criteria (overall)

### Automated
- [x] `./gradlew testDebugUnitTest` passes (200 tests, all classes above).
- [x] `./gradlew assembleDebug` compiles.
- [x] No `ClickFallbackMatcher.Bounds` / tolerance symbols remain (grep clean).
- [x] One `ElementFingerprint` is the only identity definition (the legacy keys delegate to it).

### Manual
- [ ] Deep crawl a list-heavy app; diagnostics show only `RESOURCE_ID_MATCH`/`LABEL_MATCH`, correct rows clicked.
- [ ] Open an exported screen `.xml`: merged `<element>` and its source `<node>` share one `fingerprint`; non-pressable nodes have none; `_merged_accessibility.xml` pressable nodes carry it.
- [ ] Open the exported `.html`: each button `<a>` has `fingerprint="..."` matching the XML.
- [ ] Save then resume a crawl on the same device; replay clicks succeed without bounds.

## Notes / Refinements vs. research doc
- The research doc suggested parsing the stored merged-element fingerprint back as the replay "source of truth." This plan instead **recomputes** identity from fields via the shared encoder at every use site (fresh and reloaded elements run the same function), making all stored `fingerprint` attributes inspection/redundancy and eliminating any trust/drift between stored and live values. Matching behavior is identical.
- `_merged_accessibility.xml` annotation is included (decision: yes) and comes for free via the single `appendNode` change.
- Edge fingerprint completeness (Phase 5) requires adding `isListItem`/`editable` to `CrawlEdgeRecord`; chosen for identity parity with elements.
