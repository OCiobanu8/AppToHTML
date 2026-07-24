#!/usr/bin/env bash
# extreme.sh — round 10 probes: signal handling, concurrent runs, version drift,
# case-insensitive FS slug collision. Failure modes JJ-PP.

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
section "JJ: SIGTERM mid-iteration leaves clean state"
# ---------------------------------------------------------------------------
J1="$TMP/j1"
mkdir -p "$J1"
(cd "$J1" && run init blog post >/dev/null 2>&1)
cat > "$J1/.spear/post/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Probe what happens when a SPEAR command receives SIGTERM mid-execution.
## Audience
Maintainers verifying the atomic-write contract holds under signals.
## Inputs
A stub draft and the ability to send SIGTERM to a spawned node process.
## Constraints
state.json must remain parseable; no .tmp leftover files.
## Done means
After SIGTERM and re-read, the project is in a consistent state.
EOF
mkdir -p "$J1/.spear/post/workspace"
echo "# stub" > "$J1/.spear/post/workspace/draft.md"
(cd "$J1" && run scope >/dev/null 2>&1)

# Spawn assess in bg, sigterm it after a tiny delay
(cd "$J1" && node "$SPEAR" assess --json >/dev/null 2>&1) &
PID=$!
sleep 0.02
kill -TERM $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

# State should still parse
if jq . "$J1/.spear/post/state.json" > /dev/null 2>&1; then
  ok "JJ: state.json parseable after SIGTERM mid-assess"
else
  ko "JJ: state.json corrupted by SIGTERM"
fi
TMPS=$(find "$J1/.spear" -name '*.tmp.*' 2>/dev/null | wc -l | tr -d ' ')
if [ "$TMPS" = "0" ]; then
  ok "JJ: no .tmp leftovers after SIGTERM"
else
  ko "JJ: $TMPS .tmp leftovers after SIGTERM"
fi

# ---------------------------------------------------------------------------
section "KK: two assess in parallel on same slug — race condition"
# ---------------------------------------------------------------------------
K1="$TMP/k1"
mkdir -p "$K1"
(cd "$K1" && run init blog post >/dev/null 2>&1)
cat > "$K1/.spear/post/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Stress test concurrent assess invocations on the same slug to find race conditions.
## Audience
Maintainers running parallel assessments to verify atomic state writes hold under contention.
## Inputs
A draft and two simultaneous assess processes racing for state.json.
## Constraints
After both finish, state.json must be parseable and reflect one of the two writes (last-writer-wins).
## Done means
No corruption, both processes exited cleanly, state.json valid.
EOF
mkdir -p "$K1/.spear/post/workspace"
echo "# stub" > "$K1/.spear/post/workspace/draft.md"
(cd "$K1" && run scope >/dev/null 2>&1)

# Run 3 in parallel
(cd "$K1" && run assess --json >/dev/null 2>&1) &
(cd "$K1" && run assess --json >/dev/null 2>&1) &
(cd "$K1" && run assess --json >/dev/null 2>&1) &
wait

if jq . "$K1/.spear/post/state.json" > /dev/null 2>&1; then
  ok "KK: state.json valid after 3 parallel assesses"
else
  ko "KK: state.json corrupted by concurrent writes"
fi
TMPS=$(find "$K1/.spear" -name '*.tmp.*' 2>/dev/null | wc -l | tr -d ' ')
if [ "$TMPS" = "0" ]; then
  ok "KK: no .tmp leftovers after concurrent writes"
else
  ko "KK: $TMPS .tmp leftovers after concurrent writes"
fi

# ---------------------------------------------------------------------------
section "MM: --version matches package.json"
# ---------------------------------------------------------------------------
PKG_VER=$(jq -r '.version' "$REPO_ROOT/package.json")
CLI_VER=$(run --version 2>&1)
if [ "$PKG_VER" = "$CLI_VER" ]; then
  ok "MM: spear --version ($CLI_VER) matches package.json ($PKG_VER)"
else
  ko "MM: version drift — package.json: $PKG_VER, spear --version: $CLI_VER"
fi

# ---------------------------------------------------------------------------
section "PP: case-insensitive slug collision (macOS APFS, NTFS)"
# ---------------------------------------------------------------------------
P1="$TMP/p1"
mkdir -p "$P1"
(cd "$P1" && run init blog mypost >/dev/null 2>&1)
# Try to init the SAME slug with different case
OUT=$(cd "$P1" && run init blog MyPost 2>&1 || true)
# On a case-insensitive FS this would clobber; on case-sensitive it'd create a sibling
# Either way, the system should handle it without corruption. Let's check what happened:
LOWER_EXISTS=$([ -d "$P1/.spear/mypost" ] && echo "yes" || echo "no")
UPPER_EXISTS=$([ -d "$P1/.spear/MyPost" ] && echo "yes" || echo "no")
echo "  mypost dir: $LOWER_EXISTS, MyPost dir: $UPPER_EXISTS"

if [ "$LOWER_EXISTS" = "yes" ] && jq . "$P1/.spear/mypost/state.json" > /dev/null 2>&1; then
  ok "PP: original slug 'mypost' state.json still valid after MyPost init"
else
  ko "PP: state corrupted by case-collision init"
fi

# Run list — should show the projects without crashing
LIST_OUT=$(cd "$P1" && run list 2>&1 || true)
if echo "$LIST_OUT" | grep -q "mypost"; then
  ok "PP: spear list works after case-collision init"
else
  ko "PP: spear list broken after case-collision: $(echo "$LIST_OUT" | head -1)"
fi

# ---------------------------------------------------------------------------
section "QQ: invalid type rejected"
# ---------------------------------------------------------------------------
Q1="$TMP/q1"
mkdir -p "$Q1"
OUT=$(cd "$Q1" && run init invalid_type 2>&1 || true)
if echo "$OUT" | grep -q "Unknown type"; then
  ok "QQ: invalid type rejected with clear error"
else
  ko "QQ: invalid type not rejected"
fi

# ---------------------------------------------------------------------------
section "RR: --help on every command exits 0 and produces output"
# ---------------------------------------------------------------------------
HELP_BAD=0
for cmd in init scope plan execute assess resolve loop status list runner image config; do
  OUT=$(run $cmd --help 2>&1 || true)
  if [ -z "$OUT" ] || ! echo "$OUT" | grep -q "Usage:"; then
    ko "RR: $cmd --help missing 'Usage:' line"
    HELP_BAD=$((HELP_BAD+1))
  fi
done
[ "$HELP_BAD" = "0" ] && ok "RR: every command's --help has a Usage: line"

# ---------------------------------------------------------------------------
section "SS: state.json reflects writes from ONE of multiple parallel writers"
# ---------------------------------------------------------------------------
# After parallel assesses (from KK), state.round should be a sane value (not negative, not duplicated)
ROUND=$(jq -r '.round' "$K1/.spear/post/state.json")
if [ "$ROUND" -ge 1 ] && [ "$ROUND" -le 5 ]; then
  ok "SS: state.round is sane after concurrent writes (got $ROUND)"
else
  ko "SS: state.round = $ROUND is out of expected range after 3 parallel writes"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d extreme probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
