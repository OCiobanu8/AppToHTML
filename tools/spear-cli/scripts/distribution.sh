#!/usr/bin/env bash
# distribution.sh — round 14: probes the CLI's distribution surface.
#   QQQ: npm pack produces a tarball that installs and works
#   RRR: locale (LC_ALL=C, LANG variations) doesn't break sorting/output
#   SSS: running spear from a subdirectory of the repo
#   TTT: $HOME unset doesn't crash config commands
#   UUU: subcommand --help is consistent across all commands

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEAR="$REPO_ROOT/dist/cli.js"
TMP="$(mktemp -d)"
PASS=0
FAIL=0
FINDINGS=()

ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
ko() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); FINDINGS+=("$1"); }
section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
run() { node "$SPEAR" "$@"; }

# ---------------------------------------------------------------------------
section "QQQ: npm pack tarball"
# ---------------------------------------------------------------------------
PACK_DIR="$TMP/pack"
mkdir -p "$PACK_DIR"
(cd "$REPO_ROOT" && npm pack --pack-destination "$PACK_DIR" >/dev/null 2>&1)
TARBALL=$(ls "$PACK_DIR"/*.tgz 2>/dev/null | head -1)
if [ -n "$TARBALL" ] && [ -f "$TARBALL" ]; then
  SIZE=$(wc -c < "$TARBALL")
  ok "QQQ: npm pack produced $(basename $TARBALL) ($SIZE bytes)"
else
  ko "QQQ: npm pack didn't produce a tarball"
fi

# Verify tarball contents — should have dist/, templates/, README, LICENSE, package.json, but NOT node_modules or .spear
TAR_LIST=$(tar tzf "$TARBALL" 2>/dev/null)
echo "$TAR_LIST" | grep -q "package/dist/cli.js" && ok "QQQ: tarball contains dist/cli.js" || ko "QQQ: dist/cli.js missing from tarball"
echo "$TAR_LIST" | grep -q "package/templates/" && ok "QQQ: tarball contains templates/" || ko "QQQ: templates missing from tarball"
echo "$TAR_LIST" | grep -q "package/README.md" && ok "QQQ: tarball contains README.md" || ko "QQQ: README missing from tarball"
echo "$TAR_LIST" | grep -q "package/LICENSE" && ok "QQQ: tarball contains LICENSE" || ko "QQQ: LICENSE missing from tarball"
! echo "$TAR_LIST" | grep -q "node_modules/" && ok "QQQ: tarball excludes node_modules/" || ko "QQQ: node_modules leaked into tarball"
! echo "$TAR_LIST" | grep -q "package/.spear/" && ok "QQQ: tarball excludes .spear/" || ko "QQQ: .spear/ leaked into tarball"

# Install the tarball into a sandbox and try running it
INST_DIR="$TMP/inst"
mkdir -p "$INST_DIR"
(cd "$INST_DIR" && npm init -y >/dev/null 2>&1 && npm install "$TARBALL" >/dev/null 2>&1)
INSTALLED_BIN="$INST_DIR/node_modules/.bin/spear"
if [ -e "$INSTALLED_BIN" ]; then
  VER=$("$INSTALLED_BIN" --version 2>&1)
  ok "QQQ: tarball installed; spear --version = $VER"
else
  ko "QQQ: tarball installed but bin not at $INSTALLED_BIN"
fi

# ---------------------------------------------------------------------------
section "RRR: locale variations"
# ---------------------------------------------------------------------------
R="$TMP/r"
mkdir -p "$R"
# init three slugs in different lexical orders
for s in zulu alpha mike; do
  (cd "$R" && run init blog "$s" >/dev/null 2>&1)
done

for locale in "C" "en_US.UTF-8" "C.UTF-8"; do
  OUT=$(cd "$R" && LC_ALL=$locale run list --json 2>&1 || true)
  if echo "$OUT" | jq -e '.projects | length == 3' > /dev/null 2>&1; then
    SLUGS=$(echo "$OUT" | jq -r '.projects[].slug' | tr '\n' ',' | sed 's/,$//')
    if [ "$SLUGS" = "alpha,mike,zulu" ]; then
      ok "RRR: LC_ALL=$locale → list returns slugs in stable sort order ($SLUGS)"
    else
      ko "RRR: LC_ALL=$locale produces unstable order: $SLUGS"
    fi
  else
    ko "RRR: LC_ALL=$locale broke list output"
  fi
done

# ---------------------------------------------------------------------------
section "SSS: spear command from a subdirectory of the project root"
# ---------------------------------------------------------------------------
S="$TMP/s"; mkdir -p "$S/sub/deep"
(cd "$S" && run init blog post >/dev/null 2>&1)
# spear should NOT find the project from a subdir (cwd-rooted by design)
OUT=$(cd "$S/sub/deep" && run status 2>&1 || true)
if echo "$OUT" | grep -qE "No SPEAR project|No SPEAR projects"; then
  ok "SSS: spear from subdir doesn't auto-walk-up (consistent with cwd contract)"
else
  # If it DID walk up, that'd be a feature, but it should be intentional
  ko "SSS: spear from subdir found project (unexpected walk-up?): $(echo "$OUT" | head -1)"
fi

# ---------------------------------------------------------------------------
section "TTT: HOME unset"
# ---------------------------------------------------------------------------
T="$TMP/t"; mkdir -p "$T"
# config commands depend on $HOME for ~/.spear/config.json
OUT=$(cd "$T" && env -u HOME node "$SPEAR" config list 2>&1 || true)
if echo "$OUT" | grep -qE 'HOME|undefined|home'; then
  ok "TTT: config errors clearly when HOME unset (no silent crash)"
else
  # Maybe Node falls back to '.' or something — verify no crash at least
  if echo "$OUT" | jq . >/dev/null 2>&1 || echo "$OUT" | grep -q "no values"; then
    ok "TTT: config handles HOME-unset gracefully"
  else
    ko "TTT: config behavior with HOME unset unclear: $(echo "$OUT" | head -1)"
  fi
fi

# ---------------------------------------------------------------------------
section "UUU: --help format consistency across commands"
# ---------------------------------------------------------------------------
INCONSISTENT=0
for cmd in init scope plan execute assess resolve loop status list runner image; do
  OUT=$(run $cmd --help 2>&1 || true)
  # Every help output should have: Usage: line, Options: section
  if ! echo "$OUT" | grep -q "^Usage:"; then
    ko "UUU: $cmd --help missing 'Usage:' line"
    INCONSISTENT=$((INCONSISTENT + 1))
  fi
done
[ "$INCONSISTENT" = "0" ] && ok "UUU: every command's --help has Usage: line"

# Description matches between cli.ts registration and --help output
# (description is on line 3, after the Usage line + blank)
for cmd in scope plan execute assess; do
  HELP_DESC=$(run $cmd --help 2>&1 | sed -n '3p')
  if [ -n "$HELP_DESC" ]; then
    ok "UUU: $cmd --help has description line"
  else
    ko "UUU: $cmd --help missing description"
  fi
done

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d distribution probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
