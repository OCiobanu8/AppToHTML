# Data, State, and Diagnostics

## Persisted data

### Selected app

The only persisted user-facing *preference* is the selected app reference. A crawl's own
artifacts also persist each screen's identity, which the crawler reads back on resume — see
[Screen identity](#screen-identity) below.

**Files**

- `app/src/main/java/com/example/apptohtml/model/SelectedAppRef.kt`
- `app/src/main/java/com/example/apptohtml/storage/SelectedAppRepository.kt`
- `app/src/main/java/com/example/apptohtml/storage/SelectedAppRefCodec.kt`

**Stored fields**

- package name
- display name
- launcher activity
- selection timestamp

**Storage backend**

- Jetpack DataStore Preferences

## Runtime state

### Crawler UI state

`CrawlerSession` owns the in-memory session state exposed to the UI:

- current phase
- selected app
- request ID
- pause decision ID
- pause reason and progress snapshot fields
- status message
- output file paths
- failure message
- scroll step count
- captured, skipped, and depth counters

This state is process-local and intentionally not persisted.

### Snapshot UI state

`CrawlerSession.snapshotState` is a **separate** `StateFlow<SnapshotUiState>` carrying the result
of the most recent single-screen snapshot: status, screen name, package, output directory, element
count, and scroll step count.

It is deliberately not folded into `CrawlerUiState`. A snapshot is not a crawl, and moving
`CrawlerPhase` off a terminal value during a snapshot would cause `SnapshotCaptureCoordinator`'s
own "reject while a crawl is active" guard to reject every subsequent capture.

Like crawler UI state, it is process-local and not persisted.

### Accessibility observations

The accessibility service keeps short-lived runtime facts such as:

- last observed package
- active capture coroutine
- live root tree used for scrolling and capture

## Output artifacts

### Screen identity

Every captured screen's `.xml` and `.html` carry the screen's whole identity — name, element set and
traits — in a `<screen-identity>` block. This is the one part of a crawl's content that survives a
resume and that an operator is expected to **edit**: `SavedCrawlLoader` reads it back, and
route-replay arrival consults a settled screen's traits.

See [screen-identity.md](screen-identity.md) for the format, what a capture proposes, and the
validation tool.

### HTML output

- One merged HTML file per captured screen.
- Focused on pressable elements and user-readable labels.
- Each button `<a>` carries a `fingerprint="..."` attribute (the element's
  `ElementFingerprint`) for inspection and cross-referencing with the XML.

### XML output

- One XML export per captured screen plus an optional merged accessibility XML export.
- Includes:
  - merged elements
  - scroll-step count
  - per-step node tree snapshots
  - safety-relevant flags such as `checkable` and `editable`
  - a `fingerprint="..."` attribute on the merged `<element>` and on every
    pressable `<node>` (raw scroll-step trees and the synthetic merged tree);
    inspection-only, identity is recomputed from fields on load
  - crawl edge metadata, including structured `external-package` values for
    cross-package destinations

### Crawl manifest

- `crawl-index.json` tracks screens, edges, route metadata, dedup fingerprints, and aggregate counters.
- Route steps now preserve the `editable` flag so replay identity stays aligned with captured safety state.
- Route steps can also preserve `expectedPackageName` so replay can cross package boundaries intentionally.
- Screen records persist the captured `packageName` for replay, restore, and export.

### Graph artifacts

- `crawl-graph.json` stores a normalized graph snapshot with session metadata, nodes, and edges.
- `crawl-graph.html` stores a self-contained offline viewer that embeds the graph JSON directly.
- Graph nodes keep sibling artifact basenames, not absolute paths, so the exported session folder can be copied elsewhere and still work.
- Graph artifacts are refreshed whenever the manifest is refreshed so paused and partial crawls remain inspectable.

### Storage location

Artifacts are written under app-private storage in a package-specific directory
managed by `CaptureFileStore`.

Each deep-crawl session directory now contains:

- `crawl.log`
- `crawl-index.json`
- `crawl-graph.json`
- `crawl-graph.html`
- one HTML file and one XML file for each captured screen
- an optional merged accessibility XML file for each screen that used scroll merging

### Snapshot output

Single-screen snapshots are written by `SnapshotFileStore` into `snapshots/`, a **sibling** of
`crawl/` under the same package directory:

```
html/<target-package>/
├── crawl/                                     # untouched by the snapshot path
└── snapshots/
    └── <yyyyMMdd_HHmmss>_<token>_<label>/
        ├── screen_00000_<label>.html
        ├── screen_00000_<label>.xml
        ├── screen_00000_<label>_merged_accessibility.xml
        ├── snapshot.json
        ├── capture.log
        └── .done
```

Why a sibling rather than a subdirectory of `crawl/`: `CaptureFileStore.createSession` deletes
`crawl/` recursively when a fresh crawl starts, so anything stored inside it would be destroyed by
the next `New Crawl`.

`.done` is written strictly after every other file is closed, so a host-side poller that observes
it is guaranteed a complete directory. A failed capture writes `.failed` containing the reason and
never writes `.done`, so the two markers can never both exist. A failure that never resolved a
foreground package is bucketed under `html/_unknown/snapshots/` so the host script still finds a
terminal marker by token instead of timing out.

The snapshot XML is structurally identical to a crawl root screen's: `SnapshotCrawlState`
synthesizes a minimal `ScreenCrawlState` at `depth=0`, `isRoot=true`, with no resolved edges, using
the same two-step identity derivation the crawl path uses. The run-level session id is
`snapshot_<timestamp>` rather than `crawl_<timestamp>`, so provenance stays distinguishable in logs
even though the document shape matches.

Repeated snapshots of the same screen always create a new directory; there is no deduplication.

## Diagnostics

`DiagnosticLogger` writes:

- runtime info logs
- error logs
- crash logs

This is especially useful for crawler debugging on physical devices, because
scroll behavior and accessibility output vary by app.

## Operational guidance

- Persist only user intent, not large crawl sessions, unless there is a clear
  product need.
- Keep runtime crawler state in dedicated domain models so UI changes do not
  reshape core logic.
- Keep blacklist-backed safety flags explicit in persisted models so risky element handling is auditable.
- Save manifest and graph artifacts before asking the user to resolve a checkpoint pause.
- Use diagnostics to explain live-device behavior before adding more crawler
  heuristics.
