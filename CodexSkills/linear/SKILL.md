---
name: linear
description: Manage Linear tickets with a problem-first workflow, consistent defaults, and concise high-signal comments.
---

# Linear

Use this skill when the user needs help creating, updating, or commenting on Linear tickets.

## Workflow Principles

- Follow the team status flow from triage through done.
- Prefer plan review before development when possible.
- Write tickets from the user's problem perspective, not just implementation details.

## Defaults

- Status: `Triage`
- Priority: `Medium (3)`
- Project: `M U L T I C L A U D E`

## Labeling

Apply the appropriate label based on content:

- `hld` for daemon work
- `wui` for web UI work
- `meta` for tooling

Use only the label that best fits when they are meant to be mutually exclusive.

## Ticket Creation

1. Find the source material.
2. Analyze the request or implementation context.
3. Retrieve the relevant Linear context.
4. Draft the ticket summary for user approval when appropriate.
5. Create the ticket with the right metadata and linked resources.

## Comment Quality

- Prefer key insights over chronological summaries.
- Highlight important decisions, blockers, resolutions, and state changes.
- Use structured links rather than burying references in prose.

