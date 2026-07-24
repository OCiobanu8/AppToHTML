#!/usr/bin/env bash
# deck-e2e.sh — round 15: end-to-end exercise of the deck adapter.
# Generates a 3-slide pptxgenjs deck, renders to JPEGs via LibreOffice + pdftoppm,
# runs assess to verify per-slide evidence with hashes + sizes.

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

# Skip the round if LibreOffice / pdftoppm aren't installed
if ! command -v soffice >/dev/null 2>&1 && [ ! -x /Applications/LibreOffice.app/Contents/MacOS/soffice ]; then
  echo "  ⏭  LibreOffice not installed — skipping deck e2e"
  exit 0
fi
if ! command -v pdftoppm >/dev/null 2>&1; then
  echo "  ⏭  pdftoppm not installed — skipping deck e2e"
  exit 0
fi

# ---------------------------------------------------------------------------
section "VVV: deck init creates expected scaffold"
# ---------------------------------------------------------------------------
D="$TMP/deck"
mkdir -p "$D"
(cd "$D" && run init deck mydeck >/dev/null 2>&1)
[ -d "$D/.spear/mydeck/workspace/deck" ] && ok "VVV: workspace/deck/ created" || ko "VVV: workspace/deck/ missing"
[ -f "$D/.spear/mydeck/workspace/deck/package.json" ] && ok "VVV: starter package.json present" || ko "VVV: package.json missing"

# ---------------------------------------------------------------------------
section "WWW: minimal build.js generates a 3-slide pptx"
# ---------------------------------------------------------------------------
cat > "$D/.spear/mydeck/workspace/deck/build.js" <<'EOF'
import pptxgenjs from 'pptxgenjs';
import path from 'path';
import { mkdirSync } from 'fs';

const OUTPUT_DIR = process.env.OUTPUT_DIR ?? path.join(process.cwd(), '..', '..', 'output');
mkdirSync(OUTPUT_DIR, { recursive: true });

const pres = new pptxgenjs();
pres.layout = 'LAYOUT_WIDE';

const titles = ['Slide One Title', 'Slide Two Title', 'Slide Three Title'];
for (const t of titles) {
  const s = pres.addSlide();
  s.addText(t, { x: 0.5, y: 0.5, w: 12, h: 1.5, fontSize: 44, bold: true });
  s.addText(`Body content for ${t}.`, { x: 0.5, y: 2.5, w: 12, h: 4, fontSize: 24 });
}

await pres.writeFile({ fileName: path.join(OUTPUT_DIR, 'deck.pptx') });
console.log('Wrote ' + path.join(OUTPUT_DIR, 'deck.pptx'));
EOF
ok "WWW: minimal build.js written"

# Make sure pptxgenjs is installed via the package.json template
INSTALL_OUT=$(cd "$D/.spear/mydeck/workspace/deck" && npm install --silent 2>&1)
[ $? -eq 0 ] && ok "WWW: npm install in workspace/deck succeeded" || ko "WWW: npm install failed"
[ -d "$D/.spear/mydeck/workspace/deck/node_modules/pptxgenjs" ] && ok "WWW: pptxgenjs dep installed" || ko "WWW: pptxgenjs missing"

# Walk through scope+plan first (phase-gate)
cat > "$D/.spear/mydeck/SCOPE.md" <<'EOF'
# SCOPE
## Goal
Render a three-slide deck end-to-end via the spear-cli deck adapter to verify the full pipeline (build, render, assess) works.
## Audience
Maintainers verifying the deck adapter against a real LibreOffice + pdftoppm install.
## Inputs
A minimal build.js producing three slides with titles and body text only.
## Constraints
The pipeline must complete within thirty seconds and produce one JPEG per slide.
## Done means
Three v-NN.jpg files exist with non-zero sizes; assess emits per-slide evidence with sha256 hashes.
EOF
sed -i.bak 's/\[ \] User confirmed/[x] User confirmed/g' "$D/.spear/mydeck/PLAN.md" && rm -f "$D/.spear/mydeck/PLAN.md.bak"
(cd "$D" && run scope --name mydeck >/dev/null 2>&1)
(cd "$D" && run plan --name mydeck >/dev/null 2>&1)

# Now actually execute (this is the big test)
START=$(date +%s)
(cd "$D" && run execute --name mydeck --json > /tmp/deck-execute.json 2>&1)
EX_EXIT=$?
END=$(date +%s)
DURATION=$((END - START))

if [ $EX_EXIT -eq 0 ]; then
  ok "WWW: spear execute completed in ${DURATION}s"
else
  ko "WWW: spear execute failed (exit $EX_EXIT) in ${DURATION}s"
  cat /tmp/deck-execute.json | head -20
fi

# ---------------------------------------------------------------------------
section "XXX: pptx + JPEGs produced by render pipeline"
# ---------------------------------------------------------------------------
PPTX="$D/.spear/mydeck/output/deck.pptx"
if [ -f "$PPTX" ] && [ "$(wc -c < "$PPTX")" -gt 1024 ]; then
  ok "XXX: deck.pptx produced ($(wc -c < "$PPTX") bytes)"
else
  ko "XXX: deck.pptx missing or empty"
fi

QA_DIR="$D/.spear/mydeck/workspace/qa"
JPEG_COUNT=$(ls "$QA_DIR"/v-*.jpg 2>/dev/null | wc -l | tr -d ' ')
if [ "$JPEG_COUNT" = "3" ]; then
  ok "XXX: 3 slide JPEGs produced (one per slide)"
else
  ko "XXX: expected 3 JPEGs, got $JPEG_COUNT"
fi

# Each JPEG should be non-trivially sized
for f in "$QA_DIR"/v-*.jpg; do
  [ ! -f "$f" ] && continue
  SIZE=$(wc -c < "$f")
  if [ "$SIZE" -ge 1024 ]; then
    ok "XXX: $(basename $f) is $SIZE bytes (≥1024)"
  else
    ko "XXX: $(basename $f) is only $SIZE bytes — likely blank"
  fi
done

# ---------------------------------------------------------------------------
section "YYY: assess emits per-slide evidence with hashes"
# ---------------------------------------------------------------------------
(cd "$D" && run assess --name mydeck --json > /tmp/deck-assess.json 2>&1) || true
EVIDENCE_COUNT=$(jq '.evidence | length' /tmp/deck-assess.json)
DEFECTS=$(jq '.defects | length' /tmp/deck-assess.json)
ok "YYY: assess emitted $EVIDENCE_COUNT evidence rows, $DEFECTS defects"

# Should have one mechanical render check per slide PLUS one subjective rubric pointer per slide
PER_SLIDE_RENDER=$(jq '[.evidence[] | select(.id | startswith("deck.slide.") and contains(".render"))] | length' /tmp/deck-assess.json)
PER_SLIDE_RUBRIC=$(jq '[.evidence[] | select(.id | startswith("deck.slide.") and contains(".rubric"))] | length' /tmp/deck-assess.json)
[ "$PER_SLIDE_RENDER" = "3" ] && ok "YYY: 3 per-slide render evidence rows" || ko "YYY: $PER_SLIDE_RENDER render rows (expected 3)"
[ "$PER_SLIDE_RUBRIC" = "3" ] && ok "YYY: 3 per-slide subjective rubric pointers" || ko "YYY: $PER_SLIDE_RUBRIC rubric rows (expected 3)"

# Hashes on the artifacts
HASH_COUNT=$(jq '[.evidence[] | select(.artifactHash != null)] | length' /tmp/deck-assess.json)
[ "$HASH_COUNT" -ge 3 ] && ok "YYY: $HASH_COUNT evidence rows have artifactHash (≥3)" || ko "YYY: insufficient hashes"

# Verify one hash matches the file
FIRST_ARTIFACT=$(jq -r '[.evidence[] | select(.artifactHash != null and .artifact != null)][0]' /tmp/deck-assess.json)
F_PATH=$(echo "$FIRST_ARTIFACT" | jq -r '.artifact')
F_HASH=$(echo "$FIRST_ARTIFACT" | jq -r '.artifactHash')
ACTUAL_HASH="sha256:$(shasum -a 256 "$D/$F_PATH" 2>/dev/null | awk '{print $1}')"
if [ "$ACTUAL_HASH" = "$F_HASH" ]; then
  ok "YYY: artifact hash matches recorded value (sha256 round-trip)"
else
  ko "YYY: hash drift on $F_PATH"
fi

# ---------------------------------------------------------------------------
section "ZZZ: per-round dir contains evidence/ with copied JPEGs"
# ---------------------------------------------------------------------------
ROUND_DIR=$(ls -d "$D"/.spear/mydeck/rounds/*/ 2>/dev/null | tail -1)
if [ -n "$ROUND_DIR" ]; then
  COPIED_JPEGS=$(ls "$ROUND_DIR/evidence/"v-*.jpg 2>/dev/null | wc -l | tr -d ' ')
  [ "$COPIED_JPEGS" = "3" ] && ok "ZZZ: round dir has 3 copied JPEGs in evidence/" || ko "ZZZ: $COPIED_JPEGS copied (expected 3)"
else
  ko "ZZZ: no round dir created"
fi

# ---------------------------------------------------------------------------
echo
if [ "$FAIL" -eq 0 ]; then
  printf '\033[1;32m✓ %d/%d deck-e2e probes passed.\033[0m\n' "$PASS" "$((PASS + FAIL))"
  exit 0
else
  printf '\033[1;31m✗ %d/%d passed (%d findings):\033[0m\n' "$PASS" "$((PASS + FAIL))" "$FAIL"
  for f in "${FINDINGS[@]}"; do echo "  - $f"; done
  exit 1
fi
