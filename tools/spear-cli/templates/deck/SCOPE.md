# SCOPE

## Goal

What artifact, by when, for whom. One sentence.

> _e.g. "A 14-slide LP deck for a Q2 fundraise that converts LPs by leading with portfolio traction."_

## Audience

Who reads / sees / uses this? What should they believe afterward?

## Inputs

- [ ] Narrative (slide-by-slide copy): `path/to/narrative.md`
- [ ] Brand: palette, fonts, voice (verbatim phrases from website)
- [ ] Assets: `workspace/deck/logos/`, `workspace/deck/images/`

## Constraints

- **Slides:** _e.g. 14_
- **Aspect:** 16:9 widescreen
- **Tools:** pptxgenjs (Node), gpt-image-2 (illustrations), PIL (circular photos), LibreOffice (render QA)

## Done means

- [ ] Every slide passes ASSESS.md rubric (10 metrics + lettered checks A–JJ)
- [ ] No image-text overlap, no orphan-wrap, no headline-image redundancy
- [ ] Voice matches brand
- [ ] `output/deck.pptx` opens in PowerPoint and Keynote

`MAX_ROUNDS = 20`
