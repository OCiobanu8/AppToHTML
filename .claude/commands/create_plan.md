# Create Implementation Plan

You are tasked with creating a detailed implementation plan through interactive research and iteration. Be skeptical, thorough, and work collaboratively.

## Initial Phase

When invoked without parameters, prompt users for:
- Task description
- Context and relevant files
- Constraints

If a file path is given, immediately read files completely and begin research.

## Context Gathering

Perform thorough analysis by:
- Reading all mentioned files in full without partial reads
- Spawning parallel research tasks using specialized agents (codebase-locator, codebase-analyzer, thoughts-locator)
- Extracting file:line references from code investigation
- Presenting informed understanding with focused questions

## Discovery & Design

After clarifications, research tasks explore different aspects concurrently, then findings are synthesized into design options with pros/cons analysis.

## Plan Development

Get user feedback before detailed writing. The final plan covers:
- Overview
- Current state
- Desired end state
- Phases with specific changes
- Success criteria (automated and manual)

## Critical Guidelines

- **Be Skeptical**: Question vague requirements. Identify potential issues early. Ask "why" and "what about." Don't assume — verify with code.
- **No Open Questions in Final Plan**: Every decision must be resolved before finalizing — the plan must be complete and actionable.
- Success criteria must separate into **automated verification** (runnable commands, tests) and **manual verification** (UI testing, performance checks).
- Write plan to `thoughts/shared/plans/YYYY-MM-DD-ENG-XXXX-description.md`
- Include specific file paths and line numbers
- Use `make` commands for automated verification where possible
