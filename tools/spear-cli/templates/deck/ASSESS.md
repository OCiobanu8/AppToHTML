# ASSESS — Deck

> The CLI reads this rubric to know which checks to run. Mechanical checks are auto-applied (`spear assess`). Subjective metrics are listed for the LLM to score.

## Scored metrics (1-10 each)

| # | Metric | Mechanical | What to check |
|---|---|---|---|
| 1 | Pyramid principle | no | Headline IS the punchline. Subhead supports. |
| 2 | Single message | no | Exactly one idea per slide. |
| 3 | Progressive disclosure | no | Each slide adds new info; no redundancy. |
| 4 | Truthfulness | no | Every number traces to NARRATIVE.md or a verified source. |
| 5 | MECE | no | Multi-component slides are mutually exclusive AND collectively exhaustive. |
| 6 | Position in arc | no | Slide fits its slot; flows from previous, sets up next. |
| 7 | Voice match | no | Tone matches SCOPE.md throughout. |
| 8 | Hierarchy & legibility | yes | Title > subhead > body. No wrap collisions. |
| 9 | Image-text balance | yes | Images at native aspect, no overlap with text. |
| 10 | Alignment | yes | Title-subhead-content rhythm consistent. 1-line title frames shrink. |

## Lettered failure modes

A. **Image-text overlap** — image rectangle intersects a text rectangle.
B. **Body wrap to 3+ lines** — chopped phrases like "12 of / 13 cold DMs / answered".
C. **Tag-body cramming** — tag stacked on body with <0.10" separation.
D. **Card empty bottom** — card has 25%+ unused space at the bottom.
E. **Off-canvas / clipped** — text frame extends past slide edge or below footer.
F. **Headline wrap orphan** — title wraps with 1-2 short words on line 2.
G. **Wrap regression after font swap** — changing font size caused new wraps.
H. **Backdrop visibility** — hero backdrop image swamped by scrim.
I. **Site / brand anchor missing** — no verbatim brand phrase appears anywhere.
J. **Image fills allocated space** — large dark padding inside image; content tiny in frame.
K. **Visual punch** — slide has hero visual occupying 40-60% of area (vs text-only).
L. **Quote treatment** — pull-quotes have leading punctuation (large drop-quote glyph).
M. **Diagram crispness** — diagrams native (sharp) vs raster PNG (soft).
N. **Empty band detection** — unfilled vertical band >0.5" between subhead and main visual.
O. **Caption-orphan wrap** — sub text wraps with 1-2 words alone on line 2.
P. **Industry jargon clustering** — multiple jargon terms in one slide.
Q. **Detail-loss-from-shortening** — tightening copy stripped meaning.
R. **Text-on-image-bottom collision** — caption overlaps image bottom band.
S. **Inconsistent caption format across grid** — some cards metric+period, others descriptor+stat.
T. **Soft-edge image boundary** — hero image hard vertical edge meets text column.
U. **Card empty bottom (specific)** — cards too tall for their content.
V. **Uneven content density across grid** — short-content cards become hollow.
W. **Meta line orphan wrap** — meta lines wrap with 2-3 items isolated.
X. **Visual rhythm mismatch** — paired cards have different cadences.
Y. **Headline-image redundancy** — headline duplicates baked-in image text.
Z. **Visible scrim banding** — soft scrim becomes a darkening band at high render fidelity.
AA. **Vertical breathing inconsistency** — gaps between sections inconsistent.
BB. **Subhead echoes headline** — subhead repeats the headline.
CC. **Bullet-stretch distribution** — short bullet lists stretched with mid-card gaps.
DD. **Tagline parsing ambiguity** — phrase has multiple readings.
EE. **Asymmetric content density across same-pattern cards** — cards' content lengths vary.
FF. **Cross-slide phrase echo** — same phrase on multiple slides.
GG. **Industry jargon clustering** — see P.
HH. **Mixed caption format within a grid** — see S.
II. **End-punctuation inconsistency** — fragments have periods on some slides not others.
JJ. **1-line title frame doesn't adapt** — title bottoms-out near y=2.40 with empty space above.

## Convergence

PASS when every slide is 10/10 across all 10 metrics AND no failure modes hit.

When the CLI runs `spear assess`, it scores the mechanical checks deterministically and emits a rubric-stub defect for each slide so the LLM can score the subjective ones.
