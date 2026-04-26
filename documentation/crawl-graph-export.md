# Crawl Graph Export

## Overview

Every deep-crawl session writes a graph export beside the crawl manifest:

- `crawl-index.json`
- `crawl-graph.json`
- `crawl-graph.html`

The manifest remains the authoritative crawl record. The graph artifacts are a
desktop-friendly view of the same crawl state.

## `crawl-graph.json`

The JSON export stores a normalized graph snapshot with:

- session ID
- package name
- generation timestamp
- root screen ID
- max depth reached
- graph nodes
- graph edges

### Nodes

Each node includes:

- screen ID
- screen name
- fingerprint
- package name
- depth
- discovery index
- sibling artifact basenames for HTML, XML, and merged accessibility XML

The file references are stored as basenames instead of absolute paths so the
entire crawl folder can be copied to another machine without breaking the
viewer.

### Edges

Each edge includes:

- edge ID
- source screen ID
- optional destination screen ID
- trigger label
- crawl edge status
- optional message

Current edge statuses include captured, linked-existing, blacklist skips,
no-navigation skips, external-package skips, and failures.

## `crawl-graph.html`

The HTML export is a self-contained offline viewer. It does not fetch remote
assets or depend on a local web server.

Key behavior:

- renders a deterministic SVG graph
- lays out nodes by depth and discovery order
- colors and styles edges by crawl status
- supports pan and wheel zoom
- supports filtering by edge status
- supports neighbor highlighting
- links nodes to sibling per-screen artifacts in the same session folder

Because the graph data is embedded directly in the HTML document, the file can
be opened later on a desktop browser with networking disabled.

## When exports are refreshed

Graph artifacts are updated whenever the manifest is updated. That includes:

- normal crawl progress
- paused checkpoints
- partial aborts
- completed crawls

This keeps the saved session folder inspectable even if the crawl is stopped
before frontier exhaustion.

## Desktop usage

1. Copy the crawl session directory off the device.
2. Keep `crawl-graph.html`, `crawl-graph.json`, `crawl-index.json`, and the
   sibling screen files in the same folder.
3. Open `crawl-graph.html` in a desktop browser.
4. Use the filters to reduce noise from skipped or failed edges.
5. Open linked per-screen HTML files from the graph when you need the original
   captured artifact.

## Practical tradeoff

Inlining the graph JSON keeps the viewer portable and offline-friendly, but it
also makes `crawl-graph.html` larger on very large crawls. That tradeoff is
intentional for the current desktop-only inspection workflow.
