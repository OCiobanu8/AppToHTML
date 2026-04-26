---
name: ralph-plan
description: Select or load a small high-priority Linear ticket ready for specification, create or validate its implementation plan, sync the artifact, and move the ticket into plan review.
---

# Ralph Plan

Use this skill when the repo workflow assigns planning from the highest-priority small Linear issue.

## Ticket Selection

If a ticket was provided:

1. Fetch it into `thoughts/shared/tickets/ENG-xxxx.md`.
2. Read the ticket and comments fully.

If no ticket was provided:

1. Use the `linear` skill to understand workflow rules.
2. Fetch the top ready-for-spec tickets.
3. Select the highest-priority `SMALL` or `XS` issue.
4. If none exist, stop and explain.
5. Fetch the selected ticket into `thoughts/shared/tickets/ENG-xxxx.md`.
6. Read the ticket and comments fully.

## Planning Workflow

1. Move the ticket to `plan in progress`.
2. Check the ticket links for an existing implementation plan.
3. If a good plan already exists, stop after reporting that status.
4. If a plan does not exist or the research is insufficient, run the `create-plan` skill and produce the plan artifact.
5. Sync the thoughts repository.
6. Attach the plan to the ticket and add a short comment with the link.
7. Move the ticket to `plan in review`.

## Finish

Report:

- Ticket identifier and title
- Plan path
- Status updates applied
- High-level implementation phases

