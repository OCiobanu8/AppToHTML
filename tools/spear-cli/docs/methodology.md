# SPEAR Methodology

> The long-form essay. Read the [blog post](https://ryanwaliany.com/posts/09-spear) for the punchier version.

## The problem SPEAR solves

Across a dozen AI assistants tested in 2024–2025, the same failure repeats. The system understands the request. It generates a reasonable plan. It executes the first few steps. And then it stops short of completion. The first 80% looks impressive. The last 20% is where trust breaks, and the user quietly inherits a cleanup job.

That last mile is usually not a model limitation. It's a **protocol limitation**. These systems were never designed to verify their own work, identify gaps, and iterate to completion.

The fix is not a better model. The fix is a better operating protocol.

## The five phases

SPEAR borrows from standard project management — initiate, plan, execute, monitor, close — and compresses those stages into seconds.

### 1. Scope (initiation, compressed)

Define the goal. Flag ambiguities. **Ask instead of guess.** If the system doesn't know which record to update, which date to use, or which source is authoritative, it should surface that immediately. That one step prevents a surprising number of avoidable errors.

### 2. Plan (planning, compressed)

The assistant lays out ordered actions, mapped to specific operations (API calls, file writes, render commands), and shows the user what it expects to produce at each step before it acts. Visibility matters. It gives the user a chance to catch a wrong assumption before the system writes bad data into 12 fields or books the wrong meeting.

### 3. Execute (the work)

Call APIs. Record results. Continue when failures are non-dependent. Improvise within constraints.

This is where AI is already fast. But speed in execution doesn't help if the system never checks whether it actually finished.

### 4. Assess (the phase most assistants skip)

Evaluate results against the original scope and the completion criteria from Plan. **Read the output, not the diff.** If the artifact is rendered (deck → JPEGs, blog → HTML, code → CI), look at the rendered output.

Without Assess, partial work looks like complete work, and that's the exact moment when the user becomes the QA engineer.

### 5. Resolve (close the gap)

If there are gaps, create a new plan for the remaining work only. Don't restart everything. Narrow the problem and iterate, up to N rounds (default 5–20), with diminishing scope each time.

The iteration cap matters because it keeps the system from wandering indefinitely while still giving it room to recover from missing data, flaky APIs, or ambiguous inputs.

## Why five phases, not three

Many assistants already do some version of Scope, Execute, Resolve. That sounds sufficient until things touch real systems.

**Without Plan**, irreversible mistakes happen at execution speed: wrong time selected, incomplete data submitted, existing fields overwritten because the assistant moved too quickly and assumed too much.

**Without Assess**, the assistant produces something that looks finished but isn't: a record is created but missing 2 required fields, a workflow runs but skips 1 dependency, a report returns but excludes a key segment. The user catches it later, doing QA for the AI.

Adding Plan and Assess usually costs 2–3 seconds. That small pause saves minutes — sometimes hours — of cleanup.

The pilot pre-flight checklist is the right analogy. The jet is faster than ever, but the pilot doesn't skip the checklist because the aircraft is capable. **Speed increases the need for checkpoints. It doesn't remove it.**

## The accuracy gain

In our tests, completion rates climbed sharply once Plan and Assess were added — same foundational models, same prompts, just structure around them. That result wasn't surprising. It's the same reason project management methodology exists in the first place. Capable people working without structure still make errors. Structured execution catches them earlier, when the cost of correction is low.

An unstructured assistant can look brilliant for 30 seconds and still fail at completion. A structured one closes the loop on its own work, which is what moves a system from promising to trustworthy.

## What SPEAR changes in practice

Instead of asking whether the model is smart enough, the question becomes: **does the system have enough checkpoints to finish what it starts?** Execution stops being the whole job and becomes phase 3 of 5.

When SPEAR is working, the assistant doesn't just move fast. It knows what it's trying to accomplish, shows the path, executes, checks the result, and narrows any remaining gap.

That's what turns a strong demo into a trustworthy system.

## Read next

- `design-principles.md` — the 5 design principles that make SPEAR systems composable
- `claude-code-quickstart.md` — invoke SPEAR in 60 seconds
- `extending.md` — write your own artifact templates
