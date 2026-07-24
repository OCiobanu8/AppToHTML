# Vendored: spear-cli

This directory is a **vendored, verbatim copy** of the `spear-cli` tool, committed into
AppToHTML so the SPEAR pipeline (`/spear-start`) works from a fresh clone on any device
without a separate manual install.

## Attribution

- **Author:** Ryan Waliany
- **Upstream:** https://github.com/rwaliany/spear-cli
- **Package:** `@waliany/spear`
- **Version:** 0.2.0
- **Vendored at commit:** `c3077d7`
- **License:** Apache-2.0 — see [`LICENSE`](./LICENSE) (retains `Copyright 2026 Ryan Waliany`).

## License compliance

The source is redistributed **unmodified**. Apache-2.0 §4 is satisfied by retaining the
upstream [`LICENSE`](./LICENSE) (which carries the copyright/attribution notice). Upstream
ships **no `NOTICE` file**, so none is required here. The attribution in this file (and any
courtesy credit AppToHTML adds to its own docs) honors upstream's "use freely with
attribution to Ryan Waliany" request.

## Do not hand-edit

Treat `tools/spear-cli/**` as read-only third-party code. To update, re-vendor the desired
upstream commit (copy source, excluding `.git/`, `node_modules/`, `dist/`), bump the
commit/version above, and rebuild via `scripts/setup-spear.sh`.

## Build outputs are gitignored

`tools/spear-cli/dist/` (compiled JS) and `tools/spear-cli/node_modules/` are **not
committed** — they are produced locally by `scripts/setup-spear.sh` (`npm install &&
npm run build`) and ignored via the root `.gitignore`.
