---
name: oneshot
description: Kick off a research-first planning workflow for a ticket by running the Ralph research step and then launching the one-shot planning session.
---

# Oneshot

Use this skill when the user wants a ticket to move through the repo's rapid research-to-plan workflow.

## Workflow

1. Run the `ralph-research` skill for the provided ticket number.
2. Launch a new planning session that runs the `oneshot-plan` workflow for the same ticket.

## Guidance

- Preserve the ticket number consistently across both steps.
- Use the repository's preferred launch command and title conventions if they exist.

