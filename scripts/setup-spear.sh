#!/usr/bin/env bash
# One-time, idempotent build of the vendored spear-cli for THIS checkout. Safe to re-run.
set -euo pipefail
EXPECTED_VERSION="0.2.0"                             # match tools/spear-cli/VENDORED.md
ROOT="$(git rev-parse --show-toplevel)"             # current checkout (main repo OR worktree)
SPEAR_DIR="$ROOT/tools/spear-cli"
command -v node >/dev/null || { echo "✗ need Node >= 20 on PATH" >&2; exit 1; }
cd "$SPEAR_DIR"
[ -f package-lock.json ] && npm ci || npm install   # reproducible from the lockfile
npm run build
ACTUAL="$(node dist/cli.js --version 2>/dev/null | tr -d '[:space:]')"
[ "$ACTUAL" = "$EXPECTED_VERSION" ] || { echo "✗ built version '$ACTUAL' != '$EXPECTED_VERSION'" >&2; exit 1; }
echo "✓ spear ready (v$ACTUAL) at tools/spear-cli/dist/cli.js"
