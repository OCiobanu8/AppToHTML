---
name: ralph-research
description: Select or load a small high-priority Linear ticket needing research, investigate the codebase and linked context, document findings, and move the ticket into research review.
---

# Ralph Research

Use this skill when the repo workflow assigns research from the highest-priority small Linear issue in research-needed status.

## Ticket Selection

If a ticket was provided:

1. Fetch it into `thoughts/shared/tickets/ENG-xxxx.md`.
2. Read the ticket and comments fully.

If no ticket was provided:

1. Use the `linear` skill to understand workflow rules.
2. Fetch the top research-needed tickets.
3. Select the highest-priority `SMALL` or `XS` issue.
4. If none exist, stop and explain.
5. Fetch the selected ticket into `thoughts/shared/tickets/ENG-xxxx.md`.
6. Read the ticket and comments fully.

## Research Workflow

1. Move the ticket to `research in progress`.
2. Read linked documents from the ticket when present.
3. If the request is underspecified, add a clarification comment and move the ticket back to `research needed`.
4. Run the `research-codebase` skill for the internal codebase investigation.
5. If the ticket explicitly needs outside context, perform web research as part of the investigation.
6. Document the findings in:
   `thoughts/shared/research/YYYY-MM-DD-ENG-XXXX-description.md`
7. Run `humanlayer thoughts sync`.
8. Attach the research artifact to the ticket.
9. Add a concise findings comment.
10. Move the ticket to `research in review`.

## Guidance

- Document the codebase and constraints as they exist today.
- Focus on useful findings rather than prematurely designing the final implementation.

