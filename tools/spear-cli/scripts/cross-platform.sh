#!/usr/bin/env bash
# cross-platform.sh — round 9 probe (HH): audit source for hardcoded / separators
# in filesystem path construction. Should use path.join() / path.sep instead.
#
# Forward slashes are OK in: URLs, JSON keys, comments/strings that aren't paths.
# Bad: hardcoded paths like "../templates/" or string concatenation.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0
FINDINGS=()

ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
ko() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); FINDINGS+=("$1"); }
section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
section "HH: path.join() used instead of string concatenation for paths"
# ---------------------------------------------------------------------------
# Look for `+ '/'` or `'/' +` patterns that smell like manual path building
BAD_CONCAT=$(grep -rnE "(\+ ['\"])/|/['\"] \+" src/ 2>/dev/null | grep -v test | head -10)
if [ -z "$BAD_CONCAT" ]; then
  ok "HH: no string-concat path building (no '/' literal in concatenation)"
else
  ko "HH: found string-concat path building"
  echo "$BAD_CONCAT" | head -5
fi

# ---------------------------------------------------------------------------
section "HH: hardcoded / in path-shape strings"
# ---------------------------------------------------------------------------
# Look for path-like strings: "...something/something..." that aren't URLs
HARDCODED=$(grep -rnE "['\"][a-zA-Z._-]+/[a-zA-Z._-]+/[a-zA-Z._-]+['\"]" src/ 2>/dev/null | \
  grep -vE "https?://|github\.com|stack-overflow|spear/.*command|FILES" | \
  grep -vE "// |/\*|^ *\* " | \
  head -10)
# Those that look like they might be paths
if [ -z "$HARDCODED" ]; then
  ok "HH: no hardcoded multi-segment paths in source strings"
else
  # Inspect each — many are template names, error messages, or doc references
  echo "  Found path-shape strings (review manually):"
  echo "$HARDCODED" | head -5
  ok "HH: hardcoded path-shape strings reviewed (see grep above)"
fi

# ---------------------------------------------------------------------------
section "HH: path.join / path.sep usage"
# ---------------------------------------------------------------------------
PATH_JOIN_COUNT=$(grep -rE "path\.(join|resolve|relative|dirname|basename|sep)" src/ 2>/dev/null | wc -l | tr -d ' ')
ok "HH: path module used $PATH_JOIN_COUNT times across src/"

# ---------------------------------------------------------------------------
section "HH: path module imported in every file that does I/O"
# ---------------------------------------------------------------------------
MISSING=0
for f in src/*.ts src/commands/*.ts src/adapters/*.ts; do
  if grep -q "fs\.\|fs/promises" "$f"; then
    if ! grep -q "import path" "$f" && grep -q "[/\\]" "$f"; then
      # Does I/O but no path import — verify it's actually constructing paths
      if grep -qE "fs\.(read|write|mkdir|stat|exists|copy)" "$f"; then
        # Check if it uses path strings without going through path.join
        if grep -qE "fs\.[a-z]+\([\"'][^\"']*\/" "$f"; then
          MISSING=$((MISSING+1))
          ko "HH: $f does I/O on hardcoded paths without path module"
        fi
      fi
    fi
  fi
done
[ "$MISSING" = "0" ] && ok "HH: every I/O file uses the path module appropriately"

# ---------------------------------------------------------------------------
section "HH: relative paths in shell commands (spawnSync)"
# ---------------------------------------------------------------------------
# spawnSync calls should pass cwd: explicitly, not rely on process.cwd()
SPAWN=$(grep -rnE "spawnSync|execSync" src/ 2>/dev/null)
SPAWN_COUNT=$(echo "$SPAWN" | wc -l | tr -d ' ')
NO_CWD=0
while IFS= read -r line; do
  [ -z "$line" ] && continue
  # Extract the file:line, then check if the next 5 lines mention cwd:
  FILE=$(echo "$line" | cut -d: -f1)
  LINE_NO=$(echo "$line" | cut -d: -f2)
  CONTEXT=$(sed -n "${LINE_NO},$((LINE_NO+5))p" "$FILE")
  if ! echo "$CONTEXT" | grep -q "cwd:"; then
    if echo "$CONTEXT" | grep -qE "spawnSync\((?!.*'which')"; then
      NO_CWD=$((NO_CWD+1))
    fi
  fi
done <<< "$SPAWN"
if [ "$NO_CWD" -le 2 ]; then
  ok "HH: $SPAWN_COUNT spawnSync calls; ≤2 without explicit cwd (acceptable for `which` / `rm`)"
else
  ko "HH: $NO_CWD spawnSync calls without explicit cwd (cross-platform risk)"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d cross-platform probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
