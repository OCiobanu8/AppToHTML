# ENG-0010 Issue 2 External Package XML Field Research

Date: 2026-05-29
Repository: `OCiobanu8/AppToHTML`
Branch: `codex/issue-4-one-crawl-per-app`
Commit inspected: `d7e09c206982c438a366c68b1ba3c9a6fa6cf266`
Working tree: dirty; this research reflects the local workspace state on 2026-05-29.

## Research Question

Check the issue described in
`thoughts/shared/validations/2026-05-19-ENG-0010-issue-2-external-package-xml-field.md`:
whether external package information is persisted as a structured
`external-package` XML attribute on originating crawl edges.

## Summary

The issue is still present in the current local code. The XML serializer and
reader support `external-package`, and `EdgeXmlView` exposes
`externalPackage`, but the durable edge model (`CrawlEdgeRecord`) does not store
that field. As a result, the coordinator cannot project external package data
from crawler state into per-screen XML, and the saved-crawl loader cannot hydrate
that data back into tracker edges.

The external package name is still carried only indirectly:

- For skipped external-package edges, the package is embedded in the human
  message `Skipped external package '...'`.
- For approved/captured external-package edges, the package can often be
  inferred from the child screen record, but it is not stamped on the
  originating edge as structured XML.

## Detailed Findings

### Serializer and Reader Support Already Exists

`EdgeXmlView` has an optional `externalPackage` field:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:516`

`AccessibilityXmlSerializer.appendEdge(...)` emits
`external-package="..."` when that field is populated:

- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:347`
- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:369`

`ScreenXmlReader.parseEdge(...)` reads the same attribute back into
`EdgeXmlView.externalPackage`:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt:269`
- `app/src/main/java/com/example/apptohtml/crawler/ScreenXmlReader.kt:277`

There is focused serializer coverage proving that a manually supplied
`EdgeXmlView(externalPackage = "...")` is rendered into XML:

- `app/src/test/java/com/example/apptohtml/crawler/AccessibilityXmlSerializerTest.kt:132`

### Tracker Edge State Cannot Carry External Package

`CrawlEdgeRecord` has fields for edge identity, parent/child link, element
metadata, status, message, child screen name, and approval, but no
`externalPackage` field:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:480`

`CrawlRunTracker.addEdge(...)` accepts and stores `approval`, but has no
parameter for external package:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:73`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:83`

`CrawlRunTracker.updateEdgeStatus(...)` similarly cannot add or preserve
structured external package metadata:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:112`
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:123`

This is the central source-of-truth gap: the tracker is the state used to
rewrite screen XML, but the state object does not contain the value that XML
already knows how to represent.

### Coordinator Does Not Populate External Package on Edge XML Views

When an external boundary is detected, `DeepCrawlCoordinator` has the package in
`childPackageName` and passes it into pause/logging context:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:547`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:552`

On `CONTINUE`, the edge is updated with `IN_PROGRESS` and
`CrawlEdgeApproval.EXPLICIT`, but no structured external package is stored:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:576`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:578`

On `SKIP_EDGE`, the edge is updated to `SKIPPED_EXTERNAL_PACKAGE`, but the
package is only included inside the human-readable message:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:655`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:656`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:659`

When the coordinator builds `ScreenCrawlState` for XML rewrite, it maps
`CrawlEdgeRecord` to `EdgeXmlView` without setting `externalPackage`:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1773`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1808`
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1815`

Because `EdgeXmlView.externalPackage` defaults to `null`, the serializer never
emits `external-package` for coordinator-generated crawl state.

### Saved Crawl Loader Also Drops the Field

`ScreenXmlReader` can parse `external-package`, but `SavedCrawlLoader` converts
parsed `EdgeXmlView` instances back into `CrawlEdgeRecord`, whose constructor has
no `externalPackage` destination:

- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:101`
- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:118`
- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:132`

The loader derives durable allowed packages for explicit approvals from the
child screen's package, not from `edgeView.externalPackage`:

- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:136`
- `app/src/main/java/com/example/apptohtml/crawler/SavedCrawlLoader.kt:138`

That works only when an approved edge has a child screen. It does not address
skipped external-package edges, which have no child screen and need the
originating edge to carry the external package explicitly for auditability.

### Test Coverage Confirms the Lower-Level XML Capability, Not the Lifecycle

The serializer test proves that XML output includes `external-package` if
`EdgeXmlView.externalPackage` is manually set:

- `app/src/test/java/com/example/apptohtml/crawler/AccessibilityXmlSerializerTest.kt:148`
- `app/src/test/java/com/example/apptohtml/crawler/AccessibilityXmlSerializerTest.kt:157`

Coordinator tests cover external-package skip/continue behavior and approval
state, but the current search did not find assertions that generated
coordinator XML includes `external-package="..."` for skipped or approved
external-package edges:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:602`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:715`
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:768`

## Architecture Insight

There are currently two edge representations:

- `CrawlEdgeRecord`: durable tracker/manifest/source state.
- `EdgeXmlView`: XML projection parsed and rendered inside per-screen XML.

`externalPackage` exists only in the projection type. Since the coordinator's
XML rewrite is generated from tracker state, any edge field that should survive
normal crawl execution and saved-crawl hydration needs to exist on
`CrawlEdgeRecord` or another tracker-owned state structure.

## Decisions

- `externalPackage` should be present on every edge whose destination package
  differs from the parent package, including already-allowed external packages,
  not only edges that triggered a boundary decision.
- Saved-crawl loading should use `edgeView.externalPackage` to seed allowed
  packages for explicit approvals when the child screen is missing or has been
  pruned.
- Manifest JSON should not gain structured external package metadata as part of
  this issue. The scope is intentionally limited to per-screen XML.
