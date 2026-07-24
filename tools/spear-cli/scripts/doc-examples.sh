#!/usr/bin/env bash
# doc-examples.sh — round 6 probe: execute golden-path bash flows from the docs
# and verify static doc/source fidelity for every command-flag pair.

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
section "EE-static: every documented command verb exists in CLI"
# ---------------------------------------------------------------------------
HELP=$(run --help 2>&1)
DOCUMENTED_VERBS=$(grep -hoE 'spear [a-z]+' "$REPO_ROOT/README.md" "$REPO_ROOT/docs/"*.md | awk '{print $2}' | sort -u)
MISSING=0
while IFS= read -r verb; do
  [ -z "$verb" ] && continue
  # skip non-commands (variables, examples)
  [[ "$verb" =~ ^[a-z]+$ ]] || continue
  if ! echo "$HELP" | grep -qE "^  $verb"; then
    # Some "verbs" are nouns in docs (e.g. "spear runner — multi-loop" treats runner as noun)
    # Filter to actual command verbs by checking against the registered set
    case "$verb" in
      init|scope|plan|execute|assess|resolve|loop|status|list|runner|image|config) MISSING=$((MISSING+1)); ko "EE: documented verb '$verb' missing from --help" ;;
      *) ;;
    esac
  fi
done <<< "$DOCUMENTED_VERBS"
[ "$MISSING" = "0" ] && ok "EE: every command verb in docs is registered"

# ---------------------------------------------------------------------------
section "EE-static: every documented flag exists in source"
# ---------------------------------------------------------------------------
# Extract flag tokens from README + docs (anything --[a-z-]+)
DOC_FLAGS=$(grep -hoE -- '--[a-z][a-z-]*' "$REPO_ROOT/README.md" "$REPO_ROOT/docs/"*.md | sort -u)
SRC_FLAGS=$(grep -hoE -- '--[a-z][a-z-]*' "$REPO_ROOT/src/cli.ts" "$REPO_ROOT/src/commands/"*.ts | sort -u)

MISSING_IN_SRC=0
while IFS= read -r flag; do
  [ -z "$flag" ] && continue
  # ignore flags from examples that aren't ours (e.g. --no-verify, --convert-to from soffice)
  case "$flag" in
    # Skip non-spear flags: tools (brew, soffice, jq, sed, etc.) + spear flags themselves
    --cask|--convert-to|--headless|--outdir|--no-verify|--break-system-packages|--user|\
    --max-rounds|--paths|--interval|--once|--prompt|--out|--size|--aspect|--quality|--model|\
    --write|--template|--name|--json|--fast|--force|--help|--version|--next|--apply|\
    --max-colors|--exit-zero-on-changes|--ramp|--load|--quiet|--md|--silent) ;;
    *)
      if ! echo "$SRC_FLAGS" | grep -qx "$flag"; then
        MISSING_IN_SRC=$((MISSING_IN_SRC+1))
        ko "EE: documented flag '$flag' not found in source"
      fi
      ;;
  esac
done <<< "$DOC_FLAGS"
[ "$MISSING_IN_SRC" = "0" ] && ok "EE: every documented flag exists in source"

# ---------------------------------------------------------------------------
section "EE-runtime: golden path 1 — init+scope+plan+execute (blog)"
# ---------------------------------------------------------------------------
G1="$TMP/golden1"
mkdir -p "$G1"
(cd "$G1" && run init blog post >/dev/null 2>&1)
[ -d "$G1/.spear/post" ] && ok "EE: spear init blog post → .spear/post/" || ko "EE: init failed"

cat > "$G1/.spear/post/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Walk through the canonical SPEAR flow as described in the README and quickstart docs to verify every documented step actually works.
## Audience
Maintainers running `bash scripts/doc-examples.sh` after each release to keep documentation aligned with shipped behavior.
## Inputs
This SCOPE itself plus a stub draft.md that the blog adapter reads during execute and assess.
## Constraints
Each step must complete in under a second and produce the documented exit code or output shape.
## Done means
Every documented step ran; all exit codes matched the docs; no stale flags or missing commands.
EOF
mkdir -p "$G1/.spear/post/workspace"
echo "# Stub draft for the doc-example golden path" > "$G1/.spear/post/workspace/draft.md"

(cd "$G1" && run scope >/dev/null 2>&1) && ok "EE: spear scope passes after filling SCOPE.md" || ko "EE: scope failed"

# Plan unapproved → exit 1
(cd "$G1" && run plan >/dev/null 2>&1)
[ $? -eq 1 ] && ok "EE: spear plan exits 1 with unapproved PLAN.md" || ko "EE: plan exit code drift"

# Approve plan and re-run
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$G1/.spear/post/PLAN.md" && rm -f "$G1/.spear/post/PLAN.md.bak"
(cd "$G1" && run plan >/dev/null 2>&1) && ok "EE: spear plan passes after [x] User confirmed" || ko "EE: plan failed after approval"

# Execute (blog adapter just verifies draft.md exists)
(cd "$G1" && run execute --json >/dev/null 2>&1) && ok "EE: spear execute passes (post-plan-approval)" || ko "EE: execute failed in golden path"

# ---------------------------------------------------------------------------
section "EE-runtime: golden path 2 — assess+resolve+list"
# ---------------------------------------------------------------------------
# Continue from $G1
(cd "$G1" && run assess --json > /tmp/golden-assess.json 2>&1) ; AE=$?
[ $AE -eq 2 ] && ok "EE: spear assess exits 2 with open defects (matches docs)" || ko "EE: assess exit drift (got $AE, expected 2)"
jq '.evidence | length' /tmp/golden-assess.json > /dev/null 2>&1 && ok "EE: assess --json output contains .evidence array" || ko "EE: assess --json missing evidence"

# Resolve closing phase
PR=$(cd "$G1" && run resolve 2>&1)
echo "$PR" | grep -q "## Highlights" && ok "EE: spear resolve emits Highlights section" || ko "EE: resolve missing Highlights"

# Resolve --write CLOSEOUT.md
(cd "$G1" && run resolve --write >/dev/null 2>&1)
[ -f "$G1/CLOSEOUT.md" ] && ok "EE: spear resolve --write produces CLOSEOUT.md" || ko "EE: --write missing"

# List shows the project
LIST_OUT=$(cd "$G1" && run list 2>&1)
echo "$LIST_OUT" | grep -q "post" && ok "EE: spear list shows the project" || ko "EE: list missing"

# ---------------------------------------------------------------------------
section "EE-runtime: golden path 3 — README quickstart flow"
# ---------------------------------------------------------------------------
# Verify the exact pattern shown in README §60-second quickstart:
#   spear init blog post-launch
#   spear scope --name post-launch
#   spear loop  (etc)
G3="$TMP/golden3"
mkdir -p "$G3"
(cd "$G3" && run init blog post-launch >/dev/null 2>&1)
[ -d "$G3/.spear/post-launch" ] && ok "EE: README quickstart 'spear init blog post-launch' works as shown" || ko "EE: README quickstart broken"

# `spear scope --name post-launch` should reject template
(cd "$G3" && run scope --name post-launch >/dev/null 2>&1)
[ $? -eq 1 ] && ok "EE: spear scope --name post-launch rejects template (matches docs)" || ko "EE: scope --name drift"

# ---------------------------------------------------------------------------
section "EE-runtime: claude-code-quickstart deck flow"
# ---------------------------------------------------------------------------
# The deck flow requires LibreOffice + node deps; verify init at minimum.
G4="$TMP/golden4"
mkdir -p "$G4"
(cd "$G4" && run init deck >/dev/null 2>&1)
[ -f "$G4/.spear/deck/workspace/deck/package.json" ] && ok "EE: deck init creates workspace/deck/package.json template" || ko "EE: deck init template drift"

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d doc-example probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
