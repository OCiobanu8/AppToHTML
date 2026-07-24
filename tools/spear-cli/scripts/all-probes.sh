#!/usr/bin/env bash
# all-probes.sh — run every adversarial probe script + e2e + record cumulative numbers.
# Use this as the canonical "is the CLI healthy" entry point.

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOTAL_PASS=0
TOTAL_FAIL=0
FAILED_SCRIPTS=()

print_section() { printf '\n\033[1m=== %s ===\033[0m\n' "$1"; }

for s in e2e adversarial doc-examples fresh-clone dep-hygiene cross-platform extreme security state-corruption scale distribution deck-e2e; do
  print_section "scripts/$s.sh"
  if bash "$REPO_ROOT/scripts/$s.sh" 2>&1 | tail -1 | tee /tmp/probe-result.txt | grep -qE 'passed\.|adversarial probes passed'; then
    LAST=$(cat /tmp/probe-result.txt)
    NUM=$(echo "$LAST" | grep -oE '[0-9]+/[0-9]+' | head -1)
    PASS_N=$(echo "$NUM" | cut -d/ -f1)
    TOTAL_N=$(echo "$NUM" | cut -d/ -f2)
    TOTAL_PASS=$((TOTAL_PASS + PASS_N))
    TOTAL_FAIL=$((TOTAL_FAIL + (TOTAL_N - PASS_N)))
  else
    LAST=$(cat /tmp/probe-result.txt)
    echo "  ✗ $s.sh failed: $LAST"
    NUM=$(echo "$LAST" | grep -oE '[0-9]+/[0-9]+' | head -1)
    PASS_N=$(echo "$NUM" | cut -d/ -f1 || echo 0)
    TOTAL_N=$(echo "$NUM" | cut -d/ -f2 || echo 1)
    TOTAL_PASS=$((TOTAL_PASS + PASS_N))
    TOTAL_FAIL=$((TOTAL_FAIL + (TOTAL_N - PASS_N)))
    FAILED_SCRIPTS+=("$s.sh")
  fi
done

echo
print_section "Cumulative"
TOTAL_N=$((TOTAL_PASS + TOTAL_FAIL))
if [ "$TOTAL_FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d probes passed across all scripts.\033[0m\n' "$TOTAL_PASS" "$TOTAL_N"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d failed) across all scripts.\033[0m\n' "$TOTAL_PASS" "$TOTAL_N" "$TOTAL_FAIL"
  for s in "${FAILED_SCRIPTS[@]}"; do echo "  - $s"; done
  exit 1
fi
