# ENG-0010 Validation Issue 2: External Package Is Not Persisted as Structured Edge XML

Date: 2026-05-19
Plan: `thoughts/shared/plans/2026-05-19-ENG-0010-one-crawl-per-app.md`

## Summary

The XML serializer and reader support an `external-package` attribute on
`<edge>`, but the coordinator never populates that field from crawler state.
External package information is therefore not persisted as structured XML on
originating edges.

## Evidence

Serializer support exists:

```kotlin
if (edge.externalPackage != null) {
    builder.append(""" external-package="${escape(edge.externalPackage)}"""")
}
```

Reader support exists:

```kotlin
externalPackage = node.optionalAttribute("external-package")
```

However, `CrawlEdgeRecord` does not store `externalPackage`, and
`DeepCrawlCoordinator.buildScreenCrawlState(...)` creates `EdgeXmlView` without
setting it. Skipped external-package edges currently preserve the package only
inside a human-readable message:

```kotlin
message = "Skipped external package '$childPackageName'."
```

## Impact

The saved per-screen XML is less auditable than the plan intended. Consumers
cannot reliably inspect an edge and determine the external package from a
structured attribute, especially for skipped edges where there is no child
screen to infer the package from.

This is a source-of-truth gap: the per-screen XML is supposed to carry
safety-relevant edge lifecycle data explicitly.

## Recommended Resolution

Add structured external-package persistence to the edge lifecycle:

1. Add `externalPackage: String? = null` to `CrawlEdgeRecord`.
2. Extend `CrawlRunTracker.addEdge(...)` and `updateEdgeStatus(...)` to accept
   and preserve `externalPackage`.
3. When an external boundary is detected, stamp the current edge with
   `externalPackage = childPackageName`, including skip and continue paths.
4. Include `externalPackage = edge.externalPackage` when building `EdgeXmlView`.
5. Hydrate `externalPackage` from `ScreenXmlReader` in `SavedCrawlLoader`.
6. Add tests for both skipped and approved external-package edges to assert the
   XML includes `external-package="..."`.

