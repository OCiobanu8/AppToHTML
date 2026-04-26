# Iterate Implementation Plan (No Thoughts)

You are tasked with updating existing implementation plans based on user feedback. This variant does not use the `humanlayer thoughts` system.

## Initial Response

When invoked:
- If NO plan file provided: ask for the path
- If plan file provided but NO feedback: ask what changes to make
- If BOTH provided: proceed immediately

## Process Steps

### Step 1: Read and Understand Current Plan
Read the existing plan file COMPLETELY (no limit/offset).

### Step 2: Research If Needed
Only spawn research tasks if the changes require new technical understanding.

### Step 3: Present Understanding
Confirm your understanding before making changes. Get user confirmation.

### Step 4: Update the Plan
- Use the Edit tool for surgical changes
- Maintain the existing structure unless explicitly changing it
- Keep all file:line references accurate
- Maintain the distinction between automated vs manual success criteria

### Step 5: Review
Present a summary of changes made. Do NOT run `humanlayer thoughts sync`.

## Important Guidelines

1. **Be Skeptical** - Question vague feedback, verify technical feasibility
2. **Be Surgical** - Make precise edits, not wholesale rewrites
3. **No Open Questions** - Research or ask for clarification before updating
4. **Success criteria** must always distinguish between automated and manual verification
