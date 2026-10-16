# The crawler proposes a screen's identity, settles it only if it holds, and pauses on a case when it doesn't

Bead `a2h-c2b.3` · epic `a2h-c2b` · targets `epic/structured-screen-identity` (not `main`).
Folds in `a2h-c2b.10` (identities compare traits as an order-free set).

## Summary

Until now, every captured screen carried an identity with no traits. That made every identity
*unsettled*, so the identity work of `a2h-c2b.4` and `a2h-u68` never decided anything in a crawl.
Dedup was still an exact lookup on the screen's name. It merged two different screens that shared a
STRONG name, and it could never link a screen with a WEAK name.

With this change, every new screen goes through the lifecycle the redesign brief describes:

- **Propose.** The crawler proposes traits from the screen's first viewport. It uses only structure
  and resource ids: lists whose rows repeat a shape, and controls that carry an id. It never uses
  id-less instances, which is where all the churn in the `a2h-u68` evidence was.
- **Settle.** A proposal is kept only if it **holds**: it matches its own screen and no other screen
  the crawl knows. The check runs both ways, with the roles reversed for the second direction.
- **Recognise.** Dedup runs the known identities against the screen. Exactly one match is a link. More
  than one is a uniqueness case and **never a merge**. A WEAK-named screen is never linked.
- **Pause.** When an identity does not hold, the crawl **pauses on a named operator case**. The pause
  card names the case, the screen and why. Continue carries on with the case left open. Stop saves the
  crawl so the case can be settled offline and the crawl resumed.

## What changed

- **New decisions, each pure and pinned on its own:**
  - `TraitProposer`: what a new screen's identity asserts.
  - `IdentitySettlement`: whether the proposal holds, and if not, why.
  - `ScreenRecognition`: link, new, or collision.

  Every one of these judgements comes from `TraitEvaluator`. None of them re-derives a predicate.
- **Cases live in one file.** `identity-cases.xml` in the crawl folder holds each case's id, status,
  kind, every screen it involves, its detail and the rejected proposal.
  - Each involved screen's XML carries an `<identity-case ref>` pointer. Pointers are derived from the
    open cases on every write, so they cannot disagree with the file.
  - The loader is strict. It refuses a malformed case file, a dangling or stale pointer, or a case that
    names a missing screen, and it names the file.
  - The saved-crawl panel shows that error rather than crashing.
- **The name index is gone.** `dedupKeyToScreenId` and `findScreenIdByIdentity` were deleted.
  - Unsettled screens keep the old STRONG-name rule, and only they do (scope call C).
  - A crawl saved before this change therefore links the screens it already holds exactly as before.
- **Replay arrival and the resumed root.** A settled identity that no longer holds there opens a case
  before the existing recovery path runs.
- **One tree source, one text rule.**
  - The uniqueness check reads known screens' first viewports back from their saved XML. It re-reads a
    screen whenever that screen's XML is rewritten.
  - Text XML cannot carry is made representable in one place, `xmlRepresentable`, at the device
    boundary: every tree the coordinator receives, and the live click attributes. A fresh crawl and a
    resumed one therefore judge the same labels.
  - Line breaks and tabs in labels now survive a save.
- **The validation tool** reads the same screen set as the crawler. The case file is not a screen.

## Device verification (Gate 3, 2026-10-16, emulator)

| | Settings | Maps |
|---|---|---|
| Screens captured | 18 | 6 |
| Settled with machine-proposed traits | 16 | 4 |
| Cases | 2 (Battery, SIMs: nothing stable to assert) | 2: "asserts nothing", and a two-way uniqueness catch between two views of one place card |
| Revisit linked through traits | none reached | yes (Restaurants) |
| `validate-screen-identity` survey | HOLDS 16 · UNSETTLED 2 | HOLDS 4 · UNSETTLED 2 |
| Continue / Stop at a pause | both, as specified | both, as specified |
| Stopped crawl reloads for resume | yes; cases and pointers intact | yes |

## Test plan

- `./gradlew test`: exit 0, **540 tests, 0 failed, 2 skipped** (baseline 436).
- `./gradlew assembleDebug`: exit 0, **0 warnings**.
- **Red→green pins**, each run first against pre-change behaviour placed in the new seams:
  - two same-STRONG-name screens told apart by traits are not merged;
  - WEAK lookalikes are never merged and raise a case;
  - a replay trait failure pauses before recovery;
  - trait-order-free equality.
- **Six independent adversarial grading rounds**, each by a fresh grader and stricter than the last.
  Rounds 1–5 failed and every finding was fixed or filed. Round 6 passed: every ASSESS metric
  satisfied and failure modes A–O closed. Every vacuous pin a grader found was strengthened until its
  mutation turned red.

## Known limits, filed

- `a2h-c2b.15` (P1, **blocks `a2h-c2b.13`**). Stop mid-expansion loses the unvisited sibling edges on
  resume. This pre-dates the change, but Stop is now the normal operator path. Reproduced on device.
- `a2h-c2b.18` (P2). The pre-open top check needs an exact element set, so the churning Maps root lost
  8 of its 9 edges. This is the epic's per-flow tolerance item.
- `a2h-c2b.19` (P3). Some proposed id-bearing controls have instance-data labels, such as a place
  name, so each place card is its own screen.
- `a2h-c2b.17` (P3). Uniqueness is checked only when a screen settles.
- `a2h-c2b.16` (P3). The uniqueness check is quadratic in known screens.
- `a2h-c2b.14`. Settled identities do not carry over between crawls.
- `a2h-c2b.13`. The operator settles a case and resumes: origin marking, closing cases.

## Rollback

Revert the merge. No backward compatibility is required (`CLAUDE.md`). A crawl saved by this build has
an `identity-cases.xml` and traits that a reverted build ignores or treats as unsettled.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
