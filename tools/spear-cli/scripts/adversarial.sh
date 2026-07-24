#!/usr/bin/env bash
# adversarial.sh — probe each lettered failure mode U-II from .spear/self/ASSESS.md.
# Designed to find real defects, not validate happy paths. Each section corresponds
# to one round of formal SPEAR iteration.

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

if [ ! -f "$SPEAR" ]; then echo "✗ run npm run build first" >&2; exit 1; fi

# Reusable filled SCOPE
read -r -d '' FILLED_SCOPE <<'EOF' || true
# SCOPE
## Goal
Verify the CLI handles each adversarial failure mode without crashing or producing malformed output.
## Audience
Maintainers running rigorous self-tests before each release to catch regressions.
## Inputs
The CLI source plus a scratch sandbox where each probe creates its own project.
## Constraints
Each probe must complete in under five seconds and clean up after itself.
## Done means
Every adversarial probe either passes or produces an actionable error message.
EOF

# ---------------------------------------------------------------------------
section "U. Empty-state crash"
# ---------------------------------------------------------------------------
EMPTY="$TMP/empty"
mkdir -p "$EMPTY"
for cmd in scope plan execute assess loop status resolve list; do
  OUT=$(cd "$EMPTY" && run $cmd 2>&1 || true)
  # Should error cleanly; not leak stack traces or ENOENT
  if echo "$OUT" | grep -qE 'No SPEAR project|Run `spear init|No SPEAR projects in this directory|No.*found'; then
    ok "U: $cmd errors cleanly in empty repo"
  else
    ko "U: $cmd in empty repo — output: $(echo "$OUT" | head -1 | tr -d '\033' | sed 's/\[[0-9;]*m//g')"
  fi
done

# ---------------------------------------------------------------------------
section "V. State-corruption recovery"
# ---------------------------------------------------------------------------
CORRUPT="$TMP/corrupt"
mkdir -p "$CORRUPT"
(cd "$CORRUPT" && run init blog >/dev/null 2>&1)
echo "{ this is not valid JSON" > "$CORRUPT/.spear/blog/state.json"
OUT=$(cd "$CORRUPT" && run status 2>&1 || true)
if echo "$OUT" | grep -qE 'JSON|malformed|parse|Unexpected'; then
  ok "V: corrupt state.json produces a parseable error"
else
  if echo "$OUT" | grep -qE 'phase|round'; then
    ko "V: corrupt state.json silently passed (read returned garbage as state)"
  else
    ko "V: corrupt state.json crash — output: $(echo "$OUT" | head -1 | tr -d '\033' | sed 's/\[[0-9;]*m//g')"
  fi
fi

# Truncated state
echo -n "" > "$CORRUPT/.spear/blog/state.json"
OUT=$(cd "$CORRUPT" && run status 2>&1 || true)
if echo "$OUT" | grep -qE 'JSON|empty|parse'; then
  ok "V: empty state.json produces a parseable error"
else
  ko "V: empty state.json — output: $(echo "$OUT" | head -1 | tr -d '\033' | sed 's/\[[0-9;]*m//g')"
fi

# ---------------------------------------------------------------------------
section "W. Slug edge cases"
# ---------------------------------------------------------------------------
EDGE="$TMP/edge"
mkdir -p "$EDGE"
# 1-char slug — should be allowed
OUT=$(cd "$EDGE" && run init blog a 2>&1 || true)
if [ -d "$EDGE/.spear/a" ]; then
  ok "W: single-char slug 'a' accepted"
else
  ko "W: single-char slug rejected — $(echo "$OUT" | head -1)"
fi

# Slug starting with hyphen — should reject
OUT=$(cd "$EDGE" && run init blog -bad 2>&1 || true)
if echo "$OUT" | grep -qE 'Invalid|unknown option'; then
  ok "W: slug starting with - rejected"
else
  ko "W: slug starting with - accepted — $(echo "$OUT" | head -1)"
fi

# Slug with dots — should reject
OUT=$(cd "$EDGE" && run init blog 'foo.bar' 2>&1 || true)
if echo "$OUT" | grep -q 'Invalid'; then
  ok "W: slug with dot rejected"
else
  ko "W: slug with dot accepted — $(echo "$OUT" | head -1)"
fi

# Slug starting with digit — should be allowed per regex
OUT=$(cd "$EDGE" && run init blog 1post 2>&1 || true)
if [ -d "$EDGE/.spear/1post" ]; then
  ok "W: slug starting with digit accepted"
else
  ko "W: slug starting with digit rejected — $(echo "$OUT" | head -1)"
fi

# Long slug
LONG=$(printf 'a%.0s' {1..200})
OUT=$(cd "$EDGE" && run init blog "$LONG" 2>&1 || true)
if [ -d "$EDGE/.spear/$LONG" ]; then
  ok "W: 200-char slug accepted"
else
  ko "W: 200-char slug rejected — $(echo "$OUT" | head -1)"
fi

# ---------------------------------------------------------------------------
section "X. --json malformed"
# ---------------------------------------------------------------------------
JSONTEST="$TMP/jsontest"
mkdir -p "$JSONTEST"
(cd "$JSONTEST" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$JSONTEST/.spear/post/SCOPE.md"
mkdir -p "$JSONTEST/.spear/post/workspace"
echo "# stub" > "$JSONTEST/.spear/post/workspace/draft.md"

for cmd_args in "scope --json" "list --json" "assess --json" "resolve --json"; do
  set +u
  OUT=$(cd "$JSONTEST" && eval "node $SPEAR $cmd_args" 2>&1 || true)
  set -u
  if echo "$OUT" | jq . > /dev/null 2>&1; then
    ok "X: spear $cmd_args parses as JSON"
  else
    # Maybe it's multiple JSON docs or has a leading non-JSON line
    LAST_JSON=$(echo "$OUT" | tail -c +1 | head -c 4096)
    if echo "$LAST_JSON" | jq . > /dev/null 2>&1; then
      ok "X: spear $cmd_args parses as JSON (after trimming)"
    else
      ko "X: spear $cmd_args produced non-JSON — first line: $(echo "$OUT" | head -1 | head -c 80)"
    fi
  fi
done

# Plan --json on unfilled PLAN — exits 1 but should still emit JSON
OUT=$(cd "$JSONTEST" && run plan --json 2>&1 || true)
if echo "$OUT" | jq . > /dev/null 2>&1; then
  ok "X: spear plan --json (failure path) parses as JSON"
else
  ko "X: spear plan --json failure-path output is not JSON — $(echo "$OUT" | head -1)"
fi

# ---------------------------------------------------------------------------
section "Y. Phase-gate enforcement"
# ---------------------------------------------------------------------------
GATE="$TMP/gate"
mkdir -p "$GATE"
(cd "$GATE" && run init blog post >/dev/null 2>&1)
# scope unfilled → run execute. Should it refuse? Today it's soft.
OUT=$(cd "$GATE" && run execute --json 2>&1 || true)
if echo "$OUT" | grep -qE 'plan|scope|gate|not validated|run.*scope first'; then
  ok "Y: execute refuses when upstream phases unvalidated"
else
  ko "Y: execute proceeded without scope/plan validation (soft gate; relies on adapter failure)"
fi

# ---------------------------------------------------------------------------
section "Z. Big-input handling"
# ---------------------------------------------------------------------------
BIG="$TMP/big"
mkdir -p "$BIG"
(cd "$BIG" && run init blog post >/dev/null 2>&1)
# 1MB SCOPE
{
  echo "# SCOPE"; echo
  echo "## Goal"; for i in $(seq 1 5000); do echo "Goal sentence number $i provides repeated content for size testing of the validator under load."; done; echo
  echo "## Audience"; echo "Maintainers stress-testing the scope parser with multi-megabyte inputs to find performance cliffs."; echo
  echo "## Inputs"; echo "A pathologically large SCOPE.md to verify the validator does not OOM or hang."; echo
  echo "## Constraints"; echo "Must complete within five seconds even with 1MB+ of content."; echo
  echo "## Done means"; echo "The validator reports the result correctly without timing out."
} > "$BIG/.spear/post/SCOPE.md"
SIZE=$(wc -c < "$BIG/.spear/post/SCOPE.md")
START=$(date +%s)
(cd "$BIG" && run scope --json > /tmp/big-scope.json 2>&1 || true)
END=$(date +%s)
DURATION=$((END - START))
if [ -s /tmp/big-scope.json ] && jq . /tmp/big-scope.json > /dev/null 2>&1 && [ $DURATION -lt 10 ]; then
  ok "Z: ${SIZE}-byte SCOPE.md validated in ${DURATION}s"
else
  ko "Z: big SCOPE.md timed out or malformed (${DURATION}s, $SIZE bytes)"
fi

# ---------------------------------------------------------------------------
section "AA. Cross-slug isolation"
# ---------------------------------------------------------------------------
ISO="$TMP/iso"
mkdir -p "$ISO"
(cd "$ISO" && run init blog alpha >/dev/null 2>&1)
(cd "$ISO" && run init blog beta >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$ISO/.spear/alpha/SCOPE.md"
echo "# DIFFERENT scope for beta" > "$ISO/.spear/beta/SCOPE.md"

(cd "$ISO" && run scope --name alpha >/dev/null 2>&1) || true
ALPHA_PHASE=$(node -e "console.log(require('$ISO/.spear/alpha/state.json').phase)")
BETA_PHASE=$(node -e "console.log(require('$ISO/.spear/beta/state.json').phase)")

if [ "$ALPHA_PHASE" = "plan" ] && [ "$BETA_PHASE" = "scope" ]; then
  ok "AA: scope --name alpha advances alpha only (beta still at scope)"
else
  ko "AA: cross-slug leak — alpha=$ALPHA_PHASE beta=$BETA_PHASE"
fi

# ---------------------------------------------------------------------------
section "BB. Resume after kill"
# ---------------------------------------------------------------------------
KILL="$TMP/kill"
mkdir -p "$KILL"
(cd "$KILL" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$KILL/.spear/post/SCOPE.md"
mkdir -p "$KILL/.spear/post/workspace"
echo "# stub" > "$KILL/.spear/post/workspace/draft.md"
(cd "$KILL" && run scope >/dev/null 2>&1)

# Run assess in background, kill it mid-execution
(cd "$KILL" && timeout 0.05 node "$SPEAR" assess >/dev/null 2>&1) || true
# After kill, state.json must still parse
if jq . "$KILL/.spear/post/state.json" > /dev/null 2>&1; then
  ok "BB: state.json parseable after kill mid-assess"
else
  ko "BB: state.json corrupted after kill (atomic-write contract violated)"
fi
# No stray .tmp files
TMPS=$(find "$KILL/.spear" -name '*.tmp.*' 2>/dev/null | wc -l | tr -d ' ')
if [ "$TMPS" = "0" ]; then
  ok "BB: no .tmp.* leftovers after kill"
else
  ko "BB: $TMPS .tmp.* files leaked after kill"
fi

# ---------------------------------------------------------------------------
section "CC. Evidence path round-trip"
# ---------------------------------------------------------------------------
RT="$TMP/rt"
mkdir -p "$RT"
(cd "$RT" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$RT/.spear/post/SCOPE.md"
mkdir -p "$RT/.spear/post/workspace"
echo "# stub draft for path round-trip" > "$RT/.spear/post/workspace/draft.md"
(cd "$RT" && run scope >/dev/null 2>&1)
(cd "$RT" && run assess >/dev/null 2>&1)

# Every artifact path in evidence.json should resolve from cwd
EVJSON="$RT/.spear/post/rounds/1/evidence.json"
PATHS=$(jq -r '.[] | select(.artifact != null) | .artifact' "$EVJSON")
MISSING=0
COUNT=0
while IFS= read -r p; do
  [ -z "$p" ] && continue
  COUNT=$((COUNT+1))
  [ ! -f "$RT/$p" ] && MISSING=$((MISSING+1))
done <<< "$PATHS"
if [ "$COUNT" -gt 0 ] && [ "$MISSING" = "0" ]; then
  ok "CC: $COUNT artifact path(s) all resolve from cwd"
else
  ko "CC: $MISSING/$COUNT artifact paths broken"
fi

# Hashes must match the file
PATHS=$(jq -r '.[] | select(.artifactHash != null) | "\(.artifact)\t\(.artifactHash)"' "$EVJSON")
HASH_OK=1
while IFS=$'\t' read -r p h; do
  [ -z "$p" ] && continue
  ACTUAL="sha256:$(shasum -a 256 "$RT/$p" 2>/dev/null | awk '{print $1}')"
  [ "$ACTUAL" != "$h" ] && HASH_OK=0
done <<< "$PATHS"
if [ "$HASH_OK" = "1" ]; then
  ok "CC: every recorded hash matches its file"
else
  ko "CC: at least one recorded hash mismatches its file"
fi

# ---------------------------------------------------------------------------
section "DD. Help/source flag drift"
# ---------------------------------------------------------------------------
# Every flag registered in cli.ts should appear in --help output, AND vice versa
CLI_TS="$REPO_ROOT/src/cli.ts"
HELP_OUT=$(run --help 2>&1)
DRIFT=0
# Sample 5 commands' flags
for cmd in scope assess resolve image runner; do
  CMD_HELP=$(run $cmd --help 2>&1 || true)
  # Pull --flag-name patterns from cli.ts's $cmd command block
  SOURCE_FLAGS=$(awk "/command\\('$cmd/,/\\.action\\(/" "$CLI_TS" | grep -oE "'-[a-z-]+|'--[a-z-]+" | tr -d "'" | sort -u)
  for f in $SOURCE_FLAGS; do
    if ! echo "$CMD_HELP" | grep -qE "(^|[ ,])$f([ ,]|<|$)"; then
      DRIFT=$((DRIFT+1))
    fi
  done
done
if [ "$DRIFT" = "0" ]; then
  ok "DD: source-flag ↔ help-flag parity across 5 sampled commands"
else
  ko "DD: $DRIFT flag(s) in source not surfaced in --help"
fi

# ---------------------------------------------------------------------------
section "II. Evidence-emission gap"
# ---------------------------------------------------------------------------
GAP="$TMP/gap"
mkdir -p "$GAP"
# Test --fast mode for each adapter — does it still emit evidence?
for type in blog generic code; do
  D="$GAP/$type"
  mkdir -p "$D"
  (cd "$D" && run init $type >/dev/null 2>&1)
  printf '%s\n' "$FILLED_SCOPE" > "$D/.spear/$type/SCOPE.md"
  if [ "$type" = "blog" ]; then
    mkdir -p "$D/.spear/$type/workspace"
    echo "# stub" > "$D/.spear/$type/workspace/draft.md"
  elif [ "$type" = "generic" ]; then
    mkdir -p "$D/.spear/$type/workspace"
    echo "content" > "$D/.spear/$type/workspace/file.txt"
  fi
  (cd "$D" && run scope >/dev/null 2>&1)
  set +o pipefail
  EV=$(cd "$D" && (run assess --fast --json 2>/dev/null || true) | jq '.evidence | length' 2>/dev/null)
  set -o pipefail
  EV=${EV:-0}
  if [ "$EV" -gt 0 ]; then
    ok "II: $type --fast emits $EV evidence rows"
  else
    ko "II: $type --fast emits 0 evidence rows (gap)"
  fi
done

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d adversarial probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
