---
name: oneshot-plan
description: Execute the Ralph planning flow for a ticket and then hand off directly into the Ralph implementation flow.
---

# Oneshot Plan

Use this skill when a ticket should move directly from planning into implementation.

## Workflow

1. Run the `ralph-plan` skill for the given ticket number.
2. Run the `ralph-impl` skill for the same ticket after planning is complete.

