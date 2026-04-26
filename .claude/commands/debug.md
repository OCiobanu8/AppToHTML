---
description: Debug issues by investigating logs, database state, and git history
---

# Debug

You are tasked with helping debug issues during manual testing or implementation. This command allows you to investigate problems by examining logs, database state, and git history without editing files.

## Initial Response

When invoked WITH a plan/ticket file:
```
I'll help debug issues with [file name]. Let me understand the current state.

What specific problem are you encountering?
- What were you trying to test/implement?
- What went wrong?
- Any error messages?

I'll investigate the logs, database, and git state to help figure out what's happening.
```

When invoked WITHOUT parameters:
```
I'll help debug your current issue.

Please describe what's going wrong:
- What are you working on?
- What specific problem occurred?
- When did it last work?

I can investigate logs, database state, and recent changes to help identify the issue.
```

## Process Steps

### Step 1: Understand the Problem

After the user describes the issue:

1. **Read any provided context** (plan or ticket file)
2. **Quick state check**:
   - Current git branch and recent commits
   - Any uncommitted changes
   - When the issue started occurring

### Step 2: Investigate the Issue

Spawn parallel Task agents for efficient investigation:

- **Logs**: Find and analyze the most recent logs for errors
- **Database State**: Check the current database state if applicable
- **Git and File State**: Understand what changed recently

### Step 3: Present Findings

```markdown
## Debug Report

### What's Wrong
[Clear statement of the issue based on evidence]

### Evidence Found
[Findings from logs, database, git]

### Root Cause
[Most likely explanation based on evidence]

### Next Steps
[Specific commands or actions to try]
```

## Important Notes

- **Focus on the problem** - investigate what the user describes
- **Always require problem description** - can't debug without knowing what's wrong
- **Read files completely** - no limit/offset when reading context
- **No file editing** - pure investigation only
