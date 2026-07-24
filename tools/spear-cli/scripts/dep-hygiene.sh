#!/usr/bin/env bash
# dep-hygiene.sh — round 8 probe (GG): npm audit, outdated, license compatibility.

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
section "GG: npm audit"
# ---------------------------------------------------------------------------
AUDIT_JSON=$(npm audit --json 2>/dev/null)
HIGH=$(echo "$AUDIT_JSON" | jq -r '.metadata.vulnerabilities.high // 0')
CRIT=$(echo "$AUDIT_JSON" | jq -r '.metadata.vulnerabilities.critical // 0')
TOTAL=$(echo "$AUDIT_JSON" | jq -r '.metadata.vulnerabilities.total // 0')

if [ "$HIGH" = "0" ] && [ "$CRIT" = "0" ]; then
  ok "GG: npm audit — 0 high, 0 critical (total: $TOTAL)"
else
  ko "GG: npm audit — $HIGH high + $CRIT critical vulnerabilities"
  echo "$AUDIT_JSON" | jq -r '.vulnerabilities | to_entries | map(select(.value.severity == "high" or .value.severity == "critical")) | .[] | "  - \(.key) (\(.value.severity)): \(.value.title // "")"' 2>/dev/null
fi

# ---------------------------------------------------------------------------
section "GG: npm outdated (major-version stale)"
# ---------------------------------------------------------------------------
OUTDATED_JSON=$(npm outdated --json 2>&1 || echo '{}')
# npm outdated exits 1 when packages are outdated (that's not a failure)
MAJOR_STALE=$(echo "$OUTDATED_JSON" | jq -r 'to_entries | map(select(.value.current and .value.latest and (.value.current | split(".")[0]) != (.value.latest | split(".")[0]))) | length' 2>/dev/null || echo "0")
TOTAL_STALE=$(echo "$OUTDATED_JSON" | jq -r 'keys | length' 2>/dev/null || echo "0")

if [ "$MAJOR_STALE" = "0" ]; then
  ok "GG: no major-version-stale deps (total stale: $TOTAL_STALE)"
else
  ko "GG: $MAJOR_STALE deps are major-version stale (of $TOTAL_STALE total)"
  echo "$OUTDATED_JSON" | jq -r 'to_entries | map(select(.value.current and .value.latest and (.value.current | split(".")[0]) != (.value.latest | split(".")[0]))) | .[] | "  - \(.key): \(.value.current) → \(.value.latest)"' 2>/dev/null
fi

# ---------------------------------------------------------------------------
section "GG: license compatibility (declared)"
# ---------------------------------------------------------------------------
# Inspect every dep's license field; flag GPL/AGPL/LGPL incompat with Apache-2.0
INCOMPAT=0
for pkg in $(find node_modules -maxdepth 2 -name package.json -not -path '*/node_modules/.*' | head -200); do
  LIC=$(jq -r '.license // empty' "$pkg" 2>/dev/null)
  PNAME=$(jq -r '.name // empty' "$pkg" 2>/dev/null)
  case "$LIC" in
    GPL*|AGPL*|LGPL*|Affero*)
      ko "GG: $PNAME has copyleft license: $LIC"
      INCOMPAT=$((INCOMPAT+1))
      ;;
  esac
done
[ "$INCOMPAT" = "0" ] && ok "GG: no copyleft (GPL/AGPL/LGPL) deps in node_modules"

# ---------------------------------------------------------------------------
section "GG: declared package.json deps shape"
# ---------------------------------------------------------------------------
# Direct deps should be reasonable count, no unexpected ones
DIRECT_DEPS=$(jq -r '.dependencies | keys | length' package.json)
[ "$DIRECT_DEPS" -le 5 ] && ok "GG: only $DIRECT_DEPS direct runtime deps (≤ 5; minimal surface)" || ko "GG: $DIRECT_DEPS direct deps (more than 5)"

DEV_DEPS=$(jq -r '.devDependencies | keys | length' package.json)
ok "GG: $DEV_DEPS direct dev deps (TypeScript + types)"

# Engines.node specified
ENGINES=$(jq -r '.engines.node // empty' package.json)
[ -n "$ENGINES" ] && ok "GG: engines.node = '$ENGINES'" || ko "GG: engines.node not specified"

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d dep-hygiene probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
