# Structured screen identity with named comparison policies

Closes `a2h-c2b.2` (epic `a2h-c2b`; unblocks `a2h-u68`).

## What changes

Screens used to be identified by flat fingerprint **strings**, four variants built in different
places. Every comparison was `==` or `!=` on whichever string that call site held. A string can
only answer "same or not"; it cannot say *what* differed, and each site's tolerance was implicit in
which variant it happened to build.

Now every screen, root and child alike, has one `ScreenIdentity`, built by one builder
(`ScreenIdentity.fromRoot`):

- **package** and **root class**
- a **name half**, `ScreenNameIdentity`: screen name, title disambiguators, dedup confidence
- an **element set** of `ScreenElementIdentity`: the existing `ElementFingerprint`, unchanged,
  plus an `isBackAffordance` flag

Every live comparison goes through a named policy, and every policy returns what was **missing**
and what was **extra**:

| Site | Policy |
|---|---|
| S1 entry-screen restore | `EntryRestorePolicy`: the former `EntryScreenFingerprintMatcher` algorithm |
| S2 pre-open top validation · S4 route-step replay · S5 known-destination match | `SameScreenPolicy(countBackAffordances)` |
| S3 "the click did not navigate" · S6 changed-from-before / changed-from-top | `NavigatedAwayPolicy` |
| S7 dedup and graph linking | `DedupPolicy`: gated `keyFor` for linking, ungated `nameKey` for the name check |
| replay name check | `SameNamePolicy` |

The back-affordance flag decides which elements a policy **counts**, never whether two elements are
**equal**. The flag is derived from on-screen position, which the fingerprint deliberately ignores.

**`hints` is renamed `titleDisambiguators`** (decided at Gate 2). The old name read as "distinctive
elements on this screen"; they are the text used to tell apart screens that share a title. XML
`hint-N` → `title-disambiguator-N`; the dedup key prefix goes `v2` → `v3`. The cap stays at two.

Production **+977 / −975** across 19 files. Tests +1791 / −344 across 14.

## What was deleted

- `EntryScreenFingerprintMatcher` (absorbed as `EntryRestorePolicy`) and `ReplayFingerprintCodec`
  (the polymorphic `replayFingerprint` string)
- the duplicated back-affordance helpers, now one `BackAffordanceTokens`
  (`EntryScreenBackAffordanceDetector` stays separate, deliberately)
- the entry and geometry-entry string builder variants, and `ScreenIdentityCodec.decode` /
  `decodeContent`
- dead root recovery in `AppToHtmlAccessibilityService`: `recoverToRootAfterEdgeFailure`,
  `navigateBackToRoot`, `restoreRootToTop` (zero callers)
- the `<expected-replay fingerprint="…">` raw fallback attribute, which had no reachable input

Kept on purpose: `ElementFingerprint`'s contents, and `geometrySensitiveViewportFingerprint` for
scroll-loop detection.

## Behaviour: preserved, with two named exceptions

No tolerance changed: `2.0 / 3.0`, the `top <= 300` band, and the disambiguator cap and selection rule
are untouched. Two outcomes move, and both remove a pre-existing inconsistency instead of adding a new
rule:

1. **Entry restore builds its observed set the same way as its expected set.** Before, the expected
   side deduplicated and *then* dropped back affordances, while the observed side dropped them first
   and never deduplicated. On a screen with two identically-fingerprinted controls, one of them in the
   top band, this can move the result across the Dice boundary. Pinned by
   `ScreenIdentityCharacterizationTest.entryIdentity_dedups_before_flagging_...`.
2. **`matchedExpectedLogical`, a logged field only, now also requires the package to match.**

## Breaking changes

- **Crawls saved before this change cannot be resumed. Start a New Crawl per app.** A resumed
  pre-change screen with a `hint-N` gets a name key with no disambiguator, so the replay name check
  fails on its first expansion and the crawl ends `partial_abort`. Decided at the device check: no
  `hint-N` fallback.
- XML: `hint-*` → `title-disambiguator-*`; dedup keys are `v3:`;
  `<expected-destination-identity>` → `<expected-destination>`. Both step elements now carry
  `package`, `root-class` and `<element … back="…">`. The old element name described a name identity
  it never actually held.
- `ScreenIdentity`, `ScreenElementIdentity`, `ScreenNameIdentity`, `ElementFingerprint`,
  `ScreenDedupConfidence` and `ScreenIdentityCodec` are `public`, because they sit on the public
  `CrawlScreenRecord` and `CrawlRouteStep`. This only widens visibility.
- Debug output: log keys `topIdentity=`, `expectedIdentity=`, `destinationIdentityChanged=`;
  `crawl-index.json` prints a loaded screen's content as `"::"` where it printed `""`. Nothing reads
  either.

## Test plan

`./gradlew test` → **297 tests, 0 failures** (263 before) · `./gradlew assembleDebug` → green ·
0 compiler warnings.

- **Characterization first.** `ScreenIdentityCharacterizationTest` was written against the
  pre-refactor code, shown green there and **red under injected mutations**. It stays green after the
  refactor and still goes red under the same mutations.
- **Route-step pins run against HEAD `6e1ce3b`** in a separate worktree with zero production diff,
  green and red under mutation, so they pin the old behaviour, not the new one.
- **Every regression fix ships with a pin shown red first** (see Review).
- **Existing tests:** 12 test names from HEAD no longer exist. 7 were retired with the code they
  tested, 4 are mechanical renames keeping every assertion, and HEAD's two-way back-affordance pin is
  split into an observed-side and an expected-side pin. `EntryScreenFingerprintMatcherTest` is
  ported as `EntryRestorePolicyTest`.

## Review: eight rounds of independent adversarial grading

Each round used a fresh grader, stricter than the last. Regressions found and fixed, each with a
red-first pin:

1. S2 compared whole `ScreenIdentity` values with data-class `!=`, silently bringing the name half
   and the back flag into a check that never had them.
2. `DedupPolicy`'s confidence gate leaked into the replay name check, so weakly-titled screens failed
   replay. Fixed by splitting the gated `keyFor` from the ungated `nameKey`.
3. S4's back-affordance rule was bound to the parent screen's depth instead of the step.
4. The settle-sample richness metric measured the new flagged encoding. That would have changed which
   sample becomes the captured child; `encodeLogical` restores the old input.
5. `SameScreenPolicy` equality included the position-derived back flag.
6. A ported test dropped the argument that made it meaningful and passed on a different rule.

The graders also added pins for guards the suite never reached: root class, name mismatch, the dedup
gate, settler S5/S6, the richness flag, expected-side back affordances, a blank element class, and
root class in the richness input.

**Round 7 swept every guard the refactor rewrites: 239 mutants.** 115 killed, 37 equivalent, 79 that
also survive at HEAD (pre-existing gaps, filed as `a2h-c2b.8`), and 3 new to this change, each now
killed by its own pin. **Round 8: converged.**

## Device verification

A fresh **New Crawl** of `com.android.settings` on the emulator, running this change:

| | |
|---|---|
| screens captured | 35, with 35 distinct `v3:` keys |
| route-step replays matched | 32 |
| replay name divergences | 0 |
| screen-preparation failures | 0 |
| route-step replays rejected | 7, all one real content change |

All seven rejections are Settings → *Apps*, where one list row changed between capture and replay.
HEAD compares the same labels and rejects them too. The difference is the log now names the element:

```
- missing: |unused apps|android.widget.LinearLayout|true|false|false
+ extra:   |google|android.widget.LinearLayout|true|false|false
```

The run ended `partial_abort` on the pre-existing systemui media-output panel that survives relaunch
after an external skip (`a2h-btf`; the `a2h-c2b.1` build ends the same way). The only change after
the install is one log-only line.

Write-up: `.spear/a2h-c2b-2/evidence/12-device-check-fresh-crawl.md` (local; evidence is not
committed).

## Follow-ups filed

- `a2h-c2b.4`: screen traits (lists, fixed control sets), compared against the live screen
- `a2h-c2b.5`: rename the element in `a2h-u68`'s SCOPE to match this change
- `a2h-c2b.6`: the misleading "Expected X but found X" replay message (pre-existing)
- `a2h-c2b.8`: guards with no test at HEAD either (the sweep's 79), plus the new entry-restore
  missing/extra log counts
- `a2h-c2b.9`: `documentation/crawler-module.md` still describes the old `replayFingerprint`
- `a2h-btf`: re-seen here on a fresh crawl; repro runs added to it

## Rollback

One feature branch; revert is one `git revert` of the merge. Crawls made with this build carry `v3`
keys and the new XML, so after a rollback start a New Crawl again.
