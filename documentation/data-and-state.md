# Data, State, and Diagnostics

## Persisted data

### Selected app

The only persisted user-facing state today is the selected app reference.

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
- status message
- output file paths
- failure message
- scroll step count
- captured, skipped, and depth counters

This state is process-local and intentionally not persisted.

### Accessibility observations

The accessibility service keeps short-lived runtime facts such as:

- last observed package
- active capture coroutine
- live root tree used for scrolling and capture

## Output artifacts

### HTML output

- One merged HTML file per captured screen.
- Focused on pressable elements and user-readable labels.

### XML output

- One XML export per captured screen plus an optional merged accessibility XML export.
- Includes:
  - merged elements
  - scroll-step count
  - per-step node tree snapshots
  - safety-relevant flags such as `checkable` and `editable`

### Crawl manifest

- `crawl-index.json` tracks screens, edges, route metadata, dedup fingerprints, and aggregate counters.
- Route steps now preserve the `editable` flag so replay identity stays aligned with captured safety state.

### Storage location

Artifacts are written under app-private storage in a package-specific directory
managed by `CaptureFileStore`.

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
- Use diagnostics to explain live-device behavior before adding more crawler
  heuristics.
