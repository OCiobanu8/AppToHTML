# Create Implementation Plan (No Thoughts)

You are tasked with creating a detailed implementation plan through interactive research and iteration. Be skeptical, thorough, and work collaboratively. This variant does not use the `humanlayer thoughts` system.

## Initial Phase

When invoked without parameters, prompt users for:
- Task description
- Context and relevant files
- Constraints

If a file path is given, immediately read files completely and begin research.

## Context Gathering

Perform thorough analysis by:
- Reading all mentioned files in full without partial reads
- Spawning parallel research tasks to investigate the codebase
- Extracting file:line references from code investigation
- Presenting informed understanding with focused questions

## Discovery & Design

After clarifications, research tasks explore different aspects concurrently, then findings are synthesized into design options with pros/cons analysis.

## Plan Development

Get user feedback before detailed writing. Write the plan to a local file. The final plan covers:
- Overview
- Current state
- Desired end state
- Phases with specific changes
- Success criteria (automated and manual)

## Critical Guidelines

- **Be Skeptical**: Question vague requirements. Identify potential issues early. Don't assume — verify with code.
- **No Open Questions in Final Plan**: Every decision must be resolved before finalizing.
- Success criteria must separate into **automated verification** and **manual verification**.
- Include specific file paths and line numbers.
- Do NOT use `humanlayer thoughts sync` or write to `thoughts/` directory.
