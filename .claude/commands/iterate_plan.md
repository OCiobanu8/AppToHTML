# Iterate Implementation Plan

You are tasked with updating existing implementation plans based on user feedback. Be skeptical, thorough, and ensure changes are grounded in actual codebase reality.

## Initial Response

When invoked:
- If NO plan file provided: ask for the path
- If plan file provided but NO feedback: ask what changes to make
- If BOTH provided: proceed immediately

## Process Steps

### Step 1: Read and Understand Current Plan
Read the existing plan file COMPLETELY (no limit/offset). Understand the current structure, phases, scope, and success criteria.

### Step 2: Research If Needed
Only spawn research tasks if the changes require new technical understanding. Use parallel sub-agents (codebase-locator, codebase-analyzer) for efficiency. Be specific about directories.

### Step 3: Present Understanding and Approach
Before making changes, confirm your understanding of what changes are needed and what research found. Get user confirmation.

### Step 4: Update the Plan
- Use the Edit tool for surgical changes
- Maintain the existing structure unless explicitly changing it
- Keep all file:line references accurate
- If adding a new phase, follow existing patterns
- Maintain the distinction between automated vs manual success criteria

### Step 5: Sync and Review
- Run `humanlayer thoughts sync`
- Present a summary of changes made

## Important Guidelines

1. **Be Skeptical** - Question vague feedback, verify technical feasibility
2. **Be Surgical** - Make precise edits, not wholesale rewrites
3. **Be Thorough** - Read the entire plan before making changes
4. **No Open Questions** - Research or ask for clarification before updating
5. **Success criteria** must always distinguish between automated and manual verification
