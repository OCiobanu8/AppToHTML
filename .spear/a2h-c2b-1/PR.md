# Skip external packages entirely: remove the approval pause

Closes `a2h-c2b.1` (epic `a2h-c2b`; unblocks `a2h-c2b.2`).

## What changes

The deep crawler will **never** follow a click that leaves the target app.

Before, the external-package boundary was crossable on approval: the crawl paused, the operator
could choose *Continue*, and the crawler then re-opened and captured a foreign app's screen. Now a
click that lands in a foreign package is recorded `SKIPPED_EXTERNAL_PACKAGE` with the destination
package, and the crawl carries on. No prompt, no allow-list, no way to say yes.

Decided with technical stakeholders on 2026-09-08. This *strengthens* the `factory/COMPANY.md`
cross-app guardrail ("Safety over coverage") — the boundary becomes uncrossable where it was
previously crossable on approval.

**Accepted cost:** cross-package subtrees are now permanently uncapturable — sign-in handoffs,
system pickers, payment sheets.

## Why the skip is after the fact

Accessibility cannot reveal a control's destination before the click. So the boundary is detected
*post-hoc*: click, observe the package changed, decline to capture, record the edge, move on. The
crawler is briefly standing in the foreign app.

Recovery is **not new code**. Every edge already begins with `openChildFromScreen` →
`restoreLiveScreenForEdge` → `restoreToEntryScreenOrRelaunch`, whose first probe is
`currentRoot?.packageName == selectedApp.packageName`. Standing in a foreign package fails that
probe and falls through to `relaunchTargetApp`. The bead asked for this to be confirmed rather than
assumed — it is, by both a unit pin and the device run below.

## What was deleted with it

Removing the approval removed its only consumer:

- the pause, its decision context, `PauseDecision.SKIP_EDGE`, and `PauseReason.EXTERNAL_PACKAGE_BOUNDARY`
- the allow-list (`allowedPackageNames`) and everything that derived from it — `CrawlEdgeApproval`,
  `SavedCrawlLoader.allowedPackages`, `SavedCrawlRepository.AllowedPackage`, `revokeApproval`, the
  `approval` XML attribute and its parser, and the operator UI for all of it
- the post-approval re-open, and with it the **destination-compatibility matcher**
  (`DestinationSettler.compatibility` and `DestinationCompatibilityReason`) — one of three
  screen-level matchers, which had exactly one production call site

Net **−562 production LOC** (+33 / −595).

`CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE`, the edge's `externalPackage` field, and the elapsed-time
and failed-edge checkpoints all stay.

## Test plan

`./gradlew test` → **263 tests, 0 failures** · `./gradlew assembleDebug` → green.

Three new pins, all shown **RED against pre-fix code** before the change (archived):

| pin | asserts |
|---|---|
| `externalPackageClick_isSkippedAutomatically_withoutAnyPauseDecision` | zero pause decisions asked; edge is `skipped_external_package` stamped with the destination package |
| `externalPackageSkip_recoversToTargetApp_onNextEdge` | a relaunch of the target follows the skip, and the next edge captures normally |
| `noCodePath_capturesAScreenOutsideTheTargetPackage` | no screen record outside the target package; no foreign edge reaches `CAPTURED`/`LINKED_EXISTING` |

Plus `delayedExternalPackage_isSkippedAsExternal_notAsNoNavigation`, adapted from the existing
delayed-transition test: it keeps the settle-retry assertions, because settling is what stops a slow
external transition being mis-filed as `SKIPPED_NO_NAVIGATION`.

**Mutation check:** neutralising the guard turns all four red; restoring it returns the suite to
green. The four `pauseCheckpoint_*` tests are untouched apart from a forced signature change.

**18 existing tests were deleted.** Each asserted that a cross-package screen *is captured* — the
outcome this change exists to prevent. Enumerated with rationale in `.spear/a2h-c2b-1/PLAN.md`.

## Device verification

Operator ran a real crawl of `com.android.settings`. Because the crawl directory is persistent per
app, it still held two runs from the old approval build — a genuine before/after on the same target:

| | old build | this build |
|---|---|---|
| automatic skips | 0 | **4** |
| external-package pauses | 6 | **0** |
| allow-list mentions | 38 | **0** |

Nine new screens captured, **all `com.android.settings`, zero foreign**. Recovery observed directly:
after each skip, `entry_restore_attempt strategy=relaunch` → `observedPackageName="com.android.settings"`
→ `success=true`, first attempt.

Two of the four skips carried `currentPackageName == nextPackageName == com.google.android.cellbroadcastreceiver`.
Under the original comparison those would have been **captured**; they exercised — on real hardware —
the review fix described below.

Legacy XML still carrying `approval="explicit"` was hydrated cleanly by the new reader
(147 screens, 306 edges, zero parse warnings), confirming the dropped attribute is harmless.

Full write-up: `.spear/a2h-c2b-1/evidence/06-device-check.md`.

## Review findings, fixed

Two independent adversarial graders (the second tasked with attacking what the first did not, and
with verifying the first's fixes) found four defects, all fixed:

1. `expectedChildPackageName` left as a constant-`null` parameter chain after the re-open was deleted.
2. **The guard compared against `screenRecord.packageName` rather than `selectedApp.packageName`** —
   so it disagreed with the recovery probe it is meant to mirror. Now uses the target package.
3. A half-updated sentence in `documentation/crawler-module.md`.
4. `CrawlGraphHtmlRenderer` still telling operators *"User chose to stay inside the selected app."* —
   a choice that no longer exists, embedded in every generated `crawl-graph.html`.

Fix 2 ships **without a unit pin**, deliberately. The suggested regression test passed against the
*unfixed* code — replay reaches the foreign screen but the fixture's fingerprint fails validation, so
the edge never reaches the guard. Rather than ship a test that passes for the wrong reason, it was
withdrawn. It is unpinnable in a unit test by construction: for any fresh crawl the two expressions
are identical, so only a legacy saved crawl can distinguish them, and `CLAUDE.md` waives
backward compatibility for saved crawls. The device run then exercised it twice.

## Governance

`factory/PRODUCT.md` said the crawl "asks before crossing app boundaries" — now "never crosses app
boundaries". A cycle-flywheel line was added to `factory/FUNCTION.md`: retire tests by behavior, not
by name (this cycle's plan missed `bfsTraversal_allows_cross_package_child_screens`, whose name
carries no "external"; the suite caught it, the plan did not). Both approved by the maintainer.

## Follow-ups filed

- `a2h-btf` — crawl aborts when a system overlay dialog (the systemui media output switcher) stays on
  top after a skip; relaunch brings the target up *behind* it. Pre-existing: all three device runs,
  including both on the old build, end `partial_abort` identically.
- `a2h-lme` — New Crawl leaves artifacts from the previous crawl. `deleteRecursively()`'s result is
  discarded, and `CaptureFileStore.wipePackageCrawl` has zero callers.

## Rollback

Single feature branch; revert is one `git revert` of the merge. No migration, no persisted state to
unwind.
