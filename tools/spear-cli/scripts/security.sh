#!/usr/bin/env bash
# security.sh — round 11 probes: path traversal, symlinks, force-overwrite, post-converged assess.

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
section "TT: path traversal in slug"
# ---------------------------------------------------------------------------
TT="$TMP/tt"
mkdir -p "$TT"

# The validator enforces ^[a-z0-9][a-z0-9_-]*$/i — no dots, no slashes.
for slug in '../escape' '/abs/path' '..' '.' 'foo/bar' 'foo..bar' '~root'; do
  OUT=$(cd "$TT" && run init blog "$slug" 2>&1 || true)
  if echo "$OUT" | grep -qiE 'invalid|unknown'; then
    ok "TT: path-traversal slug rejected: '$slug'"
  else
    # Did it actually write anywhere it shouldn't have?
    if [ -d "$TT/.spear/$slug" ] || [ -d "/$slug" ] || [ -d "$TT/../escape" ]; then
      ko "TT: path-traversal slug ACCEPTED: '$slug' — possible filesystem write outside .spear/"
    else
      # Some shell-level sanitization saved us; still suspicious if no error
      ko "TT: path-traversal slug '$slug' didn't error and didn't write — verify behavior"
    fi
  fi
done

# Make sure no escape happened
if [ ! -d "$TT/escape" ] && [ ! -d "$TMP/escape" ]; then
  ok "TT: no filesystem escape from any traversal attempt"
else
  ko "TT: some traversal attempt wrote outside .spear/"
fi

# ---------------------------------------------------------------------------
section "UU: state.json as symlink"
# ---------------------------------------------------------------------------
UU="$TMP/uu"
mkdir -p "$UU"
(cd "$UU" && run init blog post >/dev/null 2>&1)

# Replace state.json with a symlink to /tmp/external-target
EXTERNAL="$TMP/external-target.json"
echo '{"type":"blog","slug":"post","round":99,"phase":"converged","maxRounds":20}' > "$EXTERNAL"
rm "$UU/.spear/post/state.json"
ln -s "$EXTERNAL" "$UU/.spear/post/state.json"

OUT=$(cd "$UU" && run status 2>&1 || true)
if echo "$OUT" | grep -q "round: 99"; then
  ok "UU: symlinked state.json read correctly"
else
  ko "UU: symlinked state.json read failed: $OUT"
fi

# Now write to it — does atomic rename respect the symlink or replace it?
cat > "$UU/.spear/post/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Probe whether atomic state writes follow symlinks or replace them.
## Audience
Maintainers verifying the rename-based atomic-write contract under symlinks.
## Inputs
A symlinked state.json pointing outside the project tree.
## Constraints
The behavior should be consistent: either follow the symlink (write through) or replace it.
## Done means
Symlink behavior is documented and predictable.
EOF
(cd "$UU" && run scope >/dev/null 2>&1) || true
# After scope, state.json should reflect new state
if [ -L "$UU/.spear/post/state.json" ]; then
  ok "UU: symlink preserved after atomic write (rename-into-place writes through symlink target)"
elif [ -f "$UU/.spear/post/state.json" ]; then
  ok "UU: symlink replaced by regular file after atomic write (also a valid behavior)"
else
  ko "UU: state.json missing entirely after scope"
fi

# ---------------------------------------------------------------------------
section "VV: --force re-init"
# ---------------------------------------------------------------------------
VV="$TMP/vv"
mkdir -p "$VV"
(cd "$VV" && run init blog post >/dev/null 2>&1)
echo "user-edited content that should NOT survive --force" > "$VV/.spear/post/SCOPE.md"

# Re-init without --force: should refuse to clobber
OUT_NO_FORCE=$(cd "$VV" && run init blog post 2>&1 || true)
if echo "$OUT_NO_FORCE" | grep -q "Exists, skipping"; then
  ok "VV: re-init without --force preserves user content"
else
  ko "VV: re-init without --force may have clobbered: $(echo "$OUT_NO_FORCE" | head -3)"
fi
if grep -q "user-edited" "$VV/.spear/post/SCOPE.md"; then
  ok "VV: user-edited SCOPE.md preserved without --force"
else
  ko "VV: user-edited content lost"
fi

# Re-init WITH --force: should overwrite back to template
(cd "$VV" && run init blog post --force >/dev/null 2>&1)
if ! grep -q "user-edited" "$VV/.spear/post/SCOPE.md"; then
  ok "VV: --force overwrites with template"
else
  ko "VV: --force didn't overwrite"
fi

# ---------------------------------------------------------------------------
section "WW: assess on already-converged project"
# ---------------------------------------------------------------------------
WW="$TMP/ww"
mkdir -p "$WW"
(cd "$WW" && run init blog post >/dev/null 2>&1)
cat > "$WW/.spear/post/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Verify behavior when assess is run on a project that already converged in a previous round.
## Audience
Maintainers checking that round counters, evidence, and state stay consistent across multiple converged rounds.
## Inputs
A blog draft that produces zero defects so the project converges immediately.
## Constraints
After convergence, re-running assess should not crash and should either produce the same output or accept new defects gracefully.
## Done means
Multiple converged rounds produce consistent behavior with no errors.
EOF
mkdir -p "$WW/.spear/post/workspace"
# Long enough draft to pass the word-count check
{
  for i in $(seq 1 100); do echo "This is sentence number $i providing enough body content for the blog adapter to consider the draft satisfactory in word-count terms."; done
} > "$WW/.spear/post/workspace/draft.md"
(cd "$WW" && run scope >/dev/null 2>&1)
# First assess
(cd "$WW" && run assess --fast --json > /tmp/ww-1.json 2>&1) || true
PHASE1=$(jq -r '.converged' /tmp/ww-1.json 2>/dev/null || echo "?")
# Second assess (post-converged)
(cd "$WW" && run assess --fast --json > /tmp/ww-2.json 2>&1) || true
PHASE2=$(jq -r '.converged' /tmp/ww-2.json 2>/dev/null || echo "?")

if [ "$PHASE1" = "true" ] && [ "$PHASE2" = "true" ]; then
  ok "WW: assess on converged project remains converged (idempotent)"
elif [ "$PHASE1" = "false" ]; then
  # The blog might still have minor defects — fine, just verify the second pass is consistent
  ROUND2=$(jq -r '.round' /tmp/ww-2.json)
  if [ "$ROUND2" -ge 2 ]; then
    ok "WW: re-assess after first round increments round counter (got round $ROUND2)"
  else
    ko "WW: round counter didn't advance on re-assess"
  fi
fi

# ---------------------------------------------------------------------------
section "XX: runner with 0 projects in cwd"
# ---------------------------------------------------------------------------
XX="$TMP/xx"
mkdir -p "$XX"
OUT=$(cd "$XX" && run runner --once 2>&1 || true)
if echo "$OUT" | grep -qE "No SPEAR projects found"; then
  ok "XX: runner errors cleanly when no projects in cwd"
else
  ko "XX: runner output unexpected: $(echo "$OUT" | head -1)"
fi

# ---------------------------------------------------------------------------
section "YY: runner with --once + --json on 1 slug"
# ---------------------------------------------------------------------------
YY="$TMP/yy"
mkdir -p "$YY"
(cd "$YY" && run init blog post >/dev/null 2>&1)
RUNNER_JSON=$(cd "$YY" && run runner --once --json 2>&1 || true)
if echo "$RUNNER_JSON" | jq . > /dev/null 2>&1; then
  LOOPS=$(echo "$RUNNER_JSON" | jq '.loops | length')
  ok "YY: runner --once --json emits valid JSON with $LOOPS loop(s)"
else
  ko "YY: runner --once --json invalid: $(echo "$RUNNER_JSON" | head -3)"
fi

# ---------------------------------------------------------------------------
section "ZZ: long-path slug"
# ---------------------------------------------------------------------------
ZZ="$TMP/zz"
mkdir -p "$ZZ"
LONG_SLUG=$(printf 'a%.0s' {1..100})  # 100 chars — under the 200 we already tested
OUT=$(cd "$ZZ" && run init blog "$LONG_SLUG" 2>&1 || true)
if [ -d "$ZZ/.spear/$LONG_SLUG" ]; then
  # Try a full assess pass on the long-named project
  cat > "$ZZ/.spear/$LONG_SLUG/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Verify a 100-char slug name works through the full SPEAR pipeline without truncation or path-length errors on macOS / Linux.
## Audience
Maintainers stress-testing the slug-handling code with names approaching common filesystem limits.
## Inputs
A long slug name and a stub workspace.
## Constraints
Every operation should complete without truncation or filesystem errors.
## Done means
The full pipeline runs end-to-end with the long-named slug.
EOF
  mkdir -p "$ZZ/.spear/$LONG_SLUG/workspace"
  echo "# stub" > "$ZZ/.spear/$LONG_SLUG/workspace/draft.md"
  (cd "$ZZ" && run scope --name "$LONG_SLUG" >/dev/null 2>&1) && \
    ok "ZZ: 100-char slug — scope passes" || ko "ZZ: 100-char slug — scope failed"
else
  ko "ZZ: 100-char slug init failed"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d security/edge probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
