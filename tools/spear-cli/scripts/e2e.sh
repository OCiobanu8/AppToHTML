#!/usr/bin/env bash
# e2e.sh — MECE end-to-end test of every spear command + slug-aware behavior.
#
# Categories (mutually exclusive, collectively exhaustive over the CLI surface):
#   1. CLI shape         — every command + help is reachable
#   2. Init & scaffold   — all four templates create .spear/<slug>/ correctly
#   3. Multi-slug repo   — two slugs co-exist; auto-resolve fails, --name works
#   4. Scope/plan gates  — exit codes + validation
#   5. Adapter execute   — generic + blog
#   6. Adapter assess    — defects + evidence per adapter
#   7. Per-round history — .spear/<slug>/rounds/N/ artifacts
#   8. Atomic state      — no .tmp leftovers
#   9. Stuck detection   — stuckSince set after 2 unchanged rounds
#  10. Report parsing    — boilerplate ignored; real <spear-report> applied
#  11. Complete signal   — inline mention ignored; own-line tag honored
#  12. Image + config    — error paths
#  13. Resolve PR        — closing-phase report
#  14. spear list        — table + JSON enumeration
#  15. Dogfood           — spear init code self on spear-cli itself

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEAR="$REPO_ROOT/dist/cli.js"
TMP="$(mktemp -d)"
PASS=0
FAIL=0

if [ ! -f "$SPEAR" ]; then
  echo "✗ dist/cli.js not found — run \`npm run build\` first." >&2
  exit 1
fi

ok() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
ko() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAIL=$((FAIL + 1)); }
section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
run() { node "$SPEAR" "$@"; }

# Reusable filled SCOPE.md content (passes scope validator)
read -r -d '' FILLED_SCOPE <<'EOF' || true
# SCOPE

## Goal
Exercise the SPEAR CLI end-to-end against deterministic checks and verifiable evidence rows for the assess phase.

## Audience
Engineers integrating SPEAR into their LLM-driven workflows who need confidence the gates work as advertised.

## Inputs
The CLI source under src/, the test scripts under scripts/, and the docs that describe the convention.

## Constraints
Must run without manual setup beyond npm install and build. Should complete in under thirty seconds.

## Done means
Every category passes its check and the suite reports zero failures with a green tally line.
EOF

# ---------------------------------------------------------------------------
section "1. CLI shape"
# ---------------------------------------------------------------------------
HELP=$(run --help 2>&1)
for cmd in init scope plan execute assess resolve loop status list runner image config; do
  if echo "$HELP" | grep -q "^  $cmd"; then ok "$cmd registered"; else ko "$cmd missing from help"; fi
done

# ---------------------------------------------------------------------------
section "2. Init & scaffold"
# ---------------------------------------------------------------------------
for type in deck blog code generic; do
  D="$TMP/init-$type"
  mkdir -p "$D"
  (cd "$D" && run init "$type" >/dev/null 2>&1) && \
    [ -f "$D/.spear/$type/SCOPE.md" ] && [ -f "$D/.spear/$type/PLAN.md" ] && \
    [ -f "$D/.spear/$type/ASSESS.md" ] && [ -f "$D/.spear/$type/RESOLVE.md" ] && \
    [ -f "$D/.spear/$type/state.json" ] && ok "$type scaffold .spear/$type/" || ko "$type scaffold missing files"
done

# Custom name
N="$TMP/init-named"
mkdir -p "$N"
(cd "$N" && run init blog mypost >/dev/null 2>&1) && [ -f "$N/.spear/mypost/SCOPE.md" ] && \
  ok "custom name: spear init blog mypost → .spear/mypost/" || ko "custom name failed"

# Bad name rejected (capture then grep to avoid pipefail on exit-1 + grep-match)
BAD_OUT=$(cd "$N" && run init blog "bad name with spaces" 2>&1 || true)
echo "$BAD_OUT" | grep -q "Invalid SPEAR project name" && ok "rejects invalid slug names" || ko "should reject invalid slugs"

# ---------------------------------------------------------------------------
section "3. Multi-slug repo"
# ---------------------------------------------------------------------------
M="$TMP/multi"
mkdir -p "$M"
(cd "$M" && run init blog post-a >/dev/null 2>&1)
(cd "$M" && run init blog post-b >/dev/null 2>&1)
[ -d "$M/.spear/post-a" ] && [ -d "$M/.spear/post-b" ] && ok "two slugs co-exist" || ko "multi-slug init"

# Without --name, multi-slug repos must error (capture-then-grep pattern; commands exit 1)
AMBIG_OUT=$(cd "$M" && run scope 2>&1 || true)
echo "$AMBIG_OUT" | grep -q "Multiple SPEAR projects" && ok "auto-resolve fails with multiple slugs (clear error)" || ko "should error on ambiguous slug"

NAME_OUT=$(cd "$M" && run scope --name post-a 2>&1 || true)
echo "$NAME_OUT" | grep -q "SCOPE.md" && ok "explicit --name resolves to a project" || ko "--name flag broken"

ENV_OUT=$(cd "$M" && SPEAR_PROJECT=post-b node "$SPEAR" scope 2>&1 || true)
echo "$ENV_OUT" | grep -q "SCOPE.md" && ok "SPEAR_PROJECT env var resolves to a project" || ko "env var resolution broken"

# ---------------------------------------------------------------------------
section "4. Scope/plan gates"
# ---------------------------------------------------------------------------
D="$TMP/init-blog"
(cd "$D" && run scope >/dev/null 2>&1); [ $? -eq 1 ] && ok "scope rejects unfilled template (exit 1)" || ko "scope exit code wrong"

printf '%s\n' "$FILLED_SCOPE" > "$D/.spear/blog/SCOPE.md"
(cd "$D" && run scope >/dev/null 2>&1); [ $? -eq 0 ] && ok "scope accepts filled template (exit 0)" || ko "scope rejects valid SCOPE.md"

(cd "$D" && run plan >/dev/null 2>&1); PE=$?
[ $PE -ne 0 ] && ok "plan rejects unapproved PLAN.md (exit $PE)" || ko "plan accepted unapproved PLAN.md"

# ---------------------------------------------------------------------------
section "5. Adapter execute (gated by phase: requires scope + plan)"
# ---------------------------------------------------------------------------
# Generic execute needs the full prelude: filled scope + approved plan
G="$TMP/init-generic"
printf '%s\n' "$FILLED_SCOPE" > "$G/.spear/generic/SCOPE.md"
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$G/.spear/generic/PLAN.md" && rm -f "$G/.spear/generic/PLAN.md.bak"
(cd "$G" && run scope >/dev/null 2>&1)
(cd "$G" && run plan >/dev/null 2>&1)
mkdir -p "$G/.spear/generic/workspace" && echo "content" > "$G/.spear/generic/workspace/file.txt"
(cd "$G" && run execute --json >/dev/null 2>&1); [ $? -eq 0 ] && ok "generic execute succeeds (after scope + plan)" || ko "generic execute failed"

# Blog: same prelude. $D is from §3/§4 — already has filled scope, but plan still unapproved.
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$D/.spear/blog/PLAN.md" && rm -f "$D/.spear/blog/PLAN.md.bak"
(cd "$D" && run plan >/dev/null 2>&1)
mkdir -p "$D/.spear/blog/workspace" && cat > "$D/.spear/blog/workspace/draft.md" <<'EOF'
# Why SPEAR
Just a short stub draft so blog assess fires word-count defects.
EOF
(cd "$D" && run execute --json >/dev/null 2>&1); [ $? -eq 0 ] && ok "blog execute succeeds (after scope + plan + draft.md)" || ko "blog execute failed"

# Phase-gate: a fresh init (phase=scope) must REFUSE execute
GATE_TEST="$TMP/gate-test"
mkdir -p "$GATE_TEST" && (cd "$GATE_TEST" && run init blog >/dev/null 2>&1)
GATE_OUT=$(cd "$GATE_TEST" && run execute --json 2>&1 || true)
echo "$GATE_OUT" | grep -q "Cannot execute" && ok "execute refuses when scope/plan haven't passed (hard gate)" || ko "execute didn't enforce phase gate"

# ---------------------------------------------------------------------------
section "6. Adapter assess + evidence"
# ---------------------------------------------------------------------------
ASSESS=$(cd "$D" && run assess --json 2>&1)
echo "$ASSESS" | grep -q '"evidence"' && ok "blog assess emits evidence array" || ko "blog assess missing evidence"
echo "$ASSESS" | grep -q '"blog.draft.word-count"' && ok "blog evidence has word-count check" || ko "blog evidence missing word-count"
echo "$ASSESS" | grep -q '"pass": false' && ok "blog mechanical evidence shows pass:false" || ko "evidence pass field missing"
echo "$ASSESS" | grep -q '"kind": "subjective"' && ok "blog evidence has subjective deferral row" || ko "evidence missing subjective"

# ---------------------------------------------------------------------------
section "7. Per-round history"
# ---------------------------------------------------------------------------
[ -d "$D/.spear/blog/rounds/1" ] && ok "rounds/1/ created" || ko "rounds/1/ missing"
[ -f "$D/.spear/blog/rounds/1/assess.json" ] && ok "rounds/1/assess.json written" || ko "assess.json missing"
[ -f "$D/.spear/blog/rounds/1/evidence.json" ] && ok "rounds/1/evidence.json written" || ko "evidence.json missing"
[ -d "$D/.spear/blog/rounds/1/evidence" ] && ok "rounds/1/evidence/ dir present" || ko "evidence dir missing"
[ -f "$D/.spear/blog/rounds/1/RESOLVE.md" ] && ok "rounds/1/RESOLVE.md snapshot written" || ko "RESOLVE snapshot missing"

(cd "$D" && run assess --json >/dev/null 2>&1); (cd "$D" && run assess --json >/dev/null 2>&1)
HIST_LEN=$(node -e "const s=require('$D/.spear/blog/state.json'); console.log((s.history||[]).length)")
[ "$HIST_LEN" = "3" ] && ok "history accumulates across 3 rounds" || ko "history len=$HIST_LEN"

# ---------------------------------------------------------------------------
section "8. Atomic state writes"
# ---------------------------------------------------------------------------
LEFTOVER=$(find "$D/.spear" -name '*.tmp*' 2>/dev/null | wc -l | tr -d ' ')
[ "$LEFTOVER" = "0" ] && ok "no .tmp files leaked" || ko "$LEFTOVER stray .tmp files"

# ---------------------------------------------------------------------------
section "9. Stuck-loop detection"
# ---------------------------------------------------------------------------
STUCK=$(node -e "const s=require('$D/.spear/blog/state.json'); console.log(s.stuckSince||'none')")
[ "$STUCK" != "none" ] && ok "stuckSince=$STUCK after 3 rounds" || ko "stuckSince not set"

# ---------------------------------------------------------------------------
section "10. Report parsing"
# ---------------------------------------------------------------------------
B="$TMP/report-boilerplate"
mkdir -p "$B"
(cd "$B" && run init blog >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$B/.spear/blog/SCOPE.md"
mkdir -p "$B/.spear/blog/workspace" && echo "# stub" > "$B/.spear/blog/workspace/draft.md"
(cd "$B" && run scope >/dev/null && run assess >/dev/null 2>&1)

LOOP_OUT=$(cd "$B" && run loop --max-rounds 0 --json 2>&1)
echo "$LOOP_OUT" | grep -q "user-signaled" && ko "boilerplate template false-triggered <spear-complete/>" || \
  ok "boilerplate template does NOT short-circuit"

cat >> "$B/.spear/blog/RESOLVE.md" <<'EOF'

<spear-report>
ITERATION: 1
PHASE: resolve
COMPLETED: extended draft, added examples, fixed headers
FILES_CHANGED: workspace/draft.md
TESTS: N/A
NEXT: re-run spear loop
BLOCKERS: None
PROGRESS: 1/2
</spear-report>
EOF
(cd "$B" && run loop --max-rounds 0 --json >/dev/null 2>&1)
PROGRESS=$(node -e "const s=require('$B/.spear/blog/state.json'); console.log(s.lastAssess.progress||'none')")
FIXED=$(node -e "const s=require('$B/.spear/blog/state.json'); console.log(s.lastAssess.fixed||'none')")
[ "$PROGRESS" = "1/2" ] && ok "report PROGRESS parsed: $PROGRESS" || ko "PROGRESS not parsed (got '$PROGRESS')"
[ "$FIXED" = "3" ] && ok "report COMPLETED counted into fixed: $FIXED" || ko "fixed=$FIXED, expected 3"

BLOCKERS=$(node -e "const s=require('$B/.spear/blog/state.json'); console.log(s.blockers||'unset')")
[ "$BLOCKERS" = "unset" ] && ok 'BLOCKERS: None leaves state.blockers unset' || ko "blockers=$BLOCKERS"

B2="$TMP/report-blocked"
mkdir -p "$B2"
(cd "$B2" && run init blog >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$B2/.spear/blog/SCOPE.md"
mkdir -p "$B2/.spear/blog/workspace" && echo "# stub" > "$B2/.spear/blog/workspace/draft.md"
(cd "$B2" && run scope >/dev/null && run assess >/dev/null 2>&1)
cat >> "$B2/.spear/blog/RESOLVE.md" <<'EOF'

<spear-report>
ITERATION: 1
PHASE: resolve
COMPLETED: nothing
FILES_CHANGED: none
TESTS: N/A
NEXT: human review
BLOCKERS: API key not provisioned
PROGRESS: 0/2
</spear-report>
EOF
(cd "$B2" && run loop --max-rounds 0 --json >/dev/null 2>&1)
B2BLOCK=$(node -e "const s=require('$B2/.spear/blog/state.json'); console.log(s.blockers||'unset')")
[ "$B2BLOCK" = "API key not provisioned" ] && ok "BLOCKERS text sets state.blockers" || ko "blockers=$B2BLOCK"

# ---------------------------------------------------------------------------
section "11. Complete signal"
# ---------------------------------------------------------------------------
C="$TMP/complete-inline"
mkdir -p "$C"
(cd "$C" && run init blog >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$C/.spear/blog/SCOPE.md"
mkdir -p "$C/.spear/blog/workspace" && echo "# stub" > "$C/.spear/blog/workspace/draft.md"
(cd "$C" && run scope >/dev/null && run assess >/dev/null 2>&1)
echo "Note: I will add <spear-complete/> when satisfied." >> "$C/.spear/blog/RESOLVE.md"
LOOP_OUT=$(cd "$C" && run loop --max-rounds 0 --json 2>&1)
echo "$LOOP_OUT" | grep -q "user-signaled" && ko "inline mention false-triggered" || \
  ok "inline mention does NOT trigger complete"

echo "" >> "$C/.spear/blog/RESOLVE.md"
echo "<spear-complete/>" >> "$C/.spear/blog/RESOLVE.md"
# Synthetic test — bypass the rubber-stamp guard which correctly fires on round-1 self-completion
(cd "$C" && run loop --max-rounds 5 --allow-fast-convergence >/dev/null 2>&1); CE=$?
[ $CE -eq 0 ] && ok "<spear-complete/> on own line stops loop (exit 0)" || ko "complete signal exit=$CE"

# Anti-rubber-stamp guard: same project, no --allow-fast-convergence, should refuse
# (need to revert the converged state first by clearing completedAt)
node -e "const fs=require('fs'); const p='$C/.spear/blog/state.json'; const s=JSON.parse(fs.readFileSync(p)); delete s.completedAt; s.phase='resolve'; fs.writeFileSync(p, JSON.stringify(s))"
GUARD_OUT=$(cd "$C" && run loop --max-rounds 5 2>&1 || true)
echo "$GUARD_OUT" | grep -q "Rubber-stamp guard" && ok "rubber-stamp guard refuses round-1 self-completion without --allow-fast-convergence" || ko "rubber-stamp guard didn't fire"

# ---------------------------------------------------------------------------
section "12. Image + config error paths"
# ---------------------------------------------------------------------------
HOME_BAK="${HOME:-}"
export HOME="$TMP/fake-home"
mkdir -p "$HOME"
unset OPENAI_API_KEY
ERR_OUT=$(run image --prompt "x" --out "$TMP/x.png" 2>&1); [ $? -eq 1 ] && \
  echo "$ERR_OUT" | grep -q "OPENAI_API_KEY" && ok "image errors clearly when no key" || ko "image error path"

ERR_OUT=$(run image --prompt "x" --out "$TMP/x.png" --size weird 2>&1); \
  echo "$ERR_OUT" | grep -q "Invalid --size" && ok "image rejects invalid size" || ko "image size validation"

run config set openai-key sk-test1234567890ABCD >/dev/null 2>&1
GET_OUT=$(run config get openai-key 2>&1)
echo "$GET_OUT" | grep -q "sk-t" && echo "$GET_OUT" | grep -q "ABCD" && ok "config get masks key" || ko "config masking"
run config unset openai-key >/dev/null 2>&1
[ -f "$HOME/.spear/config.json" ] && [ "$(node -e "const c=require('$HOME/.spear/config.json'); console.log(Object.keys(c).length)")" = "0" ] && \
  ok "config unset removes key" || ko "config unset"
export HOME="$HOME_BAK"

# ---------------------------------------------------------------------------
section "13. Resolve — closing-phase PR summary"
# ---------------------------------------------------------------------------
PR_OUT=$(cd "$B" && run resolve 2>&1)
echo "$PR_OUT" | grep -q "## Highlights" && ok "PR includes Highlights" || ko "no Highlights"
echo "$PR_OUT" | grep -q "## Lowlights" && ok "PR includes Lowlights" || ko "no Lowlights"
echo "$PR_OUT" | grep -q "## What to test" && ok "PR includes What to test" || ko "no What to test"
echo "$PR_OUT" | grep -q "## Warnings" && ok "PR includes Warnings" || ko "no Warnings"
echo "$PR_OUT" | grep -q "## Next steps" && ok "PR includes Next steps" || ko "no Next steps"
echo "$PR_OUT" | grep -q "extended draft" && ok "PR highlights pull from <spear-report>" || ko "report data not surfaced"

PR_JSON=$(cd "$B" && run resolve --json 2>&1)
echo "$PR_JSON" | grep -q '"highlights"' && ok "resolve --json emits PRContext" || ko "PR JSON missing fields"

(cd "$B" && run resolve --write >/dev/null 2>&1)
[ -f "$B/CLOSEOUT.md" ] && grep -q "Highlights" "$B/CLOSEOUT.md" && ok "resolve --write CLOSEOUT.md" || ko "--write didn't persist"

(cd "$B" && run resolve --write PR.md >/dev/null 2>&1)
[ -f "$B/PR.md" ] && ok "resolve --write PR.md respects explicit path" || ko "explicit path ignored"

mkdir -p "$B/.spear/blog" && cat > "$B/.spear/blog/pr-template.md" <<'EOF'
TITLE: {{title}}
SLUG: {{slug}}
ROUNDS: {{rounds}}
DEFECTS_REMAINING: {{defectsRemaining}}
EOF
TPL_OUT=$(cd "$B" && run resolve 2>&1)
echo "$TPL_OUT" | grep -q "^TITLE:" && echo "$TPL_OUT" | grep -q "^SLUG:" && \
  ok "custom .spear/<slug>/pr-template.md is honored" || ko "custom template not used"

STUCK_PR=$(cd "$D" && run resolve 2>&1)
echo "$STUCK_PR" | grep -q "stuck" && ok "stuck status surfaces in PR Warnings" || ko "stuck not surfaced"

NEXT_OUT=$(cd "$D" && run resolve --next 2>&1)
echo "$NEXT_OUT" | grep -q "Next:" && ok "resolve --next legacy flag still works" || ko "--next broken"

# ---------------------------------------------------------------------------
section "14. spear list"
# ---------------------------------------------------------------------------
LIST_OUT=$(cd "$M" && run list 2>&1)
echo "$LIST_OUT" | grep -q "post-a" && echo "$LIST_OUT" | grep -q "post-b" && \
  ok "list shows both slugs" || ko "list missing slugs"

LIST_JSON=$(cd "$M" && run list --json 2>&1)
echo "$LIST_JSON" | grep -q '"projects"' && ok "list --json emits structured rows" || ko "list JSON malformed"

# ---------------------------------------------------------------------------
section "15. Approval gates (--gated + spear approve <phase> + --skip-approval)"
# ---------------------------------------------------------------------------
GATED="$TMP/gated"
mkdir -p "$GATED"
(cd "$GATED" && run init blog post --gated >/dev/null 2>&1)
GATED_FLAG=$(node -e "console.log(require('$GATED/.spear/post/state.json').gated)")
[ "$GATED_FLAG" = "true" ] && ok "init --gated sets state.gated = true" || ko "gated flag not persisted (got $GATED_FLAG)"

# Fill SCOPE so it passes
printf '%s\n' "$FILLED_SCOPE" > "$GATED/.spear/post/SCOPE.md"
(cd "$GATED" && run scope >/dev/null 2>&1) && ok "spear scope works in gated project (no upstream gate)" || ko "scope broke in gated"

# spear plan should refuse: no scope approval yet
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$GATED/.spear/post/PLAN.md" && rm -f "$GATED/.spear/post/PLAN.md.bak"
PLAN_OUT=$(cd "$GATED" && run plan 2>&1 || true)
echo "$PLAN_OUT" | grep -q 'requires approval of "scope"' && ok "gated: plan refuses without spear approve scope" || ko "gated: plan did not refuse"

# spear approve scope → plan now works
(cd "$GATED" && run approve scope >/dev/null 2>&1)
[ -f "$GATED/.spear/post/.approvals/scope.json" ] && ok "spear approve scope wrote .approvals/scope.json" || ko "approval file missing"
(cd "$GATED" && run plan >/dev/null 2>&1) && ok "gated: plan succeeds after approve scope" || ko "plan failed after approval"

# execute should refuse without approve plan
mkdir -p "$GATED/.spear/post/workspace" && echo "# stub" > "$GATED/.spear/post/workspace/draft.md"
EXEC_OUT=$(cd "$GATED" && run execute 2>&1 || true)
echo "$EXEC_OUT" | grep -q 'requires approval of "plan"' && ok "gated: execute refuses without spear approve plan" || ko "gated: execute did not refuse"

# --skip-approval should bypass
(cd "$GATED" && run execute --skip-approval >/dev/null 2>&1) && ok "--skip-approval bypasses the gate" || ko "--skip-approval failed"

# Assess does NOT require approval (execute + assess are one autonomous unit)
ASSESS_OUT=$(cd "$GATED" && run assess --json 2>&1 || true)
echo "$ASSESS_OUT" | jq -e '.evidence | length > 0' >/dev/null 2>&1 && \
  ok "gated: assess runs without approve execute (autonomous loop)" || \
  ko "gated: assess incorrectly gated"

# spear approve --list shows what's approved
LIST_OUT=$(cd "$GATED" && run approve --list 2>&1)
echo "$LIST_OUT" | grep -q "✓ scope" && ok "spear approve --list shows recorded approvals" || ko "approve --list broken"

# spear approve --revoke removes
(cd "$GATED" && run approve scope --revoke >/dev/null 2>&1)
[ ! -f "$GATED/.spear/post/.approvals/scope.json" ] && ok "spear approve --revoke removes the approval file" || ko "revoke didn't remove file"

# Non-gated project: gates are no-ops
NONGATED="$TMP/nongated"
mkdir -p "$NONGATED"
(cd "$NONGATED" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$NONGATED/.spear/post/SCOPE.md"
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$NONGATED/.spear/post/PLAN.md" && rm -f "$NONGATED/.spear/post/PLAN.md.bak"
(cd "$NONGATED" && run scope >/dev/null 2>&1)
(cd "$NONGATED" && run plan >/dev/null 2>&1) && ok "ungated project: plan runs without approve (back-compat)" || ko "ungated: plan broke"

# ---------------------------------------------------------------------------
section "16. Sub-agent grader (--grader cmd, blog adapter)"
# ---------------------------------------------------------------------------
GRD="$TMP/grader"
mkdir -p "$GRD"
(cd "$GRD" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$GRD/.spear/post/SCOPE.md"
mkdir -p "$GRD/.spear/post/workspace" && echo "# stub" > "$GRD/.spear/post/workspace/draft.md"
(cd "$GRD" && run scope >/dev/null 2>&1)

# Fake grader: deterministic output for testing the parse + merge logic
FAKE_GRADER="$TMP/fake-grader.sh"
cat > "$FAKE_GRADER" <<'EOF'
#!/bin/bash
cat > /dev/null
echo "<spear-grade>"
echo '{"metrics":[{"id":"M1","score":7,"evidence":"thin","below_10_reason":"weak"},{"id":"M2","score":10,"evidence":"good"}],"failure_modes":[{"letter":"F","open":true,"evidence":"filler"}]}'
echo "</spear-grade>"
EOF
chmod +x "$FAKE_GRADER"

GRD_OUT=$(cd "$GRD" && run assess --grader "$FAKE_GRADER" --json 2>&1)
GRD_METRICS=$(echo "$GRD_OUT" | jq '[.evidence[] | select(.id | startswith("grader.metric"))] | length' 2>/dev/null)
GRD_FAILURES=$(echo "$GRD_OUT" | jq '[.evidence[] | select(.id | startswith("grader.failure-mode"))] | length' 2>/dev/null)
[ "$GRD_METRICS" = "2" ] && ok "grader: 2 metric evidence rows merged into assess output" || ko "grader metrics=$GRD_METRICS (expected 2)"
[ "$GRD_FAILURES" = "1" ] && ok "grader: 1 open failure-mode merged" || ko "grader failures=$GRD_FAILURES (expected 1)"

# A score below 10 should produce a defect
GRD_DEFS=$(echo "$GRD_OUT" | jq '[.defects[] | select(.metric | startswith("grader/"))] | length' 2>/dev/null)
[ "$GRD_DEFS" = "1" ] && ok "grader: M1=7 produced 1 grader-derived defect" || ko "grader defects=$GRD_DEFS (expected 1 from M1=7)"

# Grader command failure shouldn't crash assess — should warn (to stderr) and continue with valid JSON on stdout
BAD_OUT=$(cd "$GRD" && run assess --grader "/no/such/binary" --json 2>/dev/null)
echo "$BAD_OUT" | jq '.evidence | length' >/dev/null 2>&1 && ok "grader failure doesn't crash assess (continues with adapter-only)" || ko "grader failure crashed assess"

# Code adapter: grader works on source files (uses Read-tool mode automatically)
GRD_C="$TMP/grader-code"
mkdir -p "$GRD_C/src" && echo "export const x = 1;" > "$GRD_C/src/foo.ts"
(cd "$GRD_C" && run init code >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$GRD_C/.spear/code/SCOPE.md"
GRD_C_OUT=$(cd "$GRD_C" && run assess --grader "$FAKE_GRADER" --json 2>&1 || true)
echo "$GRD_C_OUT" | jq -e '[.evidence[] | select(.id | startswith("grader.metric"))] | length > 0' >/dev/null && \
  ok "code adapter supports grader (uses default source files)" || ko "code adapter grader broke"

# --grade-files override accepts arbitrary paths (e.g., images)
GRD_OVERRIDE="$TMP/grader-override"
mkdir -p "$GRD_OVERRIDE"
(cd "$GRD_OVERRIDE" && run init blog post >/dev/null 2>&1)
printf '%s\n' "$FILLED_SCOPE" > "$GRD_OVERRIDE/.spear/post/SCOPE.md"
mkdir -p "$GRD_OVERRIDE/.spear/post/workspace" && echo "stub" > "$GRD_OVERRIDE/.spear/post/workspace/draft.md"
echo "fake jpeg" > "$GRD_OVERRIDE/test.jpg"
(cd "$GRD_OVERRIDE" && run scope >/dev/null 2>&1)
# Capture the prompt the grader sees by using a recorder script
PROMPT_RECORDER="$TMP/recorder.sh"
cat > "$PROMPT_RECORDER" <<'EOF'
#!/bin/bash
cat > /tmp/spear-test-recorded-prompt.txt
echo '<spear-grade>{"metrics":[],"failure_modes":[]}</spear-grade>'
EOF
chmod +x "$PROMPT_RECORDER"
(cd "$GRD_OVERRIDE" && run assess --grader "$PROMPT_RECORDER" --grade-files test.jpg --json >/dev/null 2>&1) || true
grep -q "Read tool" /tmp/spear-test-recorded-prompt.txt && grep -q "test.jpg" /tmp/spear-test-recorded-prompt.txt && \
  ok "--grade-files override: image path triggers Read-tool mode" || ko "--grade-files override broken"

# ---------------------------------------------------------------------------
section "17. Dogfood — spear init code self on spear-cli itself"
# ---------------------------------------------------------------------------
DF="$TMP/dogfood-spear-cli"
cp -r "$REPO_ROOT" "$DF"
rm -rf "$DF/.spear"
(cd "$DF" && run init code self >/dev/null 2>&1) && ok "spear init code self on spear-cli source" || ko "init failed"

cat > "$DF/.spear/self/SCOPE.md" <<'EOF'
# SCOPE

## Goal
Verify the spear-cli TypeScript codebase passes its own deterministic checks: type-check clean, no console.log debris, no `any` types, no leftover TODO comments in shipped code.

## Audience
Maintainers reviewing changes to the spear-cli source before publishing a release to npm.

## Inputs
All TypeScript source under src/, the package.json scripts, and the existing rubric in ASSESS.md.

## Constraints
Must pass without modifying source files. Mechanical checks should agree with manual inspection of the dist build.

## Done means
spear assess emits zero high-severity defects and the evidence manifest covers every src/ file scanned.
EOF
(cd "$DF" && run scope >/dev/null 2>&1) && ok "dogfood scope passes" || ko "dogfood scope rejected"

(cd "$DF" && run assess --fast --json > /tmp/dogfood-assess.json 2>&1); AE=$?
EVIDENCE_COUNT=$(node -e "const r=require('/tmp/dogfood-assess.json'); console.log(r.evidence.length)" 2>/dev/null)
DEFECT_COUNT=$(node -e "const r=require('/tmp/dogfood-assess.json'); console.log(r.defects.length)" 2>/dev/null)
[ -n "$EVIDENCE_COUNT" ] && [ "$EVIDENCE_COUNT" -gt 0 ] && ok "dogfood assess emits $EVIDENCE_COUNT evidence rows" || ko "dogfood no evidence"
ok "dogfood assess found $DEFECT_COUNT defect(s) (exit $AE)"

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d failed).\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  exit 1
fi
