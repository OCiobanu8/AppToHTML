# PRODUCT — Module tier

> **Durability:** changes when the approach shifts. The product's goal, approach, and a few
> examples — the surfaces it ships. Point at deeper specs; do not restate them.

## Goal

Given a user-selected target app, produce a faithful, deduplicated, navigable capture of its UI
— across scrolled viewports and one level of followed interactions — as structured HTML and XML
plus a crawl index.

## Approach

1. **Select** a target app; persist only that choice, not the transient crawl.
2. **Launch & wait** for the target's window to be ready before capturing.
3. **Scroll-scan** each screen, snapshotting the accessibility tree at every scroll stop into
   serializable nodes, and **merge viewports** by deduplicating repeated elements.
4. **Deep-crawl (optional)** one level deep: follow safe, reachable interactive elements, replay
   the path to each child screen, and capture it — filtered by explicit safety guards.
5. **Export** per-session artifacts (HTML, XML, and a crawl index) to per-session directories.

The crawl is a bounded, resumable, safety-gated traversal — not an exhaustive spider. It settles
destinations, honors checkpoints, and never crosses app boundaries.

## Durable principles that shape the product

- **Capture is read-mostly and non-destructive** — observe the app; do not change it.
- **Merge is identity-based**, on stable semantic signals rather than pixel geometry, so the same
  element seen across viewports collapses to one.
- **Crawl state is runtime; the selected target is the only persisted choice.**

## Examples of a "unit of work" here

- Making a merge collapse two viewport captures of the same list that currently double-count.
- Adding a safety guard that stops the crawler before a destructive control.
- Tightening how a child screen's replay path is validated before re-capture.

> Deeper mechanics (merge strategy, scroll flow, tradeoffs) live in the repository's module and
> crawler documentation — referenced, not copied here.
