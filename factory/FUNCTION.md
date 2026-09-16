# FUNCTION — Domain tier

> **Durability:** stable. The engineering craft reused across everything we build — principles,
> patterns, and processes. Reusable *how*, never a specific feature's detail. Keep file paths,
> function names, and version numbers out; those live in code.

## Testing craft

- **Test against synthetic models, not the framework.** Unit tests build data structures
  directly and assert on them; we do not mock the host platform's classes. Tests that depend on
  a live device belong to a separate, clearly-marked instrumented tier.
- **Behavior changes are pinned red→green.** A fix ships with a test that demonstrably *fails*
  on the pre-fix code and passes after. A pin that was never red proves nothing.
- **A refactor of *uncovered* code is pinned by characterization tests plus a mutation.** When
  moving or reshaping code that has no existing coverage, "the suite is still green" proves
  nothing — the suite never touched those lines. Write the characterization tests against
  pre-refactor behavior first, and show at least one injected mutation turning them red before
  trusting them. Uncovered code is the case where a green suite is most reassuring and least
  informative.
- **Prefer executable checks to inspection.** Any acceptance criterion that *can* be a
  test/build/lint check *must* be one; human inspection is the last resort.

## Design craft

- **Deterministic transforms.** Capture, merge, and dedup must be deterministic — the same input
  yields the same output. Non-determinism in a data pipeline is a defect, not a quirk.
- **Safety is a first-class filter, not an afterthought.** Anything that navigates or acts on a
  live target passes an explicit guard before it runs. The default answer to "is this action
  safe?" is *no* until proven otherwise.
- **Serialize the volatile, keep the durable.** State that can be regenerated is runtime; the
  decisions and specifications behind it are the record worth keeping.
- **Document a dependency by reference, never by restating it.** When code relies on another
  component's behavior, point to that component and pin the behavior you rely on with
  characterization tests — do not describe it in a comment or a spec. A restatement is a second,
  untested copy of a fact someone else owns: it drifts silently, and each correction tends to
  describe only the case that exposed the last error.

## What belongs in code vs a spec

- **Specs (here, and in `factory/`) hold the durable *why* and *how*** — principles and
  contracts true across refactors. **Code holds the *what*** — paths, names, thresholds, retry
  counts, timeouts. If a fact changes on the next refactor, it belongs in code.

## Review & process discipline

- **Scoped commits, never blanket adds.** Commit only the paths a unit of work touched. A
  repo-wide `git add -A` sweeps in unrelated or third-party changes and corrupts parallel work.
- **Third-party/vendored code is read-only.** We update it by re-vendoring a pinned upstream
  commit and rebuilding — never by hand-editing in place.
- **One unit of work at a time, isolated.** Concurrent efforts get isolated state (and, when they
  can collide in the working tree, isolated checkouts) so they cannot overwrite each other.
