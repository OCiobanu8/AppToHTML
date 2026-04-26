---
description: Validate implementation against plan, verify success criteria, identify issues
---

# Validate Plan

You are tasked with validating that an implementation plan was correctly executed, verifying all success criteria and identifying any deviations or issues.

## Initial Setup

When invoked:
1. **Determine context** — are you in an existing conversation or starting fresh?
2. **Locate the plan** — use provided path, or search recent commits/ask user
3. **Gather implementation evidence**:
   ```bash
   git log --oneline -n 20
   git diff HEAD~N..HEAD
   ./gradlew test  # or appropriate test command
   ```

## Validation Process

### Step 1: Context Discovery
1. Read the implementation plan completely
2. Identify what should have changed (files, success criteria)
3. Spawn parallel research tasks to discover what was actually implemented

### Step 2: Systematic Validation
For each phase in the plan:
1. Check completion status (look for checkmarks `- [x]`)
2. Run automated verification commands — document pass/fail
3. Assess manual criteria — list what needs manual testing
4. Think deeply about edge cases

### Step 3: Generate Validation Report

```markdown
## Validation Report: [Plan Name]

### Implementation Status
✓ Phase 1: [Name] - Fully implemented
⚠️ Phase 2: [Name] - Partially implemented (see issues)

### Automated Verification Results
✓ Tests pass
✗ Linting issues (3 warnings)

### Code Review Findings
#### Matches Plan: [list]
#### Deviations from Plan: [list]
#### Potential Issues: [list]

### Manual Testing Required
- [ ] [step 1]
- [ ] [step 2]

### Recommendations
[actionable next steps]
```

## Validation Checklist

- [ ] All phases marked complete are actually done
- [ ] Automated tests pass
- [ ] Code follows existing patterns
- [ ] No regressions introduced
- [ ] Error handling is robust

## Recommended Workflow

1. `/implement_plan` — execute the implementation
2. `/commit` — create atomic commits
3. `/validate_plan` — verify correctness
4. `/describe_pr` — generate PR description
