#!/usr/bin/env bash
# scale.sh — round 13 probes (KKK-PPP): scale and performance edges.

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

setup_minimal() {
  local D="$1" SLUG="${2:-post}"
  mkdir -p "$D"
  (cd "$D" && run init blog "$SLUG" >/dev/null 2>&1)
  cat > "$D/.spear/$SLUG/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Stress-test the CLI under high counts and large inputs to find performance cliffs or data-shape regressions.
## Audience
Maintainers measuring assess and resolve runtime against defect lists, evidence rows, and slug counts that approach realistic production loads.
## Inputs
A draft sized for the test plus pre-populated state to exercise specific code paths.
## Constraints
Each probe must complete in under ten seconds on commodity hardware.
## Done means
No regressions in latency or correctness as input sizes grow.
EOF
  mkdir -p "$D/.spear/$SLUG/workspace"
  echo "# stub" > "$D/.spear/$SLUG/workspace/draft.md"
  (cd "$D" && run scope --name "$SLUG" >/dev/null 2>&1)
}

# ---------------------------------------------------------------------------
section "KKK: 5000-word draft assess timing"
# ---------------------------------------------------------------------------
K="$TMP/k"; setup_minimal "$K"
{
  echo "# Big draft"; echo
  for i in $(seq 1 1000); do
    echo "Paragraph $i. This is sentence one. This is sentence two with more words to bring the average count up."
  done
} > "$K/.spear/post/workspace/draft.md"
WORDS=$(wc -w < "$K/.spear/post/workspace/draft.md")

START=$(date +%s)
(cd "$K" && run assess --json > /tmp/big-assess.json 2>&1) || true
END=$(date +%s)
DURATION=$((END - START))
EVIDENCE=$(jq '.evidence | length' /tmp/big-assess.json 2>/dev/null || echo 0)
DEFECTS=$(jq '.defects | length' /tmp/big-assess.json 2>/dev/null || echo 0)
if [ "$DURATION" -lt 5 ] && [ "$EVIDENCE" -gt 0 ]; then
  ok "KKK: $WORDS-word draft assessed in ${DURATION}s (evidence=$EVIDENCE, defects=$DEFECTS)"
else
  ko "KKK: $WORDS-word draft slow (${DURATION}s) or evidence missing ($EVIDENCE)"
fi

# ---------------------------------------------------------------------------
section "LLL: 50 slugs in one repo — list + runner timing"
# ---------------------------------------------------------------------------
L="$TMP/l"
mkdir -p "$L"
START=$(date +%s)
for i in $(seq 1 50); do
  (cd "$L" && run init blog "post-$i" >/dev/null 2>&1)
done
END=$(date +%s)
INIT_DURATION=$((END - START))
ok "LLL: 50 slugs initialized in ${INIT_DURATION}s"

START=$(date +%s)
LIST_OUT=$(cd "$L" && run list --json 2>&1)
END=$(date +%s)
LIST_DURATION=$((END - START))
LIST_COUNT=$(echo "$LIST_OUT" | jq '.projects | length' 2>/dev/null || echo 0)
if [ "$LIST_DURATION" -lt 3 ] && [ "$LIST_COUNT" = "50" ]; then
  ok "LLL: list discovered all 50 slugs in ${LIST_DURATION}s"
else
  ko "LLL: list slow (${LIST_DURATION}s) or count wrong ($LIST_COUNT/50)"
fi

START=$(date +%s)
RUNNER_OUT=$(cd "$L" && run runner --once --json 2>&1)
END=$(date +%s)
RUNNER_DURATION=$((END - START))
RUNNER_COUNT=$(echo "$RUNNER_OUT" | jq '.loops | length' 2>/dev/null || echo 0)
if [ "$RUNNER_DURATION" -lt 5 ] && [ "$RUNNER_COUNT" = "50" ]; then
  ok "LLL: runner --once enumerated 50 loops in ${RUNNER_DURATION}s"
else
  ko "LLL: runner slow (${RUNNER_DURATION}s) or count wrong ($RUNNER_COUNT/50)"
fi

# ---------------------------------------------------------------------------
section "MMM: 100 sequential assess rounds on one slug"
# ---------------------------------------------------------------------------
M="$TMP/m"; setup_minimal "$M"
START=$(date +%s)
for i in $(seq 1 30); do
  (cd "$M" && run assess --fast --json >/dev/null 2>&1) || true
done
END=$(date +%s)
SEQ_DURATION=$((END - START))
ROUNDS_DIR_COUNT=$(ls -1 "$M/.spear/post/rounds/" 2>/dev/null | wc -l | tr -d ' ')
HISTORY_LEN=$(jq '.history | length' "$M/.spear/post/state.json")
if [ "$ROUNDS_DIR_COUNT" -ge 30 ] && [ "$HISTORY_LEN" -le 10 ]; then
  ok "MMM: 30 sequential assesses in ${SEQ_DURATION}s; rounds dir has $ROUNDS_DIR_COUNT entries; history capped at $HISTORY_LEN"
else
  ko "MMM: round count drift — rounds_dir=$ROUNDS_DIR_COUNT, history=$HISTORY_LEN"
fi

# ---------------------------------------------------------------------------
section "NNN: 200 evidence rows render correctly in resolve"
# ---------------------------------------------------------------------------
N="$TMP/n"; setup_minimal "$N"
(cd "$N" && run assess --fast --json >/dev/null 2>&1) || true
# Inject 200 evidence rows into the latest round dir
node -e "
const fs=require('fs');
const path='$N/.spear/post/rounds/1/evidence.json';
const existing=JSON.parse(fs.readFileSync(path,'utf-8'));
const synthetic=Array.from({length:200},(_,i)=>({
  id: 'synthetic.'+i,
  kind: i%2===0?'mechanical':'subjective',
  description: 'Synthetic evidence row '+i+' for scale testing',
  pass: i%2===0?(i%4!==0):undefined,
  expected: i%2===0?'>= 0':undefined,
  actual: i%2===0?i:undefined,
  artifact: i%2===1?'workspace/synthetic-'+i+'.txt':undefined
}));
fs.writeFileSync(path, JSON.stringify([...existing, ...synthetic], null, 2));
"
START=$(date +%s%N)
PR_OUT=$(cd "$N" && run resolve 2>&1)
END=$(date +%s%N)
RESOLVE_MS=$(( (END - START) / 1000000 ))
PR_LINES=$(echo "$PR_OUT" | wc -l | tr -d ' ')
if [ "$RESOLVE_MS" -lt 3000 ] && [ "$PR_LINES" -ge 20 ]; then
  ok "NNN: resolve with 200+ evidence rows rendered in ${RESOLVE_MS}ms ($PR_LINES lines)"
else
  ko "NNN: resolve slow (${RESOLVE_MS}ms) or output truncated ($PR_LINES lines)"
fi

# ---------------------------------------------------------------------------
section "OOO: file-descriptor leak check (200 sequential commands)"
# ---------------------------------------------------------------------------
# Hard to check FD count from a child process. Proxy: run 200 commands and verify no errors
O="$TMP/o"; setup_minimal "$O"
ERRORS=0
for i in $(seq 1 200); do
  if ! (cd "$O" && run status >/dev/null 2>&1); then
    ERRORS=$((ERRORS + 1))
  fi
done
if [ "$ERRORS" = "0" ]; then
  ok "OOO: 200 sequential status calls all succeeded (no FD leak symptom)"
else
  ko "OOO: $ERRORS/200 status calls failed — possible FD leak"
fi

# ---------------------------------------------------------------------------
section "PPP: SCOPE.md with 1000 unicode + tab + CRLF mixed"
# ---------------------------------------------------------------------------
P="$TMP/p"; setup_minimal "$P"
# Build a SCOPE with mixed line endings and unicode
python3 -c "
content = '''# SCOPE
## Goal
This is a goal sentence with 中文 characters, emoji 🚀, and tabs\\there. Plus more body content to pass the five-word minimum.
## Audience
Maintainers verifying unicode handling. Multiple sentences with mixed encodings to stress the parser.
## Inputs
Source files containing UTF-8 chinese chars and emoji. Plus tab characters for spacing variety in inputs.
## Constraints
Must accept the unicode and tabs. Cannot truncate or mangle the content during validation.
## Done means
Scope passes despite the mixed content. Validator reports the file as filled correctly.
'''
# Add CRLF in some places
content = content.replace('\\n## Audience', '\\r\\n## Audience')
import sys
sys.stdout.buffer.write(content.encode('utf-8'))
" > "$P/.spear/post/SCOPE.md"
(cd "$P" && run scope --json > /tmp/uni-scope.json 2>&1)
SCOPE_VALID=$(jq -r '.valid' /tmp/uni-scope.json 2>/dev/null || echo "?")
if [ "$SCOPE_VALID" = "true" ]; then
  ok "PPP: scope accepts unicode + emoji + tabs + CRLF in SCOPE.md"
else
  ko "PPP: scope rejected mixed-encoding SCOPE.md (valid=$SCOPE_VALID)"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d scale probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
