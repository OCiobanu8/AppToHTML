#!/usr/bin/env bash
# fresh-clone.sh — round 7 probe (FF): can someone clone, install, build, and
# run e2e from scratch without depending on local state?
#
# Sandboxes a clone into /tmp, runs the full setup pipeline, and verifies:
#   - npm install completes with no missing deps
#   - npm run build produces dist/cli.js
#   - bash scripts/e2e.sh passes (independently of source repo)

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
SANDBOX="$TMP/spear-cli-fresh"
PASS=0
FAIL=0
FINDINGS=()

ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
ko() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); FINDINGS+=("$1"); }
section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------
section "FF: fresh clone via local origin"
# ---------------------------------------------------------------------------
git clone --quiet "$REPO_ROOT" "$SANDBOX" 2>&1 | head -3
[ -d "$SANDBOX/.git" ] && ok "FF: clone succeeded" || ko "FF: clone failed"
[ -f "$SANDBOX/package.json" ] && ok "FF: package.json present in clone" || ko "FF: package.json missing"
[ -f "$SANDBOX/src/cli.ts" ] && ok "FF: src/cli.ts present in clone" || ko "FF: src missing"

# Should NOT contain dist/, node_modules/, .spear/{*}/state.json, .spear/{*}/rounds/
[ ! -d "$SANDBOX/dist" ] && ok "FF: clone has no committed dist/" || ko "FF: dist/ leaked into repo"
[ ! -d "$SANDBOX/node_modules" ] && ok "FF: clone has no committed node_modules/" || ko "FF: node_modules leaked"
[ ! -f "$SANDBOX/.spear/self/state.json" ] && ok "FF: .spear/self/state.json correctly gitignored" || ko "FF: state.json leaked"
[ ! -d "$SANDBOX/.spear/self/rounds" ] && ok "FF: .spear/self/rounds/ correctly gitignored" || ko "FF: rounds/ leaked"

# Spec files should be tracked
for f in SCOPE.md PLAN.md ASSESS.md RESOLVE.md; do
  [ -f "$SANDBOX/.spear/self/$f" ] && ok "FF: .spear/self/$f tracked in clone" || ko "FF: .spear/self/$f missing in clone"
done

# ---------------------------------------------------------------------------
section "FF: npm install in fresh clone"
# ---------------------------------------------------------------------------
INSTALL_OUT=$(cd "$SANDBOX" && npm install --silent 2>&1)
INSTALL_EXIT=$?
if [ $INSTALL_EXIT -eq 0 ]; then
  ok "FF: npm install exits 0"
else
  ko "FF: npm install failed (exit $INSTALL_EXIT)"
  echo "$INSTALL_OUT" | tail -10
fi
[ -d "$SANDBOX/node_modules/commander" ] && ok "FF: commander dep installed" || ko "FF: commander missing"
[ -d "$SANDBOX/node_modules/kleur" ] && ok "FF: kleur dep installed" || ko "FF: kleur missing"
[ -d "$SANDBOX/node_modules/zod" ] && ok "FF: zod dep installed" || ko "FF: zod missing"

# ---------------------------------------------------------------------------
section "FF: npm run build in fresh clone"
# ---------------------------------------------------------------------------
BUILD_OUT=$(cd "$SANDBOX" && npm run build 2>&1)
[ $? -eq 0 ] && ok "FF: npm run build exits 0" || ko "FF: build failed: $BUILD_OUT"
[ -f "$SANDBOX/dist/cli.js" ] && ok "FF: dist/cli.js produced" || ko "FF: dist/cli.js missing"

# ---------------------------------------------------------------------------
section "FF: e2e from fresh clone"
# ---------------------------------------------------------------------------
E2E_OUT=$(cd "$SANDBOX" && bash scripts/e2e.sh 2>&1 | tail -1)
if echo "$E2E_OUT" | grep -qE "✓ [0-9]+/[0-9]+ passed"; then
  COUNT=$(echo "$E2E_OUT" | grep -oE '[0-9]+/[0-9]+ passed' | head -1)
  ok "FF: e2e in fresh clone: $COUNT"
else
  ko "FF: e2e failed in fresh clone: $E2E_OUT"
fi

# ---------------------------------------------------------------------------
section "FF: adversarial from fresh clone"
# ---------------------------------------------------------------------------
ADV_OUT=$(cd "$SANDBOX" && bash scripts/adversarial.sh 2>&1 | tail -1)
if echo "$ADV_OUT" | grep -qE "✓ [0-9]+/[0-9]+ adversarial"; then
  COUNT=$(echo "$ADV_OUT" | grep -oE '[0-9]+/[0-9]+ adversarial' | head -1)
  ok "FF: adversarial in fresh clone: $COUNT"
else
  ko "FF: adversarial failed in fresh clone: $ADV_OUT"
fi

# ---------------------------------------------------------------------------
section "FF: doc-examples from fresh clone"
# ---------------------------------------------------------------------------
DOC_OUT=$(cd "$SANDBOX" && bash scripts/doc-examples.sh 2>&1 | tail -1)
if echo "$DOC_OUT" | grep -qE "✓ [0-9]+/[0-9]+ doc-example"; then
  COUNT=$(echo "$DOC_OUT" | grep -oE '[0-9]+/[0-9]+ doc-example' | head -1)
  ok "FF: doc-examples in fresh clone: $COUNT"
else
  ko "FF: doc-examples failed in fresh clone"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d fresh-clone probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
