---
name: ralph-impl
description: Select or load a small high-priority Linear ticket, ensure it has an implementation plan, move it into development, and launch the implementation worktree session.
---

# Ralph Implement

Use this skill when the repo workflow assigns implementation from the highest-priority small Linear issue.

## Ticket Selection

If a ticket was provided:

1. Fetch it into `thoughts/shared/tickets/ENG-xxxx.md` using the repo's Linear tooling.
2. Read the ticket and comments fully.

If no ticket was provided:

1. Use the `linear` skill to understand ticket conventions.
2. Fetch the top ready-for-dev items.
3. Select the highest-priority `SMALL` or `XS` issue.
4. If none exist, stop and tell the user.
5. Fetch the selected ticket into `thoughts/shared/tickets/ENG-xxxx.md`.
6. Read the ticket and comments fully.

## Implementation Handoff

1. Move the ticket to `in dev`.
2. Identify the linked implementation plan from the ticket links.
3. If no implementation plan exists, move the ticket back to `ready for spec` and explain why you are stopping.
4. Create the implementation worktree using the repository helper.
5. Launch the implementation session with a prompt that:
   - Executes the plan
   - Runs verification
   - Commits the changes
   - Produces the PR description
   - Comments the PR link back on the ticket

## Guidance

- Think deeply before choosing the ticket and before launching the worktree.
- Track the work with a task list.
- Only work on one ticket at a time.

