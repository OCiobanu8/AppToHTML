---
date: 2026-05-08
researcher: OCiobanu8
git_commit: f94f79ca78740b300fab02f7d348088a8c2e4e80
branch: codex/issue-8-destination-settling
repository: OCiobanu8/AppToHTML
topic: "One Crawl Per App: Per-Screen XML as the Source of Truth for Crawl State"
tags: [research, design, crawler, persistence, xml, fingerprint, frontier, resume, external-package-approval]
status: design-decided
last_updated: 2026-05-19
last_updated_by: OCiobanu8
---

# Research & Design: One Crawl Per App — Per-Screen XML as the Source of Truth

## Research Question

Move from "one crawl per run" (today: each tap of `Start Deep Crawl` makes a fresh
timestamped session directory and a fresh in-memory tracker) to "one crawl per
app" (a single, app-level crawl that persists across runs and can be extended).
Specifically, what is the current source of truth for crawl state — visited
screens, the remaining frontier, fingerprints — what is already persisted, what
is in-memory only, and what would have to change to load prior state from saved
XML / manifest data on the next run?

## Summary

The decision documented here is to make the **per-screen XML file the single
source of truth** for crawl state — not the `crawl-index.json` manifest — and
to extend each `<screen>` with a `<crawl>` block at its head that carries
everything `replayRouteToScreen(...)` and the frontier reconstruction need.
The JSON manifest is demoted to a derived end-of-run artifact (or dropped
entirely).

The original research traced *what state exists* (visited screens,
fingerprints, dedup index, route, edges, frontier, external-package
approvals, in-flight progress) and *which of those are persistable today*.
That analysis is preserved in the "Detailed Findings" sections below — it is
what motivates the design decisions in this document. The chosen design then
specifies where each piece of state lives in the new XML format. The
"Chosen Design" and "Resume UX" sections at the end of this document
specify the concrete schema and the user-facing surface; the "Resolved
Decisions" section maps each original open question to its resolution.

What this document decides:

- **Per-screen XML extension**: each `<screenId>_<slug>.xml` gains a `<crawl>`
  block before `<merged-elements>`. The existing `<merged-elements>` and
  `<scroll-steps>` sections are unchanged.
- **Decomposed name=value attributes** for human readability: the dense
  `v2:pkg:…:title:…:hint:…` fingerprint becomes
  `<screen-identity package="..." title="..." hint-1="..." hint-2="..." />`.
  Same for route steps' expected destinations. `<expected-replay>` is fully
  decomposed into an `<element>` list, mirroring `<merged-elements>` minus
  the fields `buildElementFingerprint(...)` excludes.
- **Edges live inside their `<element>`** in `<merged-elements>`, never in a
  separate edges block, because every outbound edge from a screen
  corresponds 1:1 to a pressable element on that screen.
- **Frontier + per-element work list** follow the hybrid model in the
  "Follow-up: Screens, Edges, and Frontier Reconstruction" section: per-
  screen `expansion-status` (`not_started | in_progress | complete`) plus
  per-edge lifecycle (`pending | in_progress | <terminal>`).
- **Screen IDs** stay run-local `screen_NNNNN` sequences but are loaded and
  allocated `max+1` on resume. Existing IDs and filenames are preserved
  untouched.
- **Screen names** are frozen on the parent's `<edge child-screen-name="...">`
  at first capture and reused verbatim on resume. `analyzeScreenName(...)`
  gains a `preferredName` short-circuit to prevent content-driven naming
  drift. This stabilizes both the filename slug *and* the `screenFingerprint`
  (which mixes the title in).
- **Run-level state** (`session-id`, `started-at`, `finished-at`, `status`,
  `max-depth-reached`) lives as attributes on the root screen's `<crawl>`
  block, identified by `is-root="true"`. No separate header file.
- **External-package approvals** are recorded by stamping
  `approval="explicit"` on the originating approval edge. The loader unions
  these across all XMLs to seed `allowedPackageNames` on resume. Revocation
  flips the marker to `approval="revoked"`. Denials remain
  `<edge status="skipped_external_package">`.
- **Failed edges become `pending` on every load** — retry is the default
  policy. Edge IDs are stable across the transition.
- **Edge IDs** are allocated `max(existing edge sequence) + 1`, mirroring
  the screen-ID rule.
- **Storage layout**: a single per-app `html/<package>/crawl/` directory
  replaces today's timestamped session subdirectory. "New Crawl" wipes the
  directory; "Re-expand X" wipes just that screen's edges.
- **Resume UX**: a saved-crawl picker on `MainActivity.kt` lists every screen
  with status badges and offers three modes — Continue auto (implicit
  frontier), Resume from X (frontier seeded with X), Re-expand X (wipe X's
  edges then resume). It also surfaces an "Allowed external packages" list
  with per-package revoke.

A reader scanning every `*.xml` in the per-app directory becomes the
equivalent of the manifest reader the original analysis identified as
missing.

Related prior work: `documentation/github-open-issues-research-2026-04-25.md`
covers GitHub issues #3 (canonical screen IDs — partially shipped) and #4
(one saved crawl per app — what this document specifies).

## Detailed Findings

### Where the source of truth lives today

The runtime crawl is held by two collaborating objects:

- `CrawlRunTracker` — `CrawlRunTracker.kt:3-15`. Owns:
  - `screens: MutableList<CrawlScreenRecord>` — visited screens.
  - `edges: MutableList<CrawlEdgeRecord>` — every edge tried, with status.
  - `screenFingerprintToId: linkedMapOf<String, String>` — dedup index used by
    `findScreenIdByFingerprint(...)` to short-circuit revisits.
  - `rootScreenId`, `nextScreenSequence`, `nextEdgeSequence` — sequence
    counters used to allocate new IDs.
  - There is **no** constructor that loads from a saved manifest
    (`CrawlRunTracker.kt:74-89` only builds outward).

- `DeepCrawlCoordinator.crawl(...)` — `DeepCrawlCoordinator.kt:35-246`. Owns:
  - `frontier: ArrayDeque<String>` — the BFS queue of screen IDs to expand
    (`DeepCrawlCoordinator.kt:127-128`). Created empty, seeded with the root
    screen ID, then mutated as children are captured.
  - `resolvedLinksByScreenId: MutableMap<String, MutableMap<PressableElementLinkKey, String>>`
    — parent screen ID → element key → child HTML filename, used by
    `CaptureFileStore.rewriteScreenHtml(...)` to render anchors. Lives in the
    coordinator instance (`DeepCrawlCoordinator.kt:29`).
  - `allowedPackageNames: linkedSetOf<String>` — packages the user has
    "Continue"d into during this run (`DeepCrawlCoordinator.kt:33,61-62,379`).
    Reset at the start of every crawl.
  - `cachedRootSnapshot` — short-lived to avoid re-scanning the root the first
    time it is dequeued (`DeepCrawlCoordinator.kt:135-136,157-159`).

`CrawlerSession` (`CrawlerModels.kt:19-267`) owns only UI-facing state — the
phase machine, status messages, paused-decision metadata, and output paths.
Nothing in `CrawlerUiState` is required to resume traversal: it is a derived
view, not a record.

### What is already persisted (the manifest)

`CrawlManifest` (`CrawlerModels.kt:458-468`) is serialized to
`crawl-index.json` via `CrawlManifestStore.toJson(...)` and rewritten after
every material event (every screen capture, every edge resolution, every
pause). The persisted fields cover almost everything the in-memory tracker
holds:

- Session metadata: `sessionId`, `packageName`, `startedAt`, `finishedAt`,
  `status` (`CrawlRunStatus`: `IN_PROGRESS` / `COMPLETED` / `PARTIAL_ABORT` /
  `FAILED`), `rootScreenId`, `maxDepthReached`.
- For each screen (`CrawlScreenRecord`, `CrawlerModels.kt:427-442`):
  - `screenId`, `screenName`, `packageName`, `depth`.
  - `screenFingerprint` — the strong dedup key from
    `ScreenNaming.buildScreenIdentity(...)`
    (`ScreenNaming.kt:107-138`). Format `v2:pkg:<package>:title:<title>:hint:<…>`,
    deliberately bounds-free and intended to be reproducible across runs.
  - `replayFingerprint` — the bounds-free logical viewport fingerprint of the
    top-of-screen (`ScrollScanCoordinator.logicalViewportFingerprint(...)` /
    `logicalEntryViewportFingerprint(...)`,
    `ScrollScanCoordinator.kt:286-308`). Used by `prepareScreenForExpansion(...)`
    to validate that route replay landed on the right screen
    (`DeepCrawlCoordinator.kt:737-788`).
  - `htmlPath`, `xmlPath`, `mergedXmlPath` (absolute paths today).
  - `parentScreenId`, `triggerLabel`, `triggerResourceId`.
  - `route: CrawlRoute` — full ordered list of `CrawlRouteStep`s from the
    entry screen to this screen, each step carrying `childIndexPath`,
    `bounds`, `resourceId`, `className`, `label`, `checkable`, `checked`,
    `editable`, `firstSeenStep`, `expectedPackageName`,
    `expectedDestinationFingerprint`, `expectedReplayFingerprint`,
    `expectedReplayScreenName` (`CrawlerModels.kt:282-296`).
- For each edge (`CrawlEdgeRecord`, `CrawlerModels.kt:444-456`):
  `parentScreenId`, optional `childScreenId`, full element identity, status,
  and human message.

The manifest writer is robust — `CrawlManifestStore.toJson(...)` flushes
after every material change in `expandScreen(...)`
(`DeepCrawlCoordinator.kt:284,345,361,469,629`), and the writer is also called
on pause (`DeepCrawlCoordinator.kt:692`), abort, and failure
(`DeepCrawlCoordinator.kt:230`). So an interrupted run still leaves a
near-complete record on disk.

### What is NOT persisted

Concretely, the deltas between in-memory and on-disk state are:

- **The BFS frontier**. `frontier: ArrayDeque<String>`
  (`DeepCrawlCoordinator.kt:127-128`) is never written. It is only logged via
  `logFrontierState(...)` for diagnostic purposes
  (`DeepCrawlCoordinator.kt:130-134`, `615-620`). On a fresh process the
  frontier always starts as `{ rootScreenId }`.
- **`allowedPackageNames`**. Cleared at every crawl start
  (`DeepCrawlCoordinator.kt:61`). The user must re-approve external-package
  boundaries on every run, even if a route step records
  `expectedPackageName` for a previously-allowed external package.
- **`resolvedLinksByScreenId`**. Reconstructible from `CrawlEdgeRecord`s with
  `childScreenId != null` and `LINKED_EXISTING` / `CAPTURED` status, plus
  the screen records' `htmlPath` basenames — so this is a derivation, not a
  loss.
- **`nextScreenSequence` / `nextEdgeSequence`**. Reconstructible: max screen
  sequence number + 1, max edge sequence number + 1.
- **`cachedRootSnapshot` / live `AccessibilityNodeSnapshot` trees**. Not
  persistable in any useful way; they describe the live UI at one moment.
  Re-acquired via `restoreToEntryScreenOrRelaunch(...)` and
  `replayRouteToScreen(...)`.

So there is exactly one piece of irreducible state that has no equivalent on
disk today: the **frontier**. Everything else is either persisted or
derivable.

### Implicit "frontier" semantics — a key subtlety

Today a screen is either fully expanded or not yet expanded. There is no
mid-screen "this element is processed, that one is queued" granularity. The
loop pulls a screen off the deque, runs `TraversalPlanner.planTraversal(...)`,
and processes every eligible element synchronously inside `expandScreen(...)`
(`DeepCrawlCoordinator.kt:298-675`). Edges are written to the manifest as each
element is resolved, which means the manifest *implicitly* records progress at
the element granularity even though the frontier itself does not.

This means the frontier can be reconstructed from the manifest with a precise
rule: **a screen is "remaining" iff it has been captured (status = CAPTURED on
its inbound edge, or it is the root) AND its set of edges does not yet account
for every element `TraversalPlanner` would produce on a re-scan.** In
practice, partial mid-screen progress is also possible (the run died between
two `forEachIndexed` iterations), which is why one element at a time is
manifest-flushed.

### What goes into XML to make it the source of truth

`AccessibilityXmlSerializer.serialize(...)` (`AccessibilityXmlSerializer.kt:4-87`)
writes a per-screen `<screen>` element with:

- merged `<element>` entries (label, resource-id, class, bounds,
  `child-index-path`, `checkable`, `checked`, `editable`, `first-seen-step`),
- `<scroll-steps>` with per-step `<node>` trees that include synthetic-scroll-
  container flags, `source-step-indices`, `first-seen-step`, etc.

The `mergedXmlDump` companion is the same data flattened across scroll steps.

The chosen direction is to **extend `<screen>` with a `<crawl>` block** at
the head that carries the small additional set of fields needed for resume
(identity, route, parent pointer, per-element edge state, run-level state on
the root). The existing `<merged-elements>` and `<scroll-steps>` sections
stay unchanged. Each `<element>` in `<merged-elements>` additionally gains a
single `<edge>` child describing the click outcome for that element.

This costs verbosity relative to the compact JSON manifest, but the trade is
acceptable: phones have plenty of RAM and disk, and each screen's XML becomes
wholly self-describing — open one file and see the captured UI, the route to
get there, every outbound edge with its lifecycle status, and the parent
pointer. A directory scan reading every XML reconstructs the full crawl
state with no separate manifest file required.

The complete schema is specified in the "Chosen Design" section below.

### Storage layout: per-run directory, not per-app

`CaptureFileStore.createSession(...)` (`CaptureFileStore.kt:16-45`) always
creates `<external>/html/<packageName>/crawl_<yyyyMMdd_HHmmss>/`, with a
numeric suffix when timestamps collide. So the package directory holds a
*list* of historical sessions today.

Each session directory contains: `crawl-index.json`, `crawl.log`,
`crawl-graph.json`, `crawl-graph.html`, plus per-screen
`<screenId>_<slug>.html`, `<screenId>_<slug>.xml`, and optional
`<screenId>_<slug>_merged_accessibility.xml`
(`CaptureFileStore.kt:47-69`, recently switched to canonical screen-ID
filenames).

For "one crawl per app", the session directory needs to migrate either to:

- a stable per-app location like `html/<packageName>/crawl/`, replacing or
  in-place-updating the prior session, or
- the existing timestamped layout but with a "current saved crawl" pointer
  (e.g. a `latest` symlink / a marker file) so prior runs are rotated out.

The legacy single-capture path `CaptureFileStore.save(...)`
(`CaptureFileStore.kt:95-118`) and `preparePackageDirectory(...)`
(`CaptureFileStore.kt:120-131`) already model "wipe a package directory before
writing", but `preparePackageDirectory(...)` only deletes top-level files and
will not recurse into a session subdirectory. Any replacement strategy for
deep crawls must cope with directories.

### Screen IDs are per-run sequences, not stable identities

`screenIdFor(sequenceNumber)` returns `screen_%05d`
(`DeepCrawlCoordinator.kt:1548-1550`). The sequence is allocated by the
in-memory tracker (`CrawlRunTracker.nextScreenSequenceNumber()`,
`CrawlRunTracker.kt:15`). Two runs that visit the same screens in different
orders will assign different IDs to the same `screenFingerprint`.

Several outputs reference the screen ID directly:

- Per-screen filenames (`screen_00001_home.html`, `…xml`,
  `…_merged_accessibility.xml`).
- `crawl-index.json` `screenId` fields and `parentScreenId` references.
- `crawl-graph.json` nodes and edges (via `CrawlGraphBuilder`).
- `crawl.log` lines (`frontier_dequeue screenId=...`, etc.).
- HTML cross-links between screens (filename basenames stored in
  `resolvedLinksByScreenId`).

For a saved crawl that gets *extended* over multiple runs, two options exist:

1. **Keep sequence IDs but never re-assign**. Load the manifest, set
   `nextScreenSequence = max(existingSeq) + 1`, and only mint new IDs for
   newly discovered screens. Existing files keep working untouched. This is
   the minimum-viable path.
2. **Switch to fingerprint-derived IDs** (e.g. a short hash of
   `screenFingerprint`). Stable across runs, makes diffing two crawls
   trivial, but it is a one-time migration that must rewrite filenames and
   manifest references.

Option (1) interoperates with everything in place today; option (2) is more
disruptive but more aligned with "one crawl per app" semantics.

### Replay machinery already works for arbitrary screens

`DeepCrawlCoordinator.replayRouteToScreen(...)`
(`DeepCrawlCoordinator.kt:1158-1299+`) is already a self-contained function
that takes a `CrawlScreenRecord` (containing the route) and a `tracker` and:

1. Restores the entry screen via `restoreToEntryScreenOrRelaunch(...)`.
2. For each route step: rewinds to top, scrolls to the step's
   `firstSeenStep`, clicks the recorded element, and uses
   `DestinationSettler` with `mode = ROUTE_REPLAY` plus the persisted
   `expectedPackageName`, `expectedReplayFingerprint`, and
   `expectedDestinationFingerprint` to validate landing
   (`DeepCrawlCoordinator.kt:1257-1299`).
3. Logs `replay_route_step_*` for every step.

The function depends on `tracker` only to look up parent screens by ID via
`tracker.findScreen(...)`. If `CrawlRunTracker` were hydrated from a saved
manifest, this code would work unchanged. There is no traversal-time logic
that assumes a fresh in-memory tracker — only the construction does.

`prepareScreenForExpansion(...)` (`DeepCrawlCoordinator.kt:737-788`) already
re-scans a remembered screen, validates `screenFingerprint` against the
manifest, and returns a fresh `ScreenSnapshot`. That is exactly what a
"resume from screen N" or "extend the crawl" path would do for each item it
pulls off a reconstructed frontier.

### Fingerprint stability across runs

For "one crawl per app" to work, both fingerprints must be reproducible across
runs against the same app build. Current behavior:

- `screenFingerprint` is `v2:pkg:<package>:title:<screenName>:hint:<top‑2 normalized
  identity hints>` (`ScreenNaming.kt:107-138`). It is bounds-free and depends
  only on the screen name (chosen by `ScreenNaming.analyzeScreenName(...)`,
  which is itself deterministic given the same accessibility tree) plus
  semantic identity hints. **Stable across runs** for a given app build,
  modulo content-driven naming swings (visible-text screen naming can pick
  different titles when the visible text differs).
- `replayFingerprint` is `logicalViewportFingerprint(top)` /
  `logicalEntryViewportFingerprint(top)`
  (`ScrollScanCoordinator.kt:286-308`). It is bounds-free, sorts elements,
  and excludes back affordances for entry screens. **Stable across runs**
  unless the top-of-screen content changes (e.g. dynamic feed content). The
  test `logicalViewportFingerprint_ignores_root_and_element_bounds_shifts`
  confirms the bounds-free property.

Cross-run stability is therefore strong enough for dedup and replay
validation. There are still legitimate reasons a fingerprint may flip on the
next run (notification dot rendering, badge counts, dynamic top headers); the
manifest already accepts those by treating fingerprint mismatches as edge
failures rather than crashes (`prepareScreenForExpansion(...)` returns a
`Failure` result that becomes a `FAILED` edge).

### Cross-run failure recovery

Because every screen-level operation already has a "scan, compare against
recorded fingerprint, fail the edge or recover" path, a saved crawl that
encounters drift on the next run already has an articulated story: it fails
the impacted edge with a `replay_route_step_validation` log entry, recovers
to a replayable state, and continues with the next item from the frontier.
Persisting the frontier therefore does not introduce a new class of failure
mode — it reuses the same one.

### Historical context

`documentation/github-open-issues-research-2026-04-25.md` is the closest prior
work to this question. Its Issue #4 section exactly maps what this research
asks (one saved crawl per app, resume from any screen, manifest reader
required). Its "Missing Saved Resume Pieces" list (lines 178-188) and
"Cross-Issue Intersections" (lines 196-222) anticipate the same gaps this
document describes — manifest reader, single-app session directory,
artifact replacement on rescan, screen IDs as durable identifiers, and the
external-package foreground correctness gap that Issue #2 surfaces.

Note: Issue #3 (canonical screen-ID filenames, 5-digit IDs) was partially
open at the time of that prior document but is now in place — `screenIdFor`
returns `screen_%05d`, and `CaptureFileStore.saveScreen(...)` writes
`${screenId}_${slug}.html` directly, no `root` / `child` prefix. Recent
commit f558933 ("Use canonical screen artifact filenames") completed that
migration.

## Code References

In-memory state of record:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:3-15` —
  in-memory crawler state; no manifest-loading constructor.
- `app/src/main/java/com/example/apptohtml/crawler/CrawlRunTracker.kt:93-95` —
  fingerprint→id index lookup used for dedup.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:127-129` —
  `frontier: ArrayDeque<String>` is the only piece of irreducible state that
  is not persisted today.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:29` —
  `resolvedLinksByScreenId` (parent→element→child filename).
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:33,61-62,378-379` —
  `allowedPackageNames` (cleared every run; not persisted).

Persistence:

- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:411-468` —
  `CrawlRunStatus`, `CrawlEdgeStatus`, `CrawlScreenRecord`, `CrawlEdgeRecord`,
  `CrawlManifest` data classes.
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerModels.kt:282-296` —
  `CrawlRouteStep` carries every field replay needs.
- `app/src/main/java/com/example/apptohtml/crawler/CrawlManifestStore.kt:14-98` —
  manifest serializer; writer-only, no reader.
- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:16-45` —
  per-run timestamped session directory creation.
- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:47-77` —
  per-screen artifact saving and HTML rewriting.
- `app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt:120-131` —
  legacy `preparePackageDirectory` (only deletes top-level files).

Fingerprinting:

- `app/src/main/java/com/example/apptohtml/crawler/ScreenNaming.kt:107-138` —
  `buildScreenIdentity(...)` produces the bounds-free
  `v2:pkg:…:title:…:hint:…` `screenFingerprint`.
- `app/src/main/java/com/example/apptohtml/crawler/ScrollScanCoordinator.kt:286-352` —
  `logicalViewportFingerprint(...)`,
  `logicalEntryViewportFingerprint(...)`, and the underlying
  bounds-free element-fingerprint builder.

Replay (already manifest-driven):

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:737-788` —
  `prepareScreenForExpansion(...)`: replay route, rescan, validate
  fingerprint.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:1158-1299+` —
  `replayRouteToScreen(...)`: pure function over a `CrawlScreenRecord`.
- `app/src/main/java/com/example/apptohtml/crawler/PathReplayResolver.kt:28-65` —
  generic child-index-path resolution for a captured tree.

XML output (artifact, not state):

- `app/src/main/java/com/example/apptohtml/crawler/AccessibilityXmlSerializer.kt:4-87` —
  full per-screen XML emission.

Coordinator entry path that mints a fresh session per call:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:35-43` —
  `crawl(...)` builds a brand-new tracker and a brand-new session every time.

Prior research:

- `documentation/github-open-issues-research-2026-04-25.md:135-191` — Issue #4
  current state, missing pieces, and acceptance assessment for one-saved-crawl-
  per-app.
- `documentation/github-open-issues-research-2026-04-25.md:194-222` —
  cross-issue intersections (screen IDs as product API, artifact replacement
  on resume, external-package correctness, single-saved-crawl semantics).
- `documentation/data-and-state.md:1-117` — what is persisted vs runtime-only.
- `documentation/crawler-module.md:111-165` — entry-restore and replay
  semantics that already underpin resume.

## Architecture Insights

- **The chosen source of truth is the per-screen XML, not the manifest.**
  Every "what did the crawler visit and what did it find" question is
  answered by scanning the `<crawl>` blocks at the head of each
  `<screenId>_<slug>.xml`. The in-memory tracker is the *write side* of that
  source of truth — a cache that gets rewritten back to the affected
  screen's XML after every interesting event. The JSON manifest is demoted
  to a derived end-of-run artifact. The original analysis observed that the
  manifest already had most of the durable building blocks; the chosen
  design moves those same fields onto the screen XMLs (where each piece is
  naturally local to one file) rather than centralizing them.

- **All previously-not-persisted state is folded into the XML.** The BFS
  frontier becomes derivable from per-screen `expansion-status` plus
  per-edge `pending` / `in_progress` lifecycle. The user-approved external-
  package set becomes derivable from `<edge approval="explicit">` markers
  unioned across all XMLs. The parent→child resolved-link map is
  rebuildable from the edges' `child-screen-id` fields. No external
  `crawl-progress.json` sidecar is needed.

- **Replay validation is symmetric with discovery.** The same
  `replayRouteToScreen(...)` plus `screenFingerprint` re-check that runs
  during a single run for every non-root screen (so it can recover from
  state drift between expansions) is the same machinery that would validate a
  resumed crawl against a saved manifest. There is no resume-specific code to
  write for the happy path; the gap is in tracker construction and frontier
  rehydration, not in traversal.

- **Screen IDs are the seam.** They appear in filenames, the manifest, the
  graph, and HTML cross-links. Anywhere two runs could produce the same
  fingerprint and want to be the same record, the screen ID has to remain
  stable. The cheapest path is "load existing IDs, allocate new sequence
  numbers above the existing max"; the cleanest path is fingerprint-derived
  IDs, which would also fix a long-tail issue where two app versions of the
  same screen produce the same `screenFingerprint` but different
  `screen_NNNNN` IDs across runs.

- **`fingerprint` is intentionally bounds-free already.** That is the design
  signal that fingerprints are meant to survive UI shifts within a run; the
  same property makes them survive shifts between runs as long as the screen
  identity (title + hints) is stable. The crawler already accepts and
  recovers from drift on the order of "this captured screen no longer
  matches its replay fingerprint" — that recovery story carries over to
  cross-run drift unchanged.

- **External-package handling is the cross-run hazard.** The
  `allowedPackageNames` set is reset every run and the user is asked again at
  every external-package boundary. For a saved crawl that already has route
  steps with `expectedPackageName != selectedApp.packageName`, the design
  decision is whether the user's prior approval is durable. Persisting it
  alongside the frontier (per-app, not per-run) is the simplest answer.
  Issue #2's foreground correctness bug is independent of persistence but
  must be resolved before resumed external routes can be replayed safely.

- **`crawl-graph.json` and `crawl-graph.html` are derived artifacts.** They
  are rebuilt from the manifest on every save (`CaptureFileStore.saveGraph`,
  `CrawlGraphBuilder.build`). A cross-run loader does not need to read them —
  it can rebuild them from the loaded manifest the same way the in-run code
  does.

## Historical Context (from documentation/ and thoughts/)

- `documentation/github-open-issues-research-2026-04-25.md` — directly
  predicts the saved-crawl-per-app shape this question asks about and lists
  nearly the same set of missing pieces. Should be the primary reference for
  the implementation plan.
- `documentation/data-and-state.md` calls this transition out as a "good
  next extension": "Persist richer crawl sessions for later replay or
  diffing" (`documentation/crawler-module.md:200-203`).
- `documentation/pause-resume-flow.md` describes the *live* pause/resume
  decision mechanism. That code path is unrelated to saved-crawl resume but
  shares vocabulary; care should be taken to keep "resume from pause" and
  "resume from saved crawl" as distinct concepts in any new API.

## Related Research

- `documentation/github-open-issues-research-2026-04-25.md` — Issues #2, #3,
  #4 covering canonical screen IDs, saved-crawl-per-app, and external-package
  foreground correctness.
- `documentation/deep-crawl-graph-pr1-research-2026-04-21.md` — earlier
  research on fingerprint dedup, weak-title guard, and `LINKED_EXISTING`
  edges (the dedup machinery this design relies on).
- `documentation/data-and-state.md` — current persistence inventory.
- `documentation/crawler-module.md` — entry restore as a verified replay
  prerequisite, route replay step validation, settling behavior.

## Resolved Decisions

The original open questions are resolved as follows:

1. **Frontier rehydration source.** *Adopted: hybrid model.* Per-screen
   `expansion-status` (`not_started | in_progress | complete`) plus per-edge
   lifecycle (`pending | in_progress | <terminal>`) on each `<edge>`. No
   `crawl-progress.json` sidecar; no `TraversalPlanner` re-run on load. See
   "Follow-up: Screens, Edges, and Frontier Reconstruction" for the model
   details.

2. **Mid-screen progress granularity.** *Resolved by the hybrid model.*
   `pending` edges *are* the work items, carrying the element's identity on
   the edge itself. No cross-run element-matching is required. An
   `in_progress` edge from a crashed run is treated as `pending` on resume.

3. **Screen ID stability across runs.** *Adopted: run-local sequence loaded
   from XML.* `nextScreenSequence = max(existing screen-id sequence) + 1`.
   Existing IDs and filenames are preserved untouched. No fingerprint-
   derived ID migration.

4. **Single-directory layout for `<package>/`.** *Adopted: one
   `html/<package>/crawl/` per app.* No timestamped sub-sessions, no
   `current` pointer.

5. **External-package approvals across runs.** *Adopted: per-edge
   `approval="explicit"` marker on the originating approval edge.* The
   loader unions these into the per-package `allowedPackageNames` set.
   Revocation flips to `approval="revoked"` and the loader excludes it.

6. **Manifest schema evolution.** *Adopted: `<crawl schema="v1">` attribute*
   on every `<crawl>` block. Legacy XMLs lacking `<crawl>` are treated as
   `expansion-status="complete"` with no edges (BC waiver per `CLAUDE.md`).

7. **What does `New Crawl` do?** *Adopted: wipe the per-app `crawl/`
   directory and start fresh.* Re-expansion of individual screens (via the
   "Re-expand X" picker action) covers partial-rebuild needs without
   requiring a full restart.

8. **Expected-replay representation in route steps.** *Adopted: fully
   decomposed* `<expected-replay>` with `<element>` children. No opaque-
   string fallback. Phones have plenty of RAM and the readability win
   outweighs the verbosity cost.

9. **Edge ID allocation on resume.** *Adopted: `max(existing edge sequence)
   + 1`.* Mirrors the screen-ID rule. Edge IDs are stable across status
   transitions.

10. **Failed-edge retry policy.** *Adopted: transition `failed → pending`
    on every load.* The `message` attribute is dropped on rewrite. Edge ID
    is preserved.

### Still-open follow-ups

- **Chronic-failure retry caps.** An element that always fails will retry
  on every run. Add a `retry-count` attribute on `<edge>` and cap retries
  if it becomes a real annoyance. Not designed in for now.
- **Last-failure-message retention.** Clearing `message` on the
  `failed → pending` rewrite drops the prior diagnostic. If history matters,
  store it as `last-failure-message` on the now-`pending` edge and clear
  only on a new terminal status.
- **Orphan handling on re-expansion.** If a screen's UI changes between
  captures and prior descendants are no longer reachable, those captured
  XMLs become unreferenced. Surface count and offer cleanup; design pending.
- **Linked-existing carrying approval semantics.** A future enhancement
  could let an existing `linked_existing` edge "transfer" the originating-
  approval role to itself if the original `captured` edge is later wiped,
  so a single re-expansion doesn't accidentally revoke a package. Not
  designed in for now.

## Follow-up: Fingerprints in Detail

The two fingerprints have different jobs, different inputs, and different
stability targets. They are computed by different classes and are not
interchangeable.

### `screenFingerprint` — "is this the same logical screen we've seen before?"

**Used for**: cross-screen *dedup*. After capturing a child screen, the
coordinator computes its `screenFingerprint` and asks
`tracker.findScreenIdByFingerprint(...)`
(`CrawlRunTracker.kt:93-95`). If a hit comes back, the edge is recorded as
`LINKED_EXISTING` instead of being added to the BFS frontier. This is what
prevents the crawler from re-exploring "Settings home" every time it's
reached from a deeper screen.

**Built by**: `ScreenNaming.buildScreenIdentity(...)`
(`ScreenNaming.kt:107-138`).

**Format**:
```
v2:pkg:<normalizedPackage>:title:<normalizedTitle>:hint:<top‑2 hints joined by | or "none">
```

Each piece:
- `<normalizedPackage>` — `selectedApp.packageName` lowercased and reduced to
  `[a-z0-9_]` by `normalizeIdentityToken` (`ScreenNaming.kt:559-566`); falls
  back to `"unknown"`.
- `<normalizedTitle>` — same normalization applied to the chosen
  `screenName`. The screen name itself comes from
  `analyzeScreenName(...)`: event window class name → strongest visible-text
  candidate → resource-id-derived candidate → launcher activity → app name
  (`ScreenNaming.kt:140-245`). Falls back to `"unnamed"`.
- `<hints>` — up to two non-title identity hints from
  `collectIdentityHints(root)` (`ScreenNaming.kt:373-396`): high-scoring
  visible-text candidates (score ≥ `minIdentityHintScore` = 120,
  `ScreenNaming.kt:10`) plus visible resource-id-derived titles. Boilerplate
  strings in `weakChromeTitles` (`navigate up`, `more options`,
  `recommended`, `all services`, `back`, `up`) and `weakCallToActionTitles`
  (`sign in`, `sign in to continue`, `continue`, `continue with google`,
  `continue with email`) are filtered out so chrome doesn't leak into
  identity (`ScreenNaming.kt:67-81,545-550`). If nothing survives, the slot
  is the literal string `"none"`.

**Stability story**: deliberately bounds-free, sorted-set-based, and
chrome-aware so it stays the same when scroll position, view bounds, or back-
button state shift. It's the strongest fingerprint of the two — coarse,
identity-flavored, and cheap to compare.

**Confidence gate**: `STRONG` is only set when (the title is not weak) AND
(there's at least one identity hint OR the package is non-blank);
`WEAK` otherwise. `canLinkToExisting` returns true only for `STRONG`
(`ScreenNaming.kt:602-608`). A `WEAK` identity (e.g. a screen titled "Sign
in" with no hints) is *not* added to `screenFingerprintToId` (see
`indexFingerprint = childScreenIdentity.canLinkToExisting`,
`DeepCrawlCoordinator.kt:584`), so dedup is suppressed and the crawler
treats it as a new screen. This protects against false dedup hits on
generic chrome screens.

### `replayFingerprint` — "is the live screen the same shape as the one I captured?"

**Used for**: *replay validation*, not dedup. Two specific places:

1. `prepareScreenForExpansion(...)` (`DeepCrawlCoordinator.kt:737-788`) —
   after replaying a route, rescans the screen and compares the live
   `screenFingerprint` (not `replayFingerprint`) against the recorded one
   to validate that replay landed correctly.
2. `replayRouteToScreen(...)` (`DeepCrawlCoordinator.kt:1158-1299`) and
   `openChildFromScreen(...)` use the `expectedReplayFingerprint` carried
   on each `CrawlRouteStep` to validate intermediate steps as the route is
   walked. Mismatch produces a `replay_route_step_validation` log entry and
   fails the edge.

**Built by**: `ScrollScanCoordinator.buildViewportFingerprint(...)`
(`ScrollScanCoordinator.kt:321-352`), called via two thin wrappers:

- `logicalEntryViewportFingerprint(root)` (`ScrollScanCoordinator.kt:302-308`)
  — used for the **root/entry screen**. Excludes back affordances near the
  top via `looksLikeEntryBackAffordance` (top ≤ 300 px AND label/resource-id
  matches "back" / "navigate up" / "up" variants,
  `ScrollScanCoordinator.kt:372-393`). This makes the entry fingerprint
  robust to whether or not the chrome shows a back arrow.
- `logicalViewportFingerprint(root)` (`ScrollScanCoordinator.kt:286-292`) —
  used for **every non-root screen**. Keeps back affordances.

Both are bounds-free (`includeBounds = false`).

**Format**:
```
<rootClassName>::<elem1>||<elem2>||...||<elemN>
```

Where:
- `<rootClassName>` is `root.className` of the live
  `AccessibilityNodeSnapshot` — typically a layout class such as
  `android.widget.FrameLayout`.
- Each `<elemK>` is a *pressable* element from
  `AccessibilityTreeSnapshotter.collectPressableElements(root)`,
  deduplicated by `mergedElementFingerprint`
  (`ScrollScanCoordinator.kt:310-319`), then **sorted alphabetically**.
- Per-element format from `buildElementFingerprint`
  (`ScrollScanCoordinator.kt:354-370`):
  ```
  <label>|<resourceId>|<className>|<isListItem>|<checkable>|<checked>|<editable>
  ```
  Bounds are *not* included in the logical variant.

The full fingerprint is therefore: root chrome class, then a sorted list of
pressable controls with their identity flags. Pure scrolling that doesn't
reveal new content does not change it; tapping a checkbox does (`checked`
flips); navigating to a different screen does.

### Worked example

Given:
```
android.widget.FrameLayout::All services||android.view.View|false|false|false|false||Give feedback|ClickableText|android.widget.TextView|false|false|false|false||More options||android.view.View|false|false|false|false||Navigate up||android.view.View|false|false|false|false||Sign in|ButtonAction|android.view.View|false|false|false|false||Sign in|Card: Sign in|android.view.View|false|false|false|false
```

**Header**: `android.widget.FrameLayout` is the root class, before the `::`
separator.

**Parsing footgun**: when an element has an empty `resourceId`,
`joinToString("|")` produces a literal `||` *inside* that element fingerprint,
which collides with the `||` element separator. The string is unambiguous
only if you know "exactly 7 fields per element" and split accordingly.
Naive `split("||")` is wrong. This is worth flagging for any future tooling
that wants to parse these fingerprints.

**Decoded elements** (alphabetically sorted by joined element string):

| # | label | resourceId | className | listItem | checkable | checked | editable |
|---|---|---|---|---|---|---|---|
| 1 | `All services` | *(empty)* | `android.view.View` | false | false | false | false |
| 2 | `Give feedback` | `ClickableText` | `android.widget.TextView` | false | false | false | false |
| 3 | `More options` | *(empty)* | `android.view.View` | false | false | false | false |
| 4 | `Navigate up` | *(empty)* | `android.view.View` | false | false | false | false |
| 5 | `Sign in` | `ButtonAction` | `android.view.View` | false | false | false | false |
| 6 | `Sign in` | `Card: Sign in` | `android.view.View` | false | false | false | false |

**Observations**:

- `"Navigate up"` is present, so this was emitted by
  `logicalViewportFingerprint` (non-entry variant). For an entry screen
  the back-affordance filter would have stripped it. That tells you this
  `replayFingerprint` belongs to a **non-root** `CrawlScreenRecord` — the
  root would have been written with `logicalEntryViewportFingerprint`.
- `resourceId` values like `ClickableText`, `ButtonAction`,
  `Card: Sign in` aren't standard Android `package:id/foo` form — they are
  Compose semantics surfaced through `viewIdResourceName`. They flow
  through unchanged, which is fine for fingerprinting because all the
  algorithm needs is reproducibility.
- Two rows differ only by `resourceId`: both labeled `"Sign in"` but one is
  the action button and one is the surrounding card. They're kept as
  separate elements because `mergedElementFingerprint` treats `resourceId`
  as part of identity.
- Every flag is `false` here, so nothing on this screen is a list item,
  checkable, checked, or editable. A toggle being flipped from off → on
  would mutate `checked` from `false` to `true` and the fingerprint would
  change. That's intentional: replay onto a screen with a different toggle
  state should fail.

### Why two fingerprints instead of one

They optimize for different things:

- `screenFingerprint` is *coarse* on purpose. It collapses across scroll
  position, viewport, dynamic content, and chrome variations so the dedup
  table can answer "have we already captured the Settings home screen?"
  robustly. Element-set differences won't make it lie about identity.
- `replayFingerprint` is *fine* on purpose. It includes every pressable
  control's flags so route replay can detect "we landed on Settings, but the
  wrong tab is selected" or "this screen is in a loading state with one
  fewer button than when we captured it." Mismatches become `FAILED` edges,
  which is a desirable safety signal.

### Implication for "one crawl per app"

If the saved-crawl model needs a stable, cross-run identifier, the
`screenFingerprint` is the right candidate — it's already designed for
cross-run reproducibility (bounds-free, chrome-aware, identity-driven). The
`replayFingerprint` is reproducible for a static screen but will legitimately
drift across runs whenever the screen has any dynamic content (badges,
counts, time-based UI, loading states), so it should keep its per-step soft-
check role rather than be promoted to stable identity.

## Follow-up: Screens, Edges, and Frontier Reconstruction

This section drills into the two main tables in `crawl-index.json`,
`screens` and `edges`, and lays out the recommended approach for
reconstructing the unvisited frontier from them on resume.

### Anatomy of `CrawlScreenRecord` (one entry in `screens`)

`CrawlerModels.kt:427-442`. Each record describes one captured screen.

| Field | Type | What it is |
|---|---|---|
| `screenId` | `String` | Run-local identity, format `screen_NNNNN`. Allocated by `tracker.nextScreenSequenceNumber()`. Referenced from edges, filenames, graph nodes, and HTML cross-links. |
| `screenName` | `String` | Human-readable name from `ScreenNaming.analyzeScreenName(...)` (event class → strongest visible text → resource id → launcher activity → app name). |
| `packageName` | `String` | The Android package the screen belongs to. Almost always the selected app, but can differ for screens reached via accepted external-package boundaries. |
| `screenFingerprint` | `String` | The dedup key (`v2:pkg:…:title:…:hint:…`). Used by `tracker.findScreenIdByFingerprint(...)` to short-circuit revisits as `LINKED_EXISTING` edges. |
| `replayFingerprint` | `String` | Bounds-free logical viewport fingerprint of the top-of-screen. Used as `expectedReplayFingerprint` on the *outbound* route step from this screen. |
| `htmlPath` / `xmlPath` / `mergedXmlPath` | `String?` | Absolute paths to the per-screen artifact files. Filenames are `${screenId}_${slug}.html` etc. |
| `scrollStepCount` | `Int` | How many discrete scroll steps the merge required. Used for replay (when re-finding an element, scroll to its `firstSeenStep`). |
| `parentScreenId` | `String?` | The single parent in the spanning tree of "first reach". Null only for the root. |
| `triggerLabel` / `triggerResourceId` | `String?` | The element on the parent that first opened this screen. Stored mainly for graph display and diagnostic logs. |
| `route` | `CrawlRoute` | **The replay payload**. Ordered list of `CrawlRouteStep`, one per click from the entry screen down to this screen. Each step carries `childIndexPath`, `bounds`, `resourceId`, `className`, `label`, `checkable/checked/editable`, `firstSeenStep`, and the four `expected*` fields (`expectedPackageName`, `expectedDestinationFingerprint`, `expectedReplayFingerprint`, `expectedReplayScreenName`) used by `replayRouteToScreen(...)` to validate each step (`CrawlerModels.kt:282-296`). |
| `depth` | `Int` | Distance from root in the spanning tree. Equals `route.steps.size`. |

Subtlety: `route` is **fully self-contained**. Given just `route` and an
entry-screen restore primitive, you can replay to this screen without
touching `parentScreenId`. The parent chain is convenience metadata; the
route is the source of truth for replay.

### Anatomy of `CrawlEdgeRecord` (one entry in `edges`)

`CrawlerModels.kt:444-456`. Each record describes the outcome of trying *one
click* from one parent screen.

| Field | Type | What it is |
|---|---|---|
| `edgeId` | `String` | Run-local sequence, format `edge_NNN`. |
| `parentScreenId` | `String` | Always set. The screen the click happened on. |
| `childScreenId` | `String?` | Set only for `CAPTURED` and `LINKED_EXISTING`. Null otherwise. |
| `label`, `resourceId`, `className`, `bounds`, `childIndexPath`, `firstSeenStep` | — | The exact `PressableElement` that was tried, frozen at the moment the parent screen was scanned. |
| `status` | `CrawlEdgeStatus` | The outcome — see below. |
| `message` | `String?` | Human-readable detail (e.g. blacklist reason, "Captured child screen 'X'", failure message). |

The `status` enum (`CrawlerModels.kt:418-425`) is the most load-bearing field
for any frontier logic:

| Status                     | Meaning                                                                                                                                      | childScreenId? | Click happened?                            |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | -------------- | ------------------------------------------ |
| `CAPTURED`                 | Click → new child screen, child added to tracker and frontier.                                                                               | yes            | yes                                        |
| `LINKED_EXISTING`          | Click → child fingerprint matched an already-known screen. No new screen added.                                                              | yes            | yes                                        |
| `SKIPPED_BLACKLIST`        | `TraversalPlanner` rejected the element (system back, dangerous control, etc.) before any click happened.                                    | no             | no                                         |
| `SKIPPED_NO_NAVIGATION`    | Clicked, but `afterClickFingerprint == beforeClickFingerprint` or `== topFingerprint`, in same package. UI didn't change.                    | no             | yes                                        |
| `SKIPPED_EXTERNAL_PACKAGE` | Hit an external-package boundary; user chose "Skip" at the pause.                                                                            | no             | yes (the click that revealed the boundary) |
| `FAILED`                   | Tried but couldn't recover the click target / restore state / settle destination. Coordinator recovered to a replayable state and continued. | no             | attempted                                  |

### The graph these two tables form

- `screens` is a node table. The spanning tree (`parentScreenId`) is a forest
  with one root.
- `edges` is the actual reachability graph: every attempted click is an edge
  regardless of outcome. `LINKED_EXISTING` edges are exactly the "this click
  would have re-discovered an existing screen" edges that turn the spanning
  tree into a graph with cycles and shortcuts.
- A screen X's *outbound* edges are
  `edges.filter { it.parentScreenId == X.screenId }`. A screen X's *inbound*
  edges are `edges.filter { it.childScreenId == X.screenId }`. Inbound count
  is ≥ 1 for every non-root screen — exactly one of those is the spanning-
  tree edge that produced it (the one whose element matches `X.triggerLabel`
  / `X.triggerResourceId`).

### Important clarification: edge status vs child expansion status

A common source of confusion: **a `CAPTURED` edge does NOT mean the child
screen has been expanded.** `CAPTURED` only means "I clicked this element,
landed on a new screen, and added that new screen to `tracker.screens`." The
child has been *captured*, not *visited* in the frontier sense.

Whether the child has been expanded — i.e. whether `expandScreen(childScreenId)`
has run and produced its own outbound edges — is a property of the **child
screen**, not of the edge that led to it. In the live code today, this
property exists only as "the child's screenId is no longer in the
`ArrayDeque<String>` frontier." To resume across runs, that property must
have a durable home.

This is the critical decoupling: edges describe what *the parent did*;
expansion status describes what *the child did next*.

### What the live frontier actually represents

The in-memory frontier is `ArrayDeque<String>` of screen IDs
(`DeepCrawlCoordinator.kt:127`). A screen ID is on the frontier iff:

1. It has been added to `tracker.screens` (i.e. it's been *captured*), AND
2. `expandScreen(screenId)` has not yet completed.

`expandScreen(screenId)` does these things in order:

1. Replays the route to the screen and rescans it.
2. Calls `TraversalPlanner.planTraversal(snapshot, blacklist)` → produces
   `eligibleElements` and `skippedElements`.
3. Adds one `SKIPPED_BLACKLIST` edge per skipped element. Saves manifest.
4. Iterates `eligibleElements`. For each one, opens the child, decides
   outcome, adds exactly one edge per element with one of the five
   non-blacklist statuses, and saves the manifest after each iteration.
5. Returns.

So **every element from the live `planTraversal` either becomes an edge or
never gets touched.** The number of outbound edges from a fully expanded
screen X equals the number of elements `planTraversal` produced when X was
scanned.

### Recommended design: hybrid frontier reconstruction

The recommendation is to combine a screen-level expansion marker with a
per-edge lifecycle. This keeps the cheap frontier model for the common case
and avoids any cross-run element-matching guesswork for the partial case.

#### Step 1: add `ScreenExpansionStatus` to `CrawlScreenRecord`

```kotlin
enum class ScreenExpansionStatus {
    NOT_STARTED,   // captured but expandScreen has not begun
    IN_PROGRESS,   // expandScreen began but did not complete
    COMPLETE,      // expandScreen ran to completion
}

data class CrawlScreenRecord(
    // ...existing fields...
    val expansionStatus: ScreenExpansionStatus = ScreenExpansionStatus.NOT_STARTED,
)
```

Update at three points in `expandScreen(...)`:

- After step 3 (blacklist edges committed, eligible-element loop about to
  begin) → `IN_PROGRESS`. Save manifest.
- After step 4 (loop completed) → `COMPLETE`. Save manifest.

There is at most **one `IN_PROGRESS` screen at any moment**, because
expansion is single-threaded. That invariant is what makes the resume logic
tractable.

#### Step 2: add `PENDING` (and `IN_PROGRESS`) to `CrawlEdgeStatus`

Promote the edges table from "outcome log" to "work-list-and-outcome log" by
inserting per-edge state for elements identified but not yet resolved:

```kotlin
enum class CrawlEdgeStatus {
    PENDING,                   // planned but no attempt yet
    IN_PROGRESS,               // click started, not yet settled
    CAPTURED,
    LINKED_EXISTING,
    SKIPPED_BLACKLIST,
    SKIPPED_NO_NAVIGATION,
    SKIPPED_EXTERNAL_PACKAGE,
    FAILED,
}
```

Lifecycle inside `expandScreen(...)`:

1. Right after `planTraversal` runs, add one `PENDING` edge for every
   eligible element AND one `SKIPPED_BLACKLIST` edge for every skipped
   element. Save manifest. (`SKIPPED_BLACKLIST` stays terminal-on-creation
   because no click is ever attempted.)
2. Begin the eligible-element loop. Before each click, transition that edge
   `PENDING → IN_PROGRESS`. Save manifest.
3. After the click resolves, transition `IN_PROGRESS → CAPTURED /
   LINKED_EXISTING / SKIPPED_NO_NAVIGATION / SKIPPED_EXTERNAL_PACKAGE /
   FAILED`. Save manifest.

This makes edges *mutable* (transition through statuses) rather than purely
append-only as today. The JSON writer already rewrites the whole manifest on
every save, so this is only a serialization-format change, not an I/O change.

#### Step 3: resume logic falls out trivially

On manifest load:

```
frontier = []

// COMPLETE screens are done — skip unless explicitly resumed-from.
// NOT_STARTED screens are clean redos.
for screen in screens where screen.expansionStatus == NOT_STARTED:
    frontier.append(screen.screenId)

// At most one IN_PROGRESS screen. Its remaining work is the
// PENDING + IN_PROGRESS edges with parentScreenId == that screen.
inProgressScreen = screens.find { it.expansionStatus == IN_PROGRESS }
if inProgressScreen != null:
    frontier.appendFirst(inProgressScreen.screenId)
    // expandScreen will see existing edges and only attempt the
    // ones still in PENDING / IN_PROGRESS state.
```

And `expandScreen(...)` becomes naturally idempotent on resume: when it runs
on an `IN_PROGRESS` screen, instead of calling `planTraversal` afresh it
loads the existing edges for that screen, finds the ones still in
`PENDING` / `IN_PROGRESS` state, and processes only those. `NOT_STARTED`
screens behave exactly as today.

This answers the original question — "If an edge has parent =
`IN_PROGRESS` screen and status = `CAPTURED`, it means it wasn't
processed?" — with a precise, durable model:

- An edge with `status = PENDING` means the element was identified by
  `planTraversal` but the click was never attempted. **This is the
  remaining work for that screen.**
- An edge with `status = IN_PROGRESS` means the click was attempted but the
  outcome was not saved before the run died. **Treat this the same as
  `PENDING` on resume** (re-attempt; the new click will land on either the
  same destination or a `LINKED_EXISTING` if a child was actually created
  and persisted before the crash — which it wasn't, because the child
  screen save and the edge status update are in the same transaction-of-
  intent).
- An edge with any terminal status (`CAPTURED`, `LINKED_EXISTING`,
  `SKIPPED_*`, `FAILED`) means the click was resolved. **Whether the child
  itself has been expanded is on the child screen's `expansionStatus`, not
  on this edge.**

So: a `CAPTURED` edge tells you a child screen exists. The child's
`expansionStatus` tells you whether that child has been visited by
`expandScreen`. Two independent facts; two independent fields.

### Why the cheap heuristic is not enough

A purely edge-presence-based heuristic — "screen X has zero outbound edges,
therefore it's on the frontier" — sounds tempting but has two failure modes:

1. **Leaf screens with no eligible elements.** A confirmation screen with
   nothing clickable ends `expandScreen` with zero outbound edges. It looks
   identical to "captured but not yet expanded."
2. **Mid-screen interruption.** If the prior run died on element 4 of 7, the
   screen has 3 settled edges. Edge-presence says "it's been expanded"
   (because edges exist) — but it actually has 4 elements remaining. There
   is no way to tell from saved state which elements remain without
   re-planning.

Both failure modes vanish under the hybrid design: the leaf screen has
`expansionStatus = COMPLETE`, and the partially expanded screen has
`expansionStatus = IN_PROGRESS` plus surviving `PENDING` edges that pinpoint
exactly the remaining work.

### Side benefits of edge lifecycle state

- **No element-identity matching across runs is required.** The `PENDING`
  edges *are* the work items. They carry the saved `childIndexPath`,
  `firstSeenStep`, `bounds`, etc. directly, so the click-time logic does
  not have to fuzzy-match a re-planned element against a saved edge.
- **Failure semantics become explicit.** A `FAILED` edge today is terminal;
  if the user wants retry-on-resume, that becomes a clear policy
  ("optionally re-PENDING all `FAILED` edges before resuming") rather than
  an ad-hoc decision baked into expansion logic.
- **The graph viewer can render in-flight state.** A live-updated
  `crawl-graph.html` could show `PENDING` edges in a distinct style,
  giving the user a real-time picture of what's left.

### Cost / migration considerations

- Edges become mutable. `CrawlManifestStore.toJson(...)` already rewrites
  the entire manifest on every save, so this is no new write cost.
- The edge sequence numbers (`edge_NNN`) become assigned at *planning*
  time rather than resolution time. Edge IDs remain stable through status
  transitions; only the `status` and `message` fields mutate. This is a
  clean property to express in the schema.
- `nextEdgeSequence` rehydration is trivially `max(existingEdges.edgeId) +
  1` — same as today.
- Existing manifests (from runs predating this change) have no
  `expansionStatus` field. Treat absence as `COMPLETE` for all existing
  screens (the `Development Philosophy` in `CLAUDE.md` waives backward
  compatibility, so a one-shot migration or a clean break is acceptable).

### Summary of recommendation

Adopt the hybrid:

1. **Per-screen** `expansionStatus: NOT_STARTED | IN_PROGRESS | COMPLETE`
   on `CrawlScreenRecord`. Transitioned at the start and end of
   `expandScreen(...)`. Drives the screen-level frontier on resume.
2. **Per-edge** lifecycle by extending `CrawlEdgeStatus` with `PENDING`
   and `IN_PROGRESS`. Edges are added when `planTraversal` identifies
   them; their status mutates as work progresses. Drives the per-element
   work list inside an `IN_PROGRESS` screen.

Together these two changes make the per-screen XML — anchored by its
`<crawl>` block and the `<edge>` children on its `<element>`s — a
self-contained, unambiguous record of "what was visited, what was tried, and
what is left" — with no need for an external sidecar, no per-element
identity matching across runs, and a single straightforward algorithm for
rebuilding the frontier on resume. Under the chosen design (see "Chosen
Design" below) this hybrid model is implemented in XML rather than in the
JSON manifest, but the underlying state model is identical.

## Chosen Design

This section specifies the concrete XML schema and the rules that govern it.
The analysis above (Detailed Findings + the two Follow-up sections) is the
"why"; this section is the "what."

### Storage layout

Replace the timestamped session subdirectory with a single per-app directory:
`html/<package>/crawl/`. There is at most one saved crawl per app. The
`<package>/crawl/` directory contains the per-screen
`${screenId}_${slug}.html`, `${screenId}_${slug}.xml`, and optional
`${screenId}_${slug}_merged_accessibility.xml` files. The current
`crawl-index.json`, `crawl-graph.json`, and `crawl-graph.html` are demoted to
derived end-of-run artifacts — rebuilt from the XMLs, never read on resume.

### The `<crawl>` block

A new block is added at the head of each `<screen>`, before
`<merged-elements>`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<screen name="Notifications" package="com.example.app" scroll-steps="3">

  <crawl schema="v1" screen-id="screen_00012" depth="2"
         expansion-status="in_progress" is-root="false">

    <screen-identity package="com.example.app"
                     title="Notifications"
                     hint-1="System notifications"
                     hint-2="Conversations" />

    <parent screen-id="screen_00007"
            trigger-label="Notifications"
            trigger-resource-id="row_notifications" />

    <route>
      <step index="0"
            label="Menu" resource-id="nav_drawer" class="android.view.View"
            bounds="0,0,144,144" child-index-path="0.2.1" first-seen-step="0"
            checkable="false" checked="false" editable="false"
            expected-package="com.example.app"
            expected-screen-name="Drawer">
        <expected-destination-identity package="com.example.app"
                                       title="Drawer"
                                       hint-1="Profile" />
        <expected-replay root-class="android.widget.FrameLayout">
          <element label="Profile" resource-id="profile_row"
                   class="android.view.View" list-item="false"
                   checkable="false" checked="false" editable="false" />
          <element label="Settings" …/>
        </expected-replay>
      </step>
      <step index="1" …/>
    </route>
  </crawl>

  <merged-elements>
    <element label="System notifications" resource-id="row_system_notif"
             class="android.view.View" bounds="0,200,1080,360"
             list-item="false" child-index-path="2.1"
             checkable="false" checked="false" editable="false"
             first-seen-step="0">
      <edge id="edge_089" status="captured"
            child-screen-id="screen_00021"
            child-screen-name="System notifications" />
    </element>
    <element label="Account" …>
      <edge id="edge_090" status="pending" />
    </element>
    <element label="Back" …>
      <edge id="edge_091" status="skipped_blacklist"
            message="System back affordance" />
    </element>
  </merged-elements>

  <scroll-steps>…</scroll-steps>
</screen>
```

Per-screen `<crawl>` attributes:

- `schema="v1"` — schema version, mandatory.
- `screen-id` — canonical `screen_NNNNN`. Allocated `max+1` on resume.
- `depth` — distance from root in the spanning tree.
- `expansion-status` — `not_started | in_progress | complete`. See the
  hybrid lifecycle in "Follow-up: Screens, Edges, and Frontier
  Reconstruction".
- `is-root` — `true` on the root screen, `false` on others; used to locate
  the run-level state.

Run-level attributes (root only, `is-root="true"`): `session-id`,
`started-at`, `finished-at`, `status`
(`in_progress | completed | partial_abort | failed`), `max-depth-reached`.
No separate header file is written. On load, the reader finds the root by
`is-root="true"` (or `depth="0"`) and seeds run-level state from it.

Child elements of `<crawl>`:

- `<screen-identity>` — decomposed dedup identity, replacing the dense
  `v2:pkg:…:title:…:hint:…` fingerprint string. Attributes: `package`,
  `title`, `hint-1` (optional), `hint-2` (optional). The opaque fingerprint
  is reconstructed at load time by joining
  `v2:pkg:<package>:title:<title>:hint:<hint-1>|<hint-2>` (using
  `"none"` for any absent slot, matching `ScreenNaming.kt:107-138`) to feed
  `tracker.findScreenIdByFingerprint(...)` (`CrawlRunTracker.kt:93-95`)
  without changing the comparison logic.
- `<parent>` — `screen-id`, `trigger-label`, `trigger-resource-id`. Absent
  on the root.
- `<route>` — ordered list of `<step>` elements. Each step decomposes its
  `CrawlRouteStep` into attributes (label, resource-id, class, bounds,
  child-index-path, first-seen-step, checkable, checked, editable,
  expected-package, expected-screen-name) plus two child elements:
  - `<expected-destination-identity>` — same shape as `<screen-identity>`
    for the destination screen.
  - `<expected-replay root-class="...">` containing `<element>` children
    with the same shape as `<merged-elements>` minus bounds /
    child-index-path / first-seen-step (the fields
    `buildElementFingerprint(...)` excludes at
    `ScrollScanCoordinator.kt:354-370`). Fully decomposed — no opaque-
    string fallback.

Notably, the *current screen's own* `replayFingerprint` is **not**
serialized in its `<crawl>` block — it is recomputed at load time from the
screen's own `<merged-elements>` plus root class plus the back-affordance
filter for the entry case (`ScrollScanCoordinator.kt:286-308`). Only the
per-route-step `<expected-replay>` (which describes *other* screens at the
time of capture) is stored.

### Edges live on the `<element>` they belong to

Every outbound edge from a screen corresponds 1:1 to a pressable element on
that screen — `expandScreen(...)` produces one edge per element returned by
`TraversalPlanner.planTraversal(...)`, plus one `skipped_blacklist` edge per
element it filtered out. The edge is the click outcome for that element, so
it lives as a child of the element's `<element>` tag, not in a separate
edges block.

`<edge>` attributes by status:

| status | Other attributes |
|---|---|
| `pending` | (none) — element identified but not yet clicked. |
| `in_progress` | (none) — click attempted, outcome not yet written. Treat as `pending` on resume. |
| `captured` | `child-screen-id`, `child-screen-name` |
| `linked_existing` | `child-screen-id`, `child-screen-name`, `message` |
| `skipped_blacklist` | `message` |
| `skipped_no_navigation` | `message` |
| `skipped_external_package` | `message`, optionally `external-package` |
| `failed` | `message` (rewritten to `pending` on next load) |

Edge identity for matching on rescan is the element's existing tuple
(label + resource-id + class + bounds + child-index-path + list-item +
checkable + checked + editable + first-seen-step). No new identity field is
needed.

Edges are mutable in-place — `pending → in_progress → terminal`. The
rewriter rewrites the affected screen's XML on every transition. The
`<scroll-steps>` section is large but stable once captured; an optimization
(not required) is to keep the tail in memory and rewrite only the head.

Edge IDs (`edge_NNN`) are sequence numbers allocated by
`max(existing edge sequence) + 1` on resume. Stable across status
transitions.

**Consequence on rescan.** An element appearing on rescan that wasn't there
before naturally starts as `pending` (no prior `<edge>` child). An element
gone from rescan that had an edge is an orphan — surface as a known-shape
diagnostic rather than try to migrate it.

### Child screen name freezes on the parent

`analyzeScreenName(...)` (`ScreenNaming.kt:140-245`) is content-driven; the
same screen can be named "Notifications" on one run and "Notifications
(3 new)" on the next. To prevent this drift:

1. The parent's `<edge child-screen-name="...">` is the authority for the
   child's name on subsequent runs. When the coordinator is about to
   capture a child via a known parent edge that already carries
   `child-screen-name`, it passes that name through as `preferredName` and
   skips `analyzeScreenName(...)`.
2. The child's own `<screen name="...">` is the canonical form at first
   capture. On `CAPTURED`, the name is written back to the parent's edge.
3. The root has no parent. Its name is read from its own
   `<screen name="...">` and frozen there forever.
4. The `screenFingerprint` (`buildScreenIdentity(...)` at
   `ScreenNaming.kt:107-138`) mixes the title in. To keep fingerprints
   stable across runs, the *frozen* parent-recorded name must be used when
   re-computing the fingerprint on resume too — otherwise dedup flips.
   This is why parent-frozen-name is the right invariant: it stabilizes
   both the filename slug and the dedup key in one move.

Implementation: `ScreenNaming.analyzeScreenName(...)` gains an optional
`preferredName: String? = null`. When set, returns it verbatim. The
coordinator passes the parent's recorded name through
`prepareScreenForExpansion(...)` (`DeepCrawlCoordinator.kt:737-788`) and
the child capture path.

### Failed edges become pending on every load

On load, every `<edge status="failed">` is rewritten to
`<edge status="pending">` and the `message` attribute is dropped. The next
run re-attempts the click. Rationale: a failed attempt is more often a
transient state issue (UI not settled, replay drift) than a fundamental
problem, so the default policy is to retry. Edge ID is preserved across
the transition.

Consequence: an element that *always* fails will retry on every run. See
"Still-open follow-ups" in Resolved Decisions for the `retry-count`
mitigation if it becomes an issue.

### External-package approvals

The pause/approve flow (`DeepCrawlCoordinator.kt:354-401`) fires when a
click crosses into a package not in `allowedPackageNames`. Today the set is
reset every run (`DeepCrawlCoordinator.kt:61-62`) — approvals are not
durable.

Resolution: stamp `approval="explicit"` on the edge whose resolution first
triggered the pause and got "Continue" — the same code path that calls
`allowedPackageNames += childPackageName` at
`DeepCrawlCoordinator.kt:379`. One such edge per (approved-package, crawl).
Subsequent edges that cross into an already-approved package get no marker;
their permission is inherited from the originating edge through the
loader's union.

Denials remain `<edge status="skipped_external_package">`. No `approval`
attribute is needed on denials (the status carries the meaning).

Loader rule:

```
allowedPackages = {}
for screen in all loaded screens:
  for edge in screen.edges where edge.approval == "explicit":
    childScreen = lookup(edge.child-screen-id)
    allowedPackages += childScreen.packageName
seed coordinator.allowedPackageNames = allowedPackages
```

Revocation: rewrite `approval="explicit"` to `approval="revoked"`. The
loader excludes `revoked`. Captured descendant screens stay on disk; only
future encounters re-pause.

Consequences:

- Re-expanding a screen wipes its `<edge>` children. If that screen owned
  the originating approval for a package, the approval drops out of the
  loaded set on the next resume. The next boundary into that package
  re-prompts. Correct conservative default.
- `linked_existing` edges crossing a boundary are silent witnesses — they
  still encountered the gate at `DeepCrawlCoordinator.kt:349`, but their
  approval source is the earlier `captured` edge.
- Legacy XMLs without `<crawl>` blocks contribute zero approvals (BC
  waiver).

### Atomicity

Per-screen XML rewrite is more atomic than the prior single-manifest model:
a crash mid-write can leave at most one screen's XML partially written,
never desynchronized across screens. Each screen owns its own state.

### Writer / reader integration points

- `AccessibilityXmlSerializer.serialize(snapshot)`
  (`AccessibilityXmlSerializer.kt:4-19`) gains an optional
  `crawlState: ScreenCrawlState?` parameter. When present, emits the
  `<crawl>` block between `<screen …>` and `<merged-elements>`.
- `appendMergedElements(...)` (`AccessibilityXmlSerializer.kt:89-124`)
  gains an optional `edgesByElement: Map<ElementKey, EdgeRecord>` and,
  when present, emits a `<edge …/>` child inside each `<element>` instead
  of self-closing the `<element>` tag.
- `CaptureFileStore.saveScreen(...)` (`CaptureFileStore.kt:47-69`) is
  rewritten to call the new serializer with the per-screen `CrawlState`.
- Manifest-save call sites inside `expandScreen(...)`
  (`DeepCrawlCoordinator.kt:284,345,361,469,629,692`) become per-screen
  XML rewrites of the *parent* screen on each edge transition, plus the
  child screen on `CAPTURED`.
- A new `ScreenXmlReader` parses the `<crawl>` blocks from every `*.xml`
  (skipping `*_merged_accessibility.xml` siblings) into `CrawlScreenRecord`
  + `CrawlEdgeRecord` lists, and feeds them into a new `CrawlRunTracker`
  constructor that takes pre-populated state. Supports a "head only" mode
  that stops once `</crawl>` is reached, for the resume picker.
- `crawl-graph.json` / `crawl-graph.html` (`CaptureFileStore.saveGraph`,
  `CrawlGraphBuilder`) become pure derivations of the loaded XMLs. Build
  at end of run only.

## Resume UX

A new picker on `MainActivity.kt`, below "Start Deep Crawl", surfaces the
saved crawl. The reader uses the "head only" mode to parse each XML's
`<crawl>` block and stop before `<scroll-steps>` — the heavy section is
irrelevant to selection.

The picker shows a scrollable tree of screens, indented by `<parent
screen-id>` chain. Each row: screen name, muted `screen_NNNNN`, status pill
(`✓ complete` / `▶ in progress` / `· not started`), pending-edge count
badge (number of `<edge status="pending">` children), per-row actions.

### Three resume modes

| Mode | Behavior |
|---|---|
| **Continue auto** | Implicit frontier: `{not_started screens} ∪ {the single in_progress screen, if any}`. Default action. Disabled with a "nothing pending" hint when no work remains. |
| **Resume from X** | Frontier = `{X}` ∪ any `not_started` descendants reachable through existing `captured`/`linked_existing` edges. Existing `pending`/`in_progress` edges on X drive the per-element work. `complete` screens elsewhere stay untouched. |
| **Re-expand X** | Reset X's `expansion-status` to `not_started`, drop all of X's `<edge>` children, then resume from X. Useful when the UI on screen X has changed and a fresh `planTraversal` is wanted. Descendants stay in place; if elements change, prior descendants may become orphans. |

### Allowed external packages

The picker surfaces an "Allowed external packages" subsection — derived from
the loader rule above — with provenance ("approved from `Settings` →
`Share`") and a per-package "Revoke" action that flips the originating
edge's `approval="explicit"` to `approval="revoked"`.

### Failure cases the UI should surface

- **Route step validation fails on the way to X.**
  `prepareScreenForExpansion(...)` (`DeepCrawlCoordinator.kt:737-788`)
  returns `Failure` with the failing step index. Bubble as "Couldn't reach
  X — the route has drifted" rather than failing silently. Existing
  `replay_route_step_validation` log lines already carry the diagnostic.
- **External-package step on the route.** Handled automatically by the
  witnessed-approval union; if revoked, re-prompts.
- **X is `complete` with no pending children.** Grey out "Resume from X",
  offer only "Re-expand X".

### Wiring points

- `app/src/main/java/com/example/apptohtml/MainActivity.kt` — Compose
  surface gains the picker.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:35-43`
  — `crawl(...)` gains `resumeFromScreenId: String? = null` and
  `reExpand: Boolean = false` parameters that seed the frontier
  accordingly instead of always starting at the root.
- `app/src/main/java/com/example/apptohtml/crawler/CrawlerSession.kt` —
  phase machine unchanged
  (`IDLE → LAUNCHING → WAITING → SCANNING → TRAVERSING → CAPTURED/ABORTED/FAILED`);
  only the precondition for `TRAVERSING` changes — the tracker is hydrated
  from XML before traversal begins.
- A new `SavedCrawlRepository` wraps the directory scan and exposes a
  `Flow<List<SavedScreenSummary>>` so the picker refreshes when a crawl
  finishes in the background.
