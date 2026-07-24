# Extending SPEAR

Write your own artifact template — research reports, design specs, fundraising emails, anything with a checkable rubric.

## Anatomy of a template

A template is a directory under `skills/spear/templates/<type>/` with four canonical files:

```
my-template/
├── SCOPE.md       ← what to fill in before running
├── PLAN.md        ← (mostly inherited from _common; override the steps if needed)
├── ASSESS.md      ← the rubric — THIS is the meat
└── RESOLVE.md     ← (mostly inherited from _common)
```

When the user runs `/spear init my-template`, the contents are copied verbatim into their working directory.

## Writing a good ASSESS.md

The rubric is what makes SPEAR work. A weak rubric = weak Assess phase = weak output. Aim for:

- **10 numbered scored metrics** — high-level quality dimensions, scored 1–10 on every checkable unit (slide, section, function).
- **20+ lettered failure-mode checks** — specific bugs that have bitten you before. Lettered (A, B, C…) so they're easy to reference in RESOLVE.md.
- **Zero subjective metrics** — "is it good?" is not a metric. "Does the headline IS the punchline?" is.

### Example: how the deck rubric grew

The deck template ASSESS.md ships with 36 lettered checks (A through JJ). Each one was added BECAUSE we hit it in real iteration. The first round surfaces 5–10 generic defects. By round 10, the rubric catches subtle stuff like:

- F. Headline-image redundancy (text duplicates baked-in image content)
- Z. Visible scrim banding (soft scrim becomes a darkening band at higher render fidelity)
- JJ. 1-line title frame doesn't shrink (pushes content down unnecessarily)

When you find a new failure mode in your own template, ADD A LETTER. The rubric is a living document.

## Writing a good SCOPE.md

The user fills SCOPE.md before invoking the loop. Make it a checklist:

- **Goal** — one sentence
- **Audience** — who's the reader
- **Inputs** — paths to source-of-truth files
- **Constraints** — length, tools, deadlines, style
- **Done means** — explicit completion criteria
- **MAX_ROUNDS** — override the default if you want a tighter cap

If a SCOPE.md field is unclear, Claude will ask before planning. Don't make the user figure out which fields matter — bake the prompts in.

## Writing a good PLAN.md (template)

The base PLAN.md from `_common/` works for most templates. Override only when your artifact has specific phase structure:

- **Deck:** generate build.js → render JPEGs → score → fix
- **Blog:** outline → draft sections → image cadence → score → fix
- **Code:** stub functions → write tests → implement → run CI → fix

The PLAN.md template is a STARTING POINT. Claude rewrites the steps based on the user's SCOPE.md. So write the template as a skeleton + comments, not as the final plan.

## Where to put it

- **Personal templates:** drop into `~/.claude/skills/spear/templates/<type>/` directly
- **Sharable:** add to this repo at `skills/spear/templates/<type>/`, open a PR
- **Internal team:** fork this repo, add your templates, point your team at the fork

## Test your template

```bash
mkdir test-project && cd test-project
~/path/to/spear-cli/bin/spear init my-template
# fill in SCOPE.md
claude
/spear
```

If the loop converges and the output is what you expect, ship it.

## Patterns from existing templates

Things that work:

- **Templates extend `_common/`** — start with the base 10 metrics + 9 failure modes, add type-specific items.
- **Lettered failure modes are append-only** — once `J` is taken, never reuse it. Future readers want stable references.
- **Voice rules in SCOPE.md** — bake brand voice into the scope, don't leave it implicit. Otherwise drift.

Things that don't:

- **Subjective rubrics** — "is it elegant?" can't be enforced. "Does it match the brand voice in SCOPE.md?" can.
- **Open-ended scope fields** — "Anything else?" produces nothing. Specific prompts produce specifics.
- **Templates without a worked example** — readers don't know what good looks like.
