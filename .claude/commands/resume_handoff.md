# Resume work from a handoff document

You are tasked with resuming work from a handoff document through an interactive process.

## Initial Response

When this command is invoked:

1. **If a handoff document path was provided**:
   - Immediately read the handoff document FULLY
   - Immediately read any research or plan documents linked under `thoughts/shared/plans` or `thoughts/shared/research` (do NOT use a sub-agent)
   - Propose a course of action to the user and confirm

2. **If a ticket number (like ENG-XXXX) was provided**:
   - Run `humanlayer thoughts sync`
   - List contents of `thoughts/shared/handoffs/ENG-XXXX/`
   - If zero files: ask the user for the path
   - If one file: proceed with it
   - If multiple files: use the most recent (by `YYYY-MM-DD_HH-MM-SS` in the filename)
   - Read the handoff and linked documents fully, then propose a course of action

3. **If no parameters provided**:
   ```
   Which handoff would you like to resume from?
   Tip: /resume_handoff thoughts/shared/handoffs/ENG-XXXX/YYYY-MM-DD_HH-MM-SS_description.md
   or: /resume_handoff ENG-XXXX
   ```

## Process Steps

### Step 1: Read and Analyze Handoff
Extract all sections: Tasks, Recent changes, Learnings, Artifacts, Action items, Other notes.

### Step 2: Synthesize and Present Analysis
```
I've analyzed the handoff from [date] by [researcher]. Here's the current situation:

**Original Tasks:** [status of each]
**Key Learnings Validated:** [still valid/changed]
**Recent Changes Status:** [verified present/missing]
**Recommended Next Actions:** [based on handoff action items]

Shall I proceed with [recommended action 1]?
```

### Step 3: Create Action Plan
Use TodoWrite to convert handoff action items into a task list.

### Step 4: Begin Implementation
- Reference learnings from handoff throughout
- Apply patterns and approaches documented
- Update progress as tasks are completed

## Guidelines

1. **Validate Before Acting** — never assume handoff state matches current state
2. **Verify file references** — check that files named in the handoff still exist
3. **Leverage Handoff Wisdom** — pay special attention to the "Learnings" section
4. **Be Interactive** — present findings before starting work, get buy-in
