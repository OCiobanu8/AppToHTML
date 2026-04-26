# Linear - Ticket Management

Guide for managing Linear tickets via MCP tools.

## Core Workflow

The team follows a multi-stage process from "Triage" through "Done," with emphasis on plan review before development to minimize rework.

## Key Requirements

**Problem-First Approach**: All tickets must articulate "the problem you're trying to solve from a user perspective" — implementation details alone are insufficient.

**Default Settings**:
- Status: Triage
- Priority: Medium (3)
- Project: M U L T I C L A U D E

**Automatic Labels**: Apply based on content — `hld` for daemon work, `wui` for web UI, `meta` for tooling (mutually exclusive with hld/wui).

## Creating Tickets

Process:
1. Locate source material
2. Analyze content
3. Retrieve Linear context
4. Draft a summary for user approval
5. Create the ticket with appropriate metadata

Use GitHub links via the `links` parameter rather than markdown alone.

## Comment Quality Standards

Focus on "key insights over summaries" — highlight critical understanding, decisions, resolved blockers, and state changes rather than mechanical recitations of changes made.
