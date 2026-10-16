# SCOPE

Bead: `a2h-c2b.3` — *Propose, validate, settle: fingerprint lifecycle and operator cases* (P2,
feature; parent epic `a2h-c2b`; depends on closed `a2h-u68`, `a2h-c2b.4`, `a2h-c2b.2`)

Branch: `feat/propose-and-settle-screen-identity`, cut from `epic/structured-screen-identity` at
`0c99d87`. PR targets the epic branch, not `main`.

**Split at Gate 1 (owner decision, 2026-09-22).** The operator half — recording *who* settled an
identity, an operator-settled identity being authoritative and never overwritten, and re-validating
and closing cases on resume — moves to the new sibling bead **`a2h-c2b.13`**. This cycle builds the
machine half and the pause that hands a case to a human.

## Goal

Feature: the crawler **proposes** a trait-bearing identity for every new screen, **settles** it when
it holds (it matches its own screen, and only its own screen), recognises known screens by **running
the settled identities against the screen** instead of looking up a name, and **pauses the crawl
with a named operator case** whenever an identity does not hold or two screens both match, instead
of aborting or silently merging.

## Audience

The operator running crawls on complex third-party apps. Today a screen that no longer matches a
byte-equal string aborts the replay, and two different screens that share a name are silently merged
into one graph node. After this cycle the first becomes a pause that names the failing assertion,
and the second becomes a pause that names both screens.

Secondary: `a2h-c2b.13`, which builds the operator's settle-and-resume flow on the cases raised here.

## Why now

Every piece this needs exists, but nothing connects them:

- `a2h-c2b.4` gave an identity **traits** and `TraitEvaluator.matchKnownScreens` — zero, one or
  more-than-one known screen holds on this tree. It has **no production caller**.
- `a2h-u68` put the whole identity, traits included, in each screen's XML and HTML, loads it on
  resume, and made replay arrival check a *settled* identity's traits. **Nothing proposes traits**, so
  every identity is unsettled and that check never runs.
- Dedup is still `DedupPolicy.keyFor`: an exact name-key hash lookup, gated on a STRONG name. It
  silently merges two screens that share a name, and it can never link a WEAK-titled screen, which
  therefore gets re-captured every time it is reached.

Evidence from `a2h-u68` (`.spear/a2h-u68/evidence/12-evidence-for-c2b3/`) shapes the proposal rule:
between two crawls of Settings 40 minutes apart, 17% of screens' element sets changed, **all** churn
was on elements with no resource id, and id-bearing elements never changed. A `has-list` over the
container id and row-child ids held where the id-less rows churned. So the proposal is built from
**structure and resource ids**, never from id-less instances.

## The lifecycle this cycle builds

```
new screen reached ─► run every SETTLED known identity against its step-0 tree
                        │
      ┌─────────────────┼──────────────────────────┐
     One              None                      Ambiguous
  link to it     propose an identity         CASE: uniqueness collision
                        │                    (names every matching screen)
                 does the proposal hold?
                 ┌──────┴───────┐
                yes             no
             SETTLED       CASE: proposal does not hold
          capture, expand  (names why: asserts nothing / fails on
                            own tree / also holds on screen X)

settled screen revisited by replay ─► traits do not hold ─► CASE: settled identity does not hold
```

**Every case pauses the whole crawl** (owner decision). The screen's files are written **before**
the pause, so the operator has the capture in hand. The case itself is recorded in the crawl's one
case file, and every screen it involves carries a pointer to it (scope call F).

## Scope calls for Gate 1 — please confirm or change each

Scope calls 1–4 record the decisions taken on 2026-09-22. Calls A–G have recommendations, and the
SCOPE is written assuming them.

**1. Cases pause the whole crawl. (Decided.)** A case is a new `PauseReason`. It takes the existing
`PAUSED_FOR_DECISION` path with its two decisions. The pause screen shows the case kind, the screen,
and the detail. A known risk, stated up front: on an app where most screens have no resource ids, most
proposals assert nothing, so most new screens pause. The Gate 3 device run measures the case rate.

**2. Holds = structural proposal + two-way uniqueness. (Decided.)** A proposal holds when all of the
following are true:
(a) it asserts something present (`TraitEvaluator.verdict` ≠ `UNSETTLED`);
(b) it `HOLDS` on the screen's own step-0 tree;
(c) no settled known identity holds on this tree (the dedup step, which already answered `None`);
(d) it does not hold on any other known screen's step-0 tree. That check goes through
`TraitEvaluator.matchKnownScreens` with the roles reversed, so package and name participate exactly
as they do in dedup.

**3. No carry-over across crawls. (Decided.)** "Settled" means settled within one crawl and its
resumes, which the XML already persists. A new crawl starts with no known screens. A follow-up bead
is filed for seeding a crawl from the previous one's settled identities, and for what happens across
app versions.

**4. Split. (Decided.)** The operator settle/resume flow is `a2h-c2b.13` (see top).

**A. The proposal rule. (Recommended.)** A pure, deterministic function from a step-0 tree to traits:
- **`has-list`** for each container with a resource id that has **≥ 2 direct-child rows** carrying a
  **non-empty common set** of row-child resource ids. The schema is the intersection over those
  rows. `minRows = 1`, so a list that shrinks to one item still holds.
- **`has-control`** for each first-viewport pressable that **has a resource id**, is **not** a back
  affordance, and is **not** inside a proposed list container. The rows are data, and a back button
  identifies nothing.
- **Never** from id-less pressables, which the evidence shows is where all churn lives.
- Traits are emitted in a canonical order (list traits by container id, then controls by
  fingerprint encoding), so the same tree always yields byte-identical traits.

The exact thresholds live in code and are pinned by tests. This scope names the rule, not the numbers.

**B. Continue and Stop at a case pause. (Recommended.)** Stop is today's Stop: a partial crawl is
saved with the case recorded, to be settled offline with the validate tool and resumed. What that
resume does is `a2h-c2b.13`. **Continue** means "go on with this case left open", per kind:

| Case | Continue does |
|---|---|
| Proposal does not hold | The screen is captured **unsettled**: no traits, and the rejected proposal is recorded on the case. It is expanded as today. |
| Uniqueness collision (Ambiguous) | The screen is captured as a **new, unsettled** screen. It is **never merged** into any of the matches. |
| Settled identity does not hold at replay arrival | The existing `handlePreparationFailure` recovery, exactly as `a2h-u68` wired it. |

**C. Unsettled known screens keep today's name rule, and only they do. (Recommended.)**
`matchKnownScreens` never offers an unsettled screen as a candidate. With no fallback, a screen
continued past a case would be re-proposed, re-fail and re-pause **every** time it is reached, and be
captured again each time. So when matching returns `None`, an **unsettled** known screen with an equal
STRONG name key is linked as today, and the log names that path `linked_existing_unsettled`. A
settled screen is never matched by name alone. This fallback is the only name-based lookup left, and
it shrinks as screens get settled. A crawl resumed from before this change has only unsettled screens,
so it behaves exactly as today.
*Known cost, accepted at Gate 1:* an unsettled screen whose name is **WEAK** still gets no fallback,
because today's rule never links WEAK names. It is re-proposed, re-paused and re-captured on every
visit. The Gate 3 device run reports how often this happens.

**D. Dedup resolves through fingerprint matching. `dedupKeyToScreenId` is deleted. (Recommended.)**
The name-key map in `CrawlRunTracker` and `SavedCrawlLoader` goes. Dedup becomes
`matchKnownScreens` over the settled identities, plus the unsettled fallback in C. Two outcome
changes follow, and both are intended:
- Two STRONG-named screens that share a name but whose traits tell them apart are **no longer
  merged**. Today the name map links them.
- Two screens whose settled identities both hold are **a case, never a merge**.

*Amended at Gate 2 (owner decision, 2026-10-15). This replaces the original first bullet, "a
WEAK-titled screen whose settled identity holds uniquely can now be linked".* Traits describe a
screen's **structure**, not which instance it is. Two different Settings pages with the same weak
title (for example "Navigate up") and the same list shape propose identical traits. Linking on them
would merge the two silently, which is the sibling collapse the brief ranks worse than re-capturing.
So:
- **A WEAK-titled screen is never linked**, by traits or by name, exactly as today.
- It is still proposed for. Anything that would have matched it becomes a **uniqueness case**: a
  settled screen whose identity holds on it, or an earlier screen with the same weak name that its
  proposal also holds on. The crawl pauses and names both screens.
- *Known cost, accepted:* every visit to a WEAK-titled screen whose lookalike is already known
  pauses. That includes revisits of the same screen, because nothing can tell a revisit from a
  sibling. The Gate 3 device run reports how often this happens.

**E. The known-screen trees for check 2(d) are read from the saved XML. (Recommended.)** Each
screen's step-0 tree is read once with the existing `CaptureTreeReader.readFirstViewport` and cached
for the run. A fresh crawl and a resumed crawl therefore check uniqueness against the **same** bytes,
and nothing new has to be persisted.

**F. Where a case lives: one case file, plus pointers on the screens. (Decided at Gate 2,
2026-10-15, option B; replaces "in the screen's XML and in the manifest".)**

- **The case file is the one home.** It is `identity-cases.xml` in the crawl folder, a list of every
  case the crawl has raised:
  `<case id="case_003" status="open" kind="uniqueness-collision" raised-on="screen_00007">`.
  Each case names **every** screen it involves, with a role: raised on, or related. It also carries
  the detail and the rejected proposal, written in the existing `<traits>` syntax. Ids are sequential
  and the file is written sorted by id, so it is deterministic.
- **Pointers are references only.** Each involved screen's XML carries
  `<identity-case ref="case_003"/>`, one per **open** case that involves it, inside `<crawl>`. A screen
  can carry several. Pointers are *derived* from the case file whenever the screen's XML is rewritten,
  so they cannot disagree with it.
- **Resume reads the case file.** `SavedCrawlLoader` reads it, so open cases survive a Stop and a
  resume. A pointer naming a case the file does not hold, or a malformed case file, **fails the
  resume loudly** and names the file. A case is never dropped silently.
- **No manifest copy.** The case file is the only home.
- **Closing a case is a contract for `a2h-c2b.13`, not built here.** When a case is settled, its status
  in the case file becomes `closed`, and the case stays there as history. Its pointers are removed from
  every involved screen's XML, so a screen with a settled identity is clean. Nothing in this cycle
  closes a case. This cycle only opens, records and reloads cases.

**G. Folded in: `a2h-c2b.10` canonical trait order. (Recommended.)** Machine-proposed identities are
now persisted and compared, which is exactly the trigger that bead names. `ScreenIdentity` equality
becomes independent of trait order and duplicates. This is a small, pinned change, and it keeps a
reordered but otherwise identical proposal from reading as a different identity. **Not folded:**
`a2h-c2b.6` (the name-divergence message), `.9`, `.11` and `.12`.

## Affected surface

- **New (main):** the trait **proposer**; the **settle decision** (checks 2a–2d composed from
  `TraitEvaluator`); the **identity case** model and its `PauseReason`.
- **Modified (main):**
  - `DeepCrawlCoordinator`: the child-capture dedup site, root capture, and the replay-arrival failure
    branch.
  - `CrawlRunTracker` and `SavedCrawlLoader`: the name map is removed, and the known-screen identities
    and trees are added.
  - The screen XML writer and reader, which gain `<identity-case ref>` pointers. A new case-file store
    writes and reads `identity-cases.xml`. `SavedCrawlLoader` reads the case file, and its
    screen-XML scan excludes that file.
  - `CrawlerModels`, `CrawlerSession` and `MainActivity`, which show the case at the pause.
  - `ScreenIdentity`, for order-free trait equality (G).
- **Not modified, deliberately:**
  - `ElementFingerprint`, `TraitEvaluator`'s semantics, and every `ScreenIdentityPolicy`.
  - Entry restore, destination settling, route-step expectations and the scroll fingerprint.
  - The replay **name** rule, and the `validate-screen-identity` tool's verdicts.
- **Breaking changes:** a crawl folder gains `identity-cases.xml`, the screen XML gains
  `<identity-case ref>` pointers, and captures now carry proposed traits.
  No backward compatibility is required (`CLAUDE.md`). A crawl saved before this change loads with
  every screen unsettled and behaves as today (C).

## Inputs

- [x] Bead: `bd show a2h-c2b.3`, with its acceptance criteria and its 2026-09-18 evidence note
- [x] Owner decisions at Gate 1, 2026-09-22: pause on every case; two-way uniqueness; no carry-over;
      split to `a2h-c2b.13`
- [x] Evidence: [`.spear/a2h-u68/evidence/12-evidence-for-c2b3/README.md`](../a2h-u68/evidence/12-evidence-for-c2b3/README.md)
- [x] Redesign brief Part 5 (target model, lifecycle) and Part 6 Q2, Q2b and Q3b:
      [`thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md`](../../thoughts/shared/research/2026-08-18-fingerprint-redesign-brief.md)
- [x] Prior cycles: [`.spear/a2h-c2b-4/`](../a2h-c2b-4/) (traits, `matchKnownScreens`) and
      [`.spear/a2h-u68/`](../a2h-u68/) (identity in the artifacts, replay-arrival trait check)
- [x] Governing values: `factory/COMPANY.md` (correctness over speed; safety over coverage) and
      `factory/FUNCTION.md` (deterministic transforms; red→green; characterization plus a mutation
      for uncovered refactors)

## Constraints

- **One fact, one home.** Holding is decided only by `TraitEvaluator.verdict` and
  `matchKnownScreens`. The settle decision composes them and does not re-derive them. The proposer is
  the only place traits are generated.
- **Deterministic.** The same tree and the same known screens give the same proposal, the same
  settle decision and the same case, byte for byte, whatever order the known screens are presented in.
- **Never a silent merge, never a silent rewrite.** More than one match is always a case. A settled
  identity already on a record is never replaced by a fresh proposal.
- **Safety unchanged.** A case pause happens at the same edge boundary as the existing pauses. No new
  click, back or relaunch path is added.
- **Tests:** unit tier only, with synthetic `AccessibilityNodeSnapshot` trees, plus the real-capture
  fixtures already committed by `a2h-u68` and one new Settings list-screen capture. No Android
  framework mocks.
- **Pre-existing tests** stay unmodified, except those that pin the removed name map, the exact
  serialized XML or HTML, or name-based linking of screens that are now settled. Each such test is
  found by the behaviour it asserts (`factory/FUNCTION.md`), and listed and justified at Assess.
- **Type-check:** strict. `./gradlew assembleDebug` compiles with no new warnings.
- **PR size:** no hard cap. Expected to be large. Not a graded metric.

## Done means

### Machine-checked (graded in the Assess loop)

**Build**
- [ ] `./gradlew test` green at the final artifact state.
- [ ] `./gradlew assembleDebug` green at the final artifact state.

**Proposal (A)**
- [ ] A test asserts that a cart-shaped tree is proposed with `has-list(cart_items, 1,
      {title, price, quantity})` plus `has-control` for its id-bearing checkout button. It is **not**
      proposed with a control for any id-less row, for the back affordance, or for a pressable inside
      the list.
- [ ] A test asserts that a tree whose pressables all lack resource ids, and which has no qualifying
      list, is proposed with **no** traits.
- [ ] A test asserts that the proposal is deterministic: two trees with the same structure and nodes
      in a different child order propose byte-identical traits.
- [ ] A test asserts that the Settings evidence shape holds. A `network_internet` real capture, taken
      from the newest identity-carrying crawl under `E:\Logs\com.android.settings\` and **committed
      as a fixture in this cycle**, is proposed with a `has-list` over `recycler_view` whose schema includes
      `android:id/title`, and that proposal **holds** on the fixture itself.

**Holds, two-way (2)**
- [ ] Tests assert each settle outcome with its reason: settled; not settled because it **asserts
      nothing**; not settled because it **fails on its own tree** (forced by an injected proposer);
      not settled because it **also holds on known screen X**, naming X.
- [ ] A test asserts that check 2(d) runs through `matchKnownScreens` with the roles reversed. A
      same-traits screen with a **different** name is not a collision, and one with the **same** name
      is.

**Dedup through fingerprints (C, D)**
- [ ] A test asserts that `One` links to the matching screen, `None` goes on to propose, and
      `Ambiguous` raises a uniqueness case that lists **every** matching screen id and merges nothing.
- [ ] **Red→green:** a test asserts that two screens with the **same STRONG name** but traits that
      tell them apart are **not merged**. It **fails on pre-change code**, where the name map links
      them. Both runs are archived.
- [ ] **Red→green (amended at Gate 2):** a test asserts that two **WEAK-titled** lookalike screens
      (same weak name, same proposed traits) are **never merged**, and that the second raises a
      **uniqueness case** naming the first. It **fails on pre-change code**, which captures the second
      silently with no case. Both runs are archived.
- [ ] A test asserts that a WEAK-titled observed screen is never linked, even when exactly one
      settled identity holds on it. That `One` becomes a uniqueness case instead.
- [ ] A test asserts the unsettled fallback (C): an unsettled known screen with an equal STRONG name
      is linked and logged `linked_existing_unsettled`, and a **settled** known screen is never
      linked by name alone.
- [ ] Grep: `dedupKeyToScreenId` no longer appears under `app/src/main`, and `DedupPolicy.keyFor` has
      no caller left on the dedup path.

**Cases pause the crawl (1, B, F)**
- [ ] A test through the coordinator's host seam asserts that each case kind (proposal does not
      hold, uniqueness collision, settled identity does not hold at replay) calls
      the identity-case pause (`awaitIdentityCaseDecision`, per PLAN fact 5) with the case detail,
      **after** the screen's files, the case file entry and the pointers are written.
- [ ] Tests assert **Continue** does what scope call B's table says for each kind, and that
      **Stop** saves a partial crawl whose `identity-cases.xml` holds the open case, and every
      involved screen's XML points to it.
- [ ] A test asserts that a collision case names **every** involved screen in the case file, and that
      **each** of those screens' XML carries the pointer. A screen involved in two cases carries two.
- [ ] A test asserts that the case file round-trips (write → `SavedCrawlLoader` → rewrite
      byte-identical, case file and pointers both), so an open case survives a Stop and a resume.
- [ ] A test asserts that a screen with no open case gets **no** pointer, and that pre-existing XML
      pins stay byte-identical.
- [ ] A test asserts that the pointers are derived from the case file: marking a case `closed` in the
      tracker removes its pointer from every involved screen on the next rewrite, and keeps the case
      in the file as history. This pins the `a2h-c2b.13` contract. The test drives the tracker
      directly, because nothing in production closes a case yet.
- [ ] Tests assert that resume **fails loudly, naming the file**, on a malformed case file and on a
      pointer to a case the file does not hold. It never drops a case silently.
- [ ] A test asserts that the screen loader does not treat `identity-cases.xml` as a screen.
- [ ] A test asserts that a replay-arrival trait failure raises the case, and does **not** go
      straight to recovery without the pause. It **fails on pre-change code**, which goes straight to
      recovery. Both runs are archived.
- [ ] A test asserts that a crawl whose screens are all **unsettled** (a pre-change save) takes no
      identity-case pause and links exactly as the name rule did.

**Determinism and precedence**
- [ ] A test asserts that the same trees, presented to the coordinator's decision seams in two
      different known-screen orders, produce identical settle decisions, identical cases and
      identical link targets.
- [ ] A test asserts that a record which already carries a settled identity keeps that identity. A
      later visit never replaces it with a fresh proposal, even when the fresh one differs.
- [ ] **G:** a test asserts that identities whose trait lists differ only in order or duplicates are
      equal and hash equal.

**Pins bite (each mutation applied, shown red, archived, reverted)**
- [ ] (1) The settle decision skips check 2(d). (2) `Ambiguous` is treated as `One` (the first id).
      (3) The proposer emits `has-control` for id-less pressables. (4) The unsettled fallback also
      applies to settled screens. (5) Continue after a uniqueness case links instead of capturing.
      (6) A WEAK-titled observed screen is linked on `One`.

**Hygiene**
- [ ] No commented-out code, no `TODO` added, no debug output. Every new public type and function has
      a KDoc contract.
- [ ] `documentation/crawler-module.md` and `documentation/data-and-state.md` point to the lifecycle
      and to `identity-cases.xml`, by reference and not by restatement.
- [ ] The follow-up bead for carry-over across crawls and app versions (scope call 3) is filed.

### Human-verified at Gate 3 (real device; evidence under `.spear/a2h-c2b-3/evidence/`)

- [ ] A fresh Settings crawl on the rebuilt app. Report its screens settled, cases raised by kind,
      links through settled identities, and links through the unsettled fallback. Show that the XML of
      a settled screen carries machine-proposed traits that the `validate-screen-identity` tool
      reports as **HOLDS**.
- [ ] A case pause seen on device. The pause screen names the case kind and the screen, Continue
      proceeds per scope call B, and Stop leaves a partial crawl whose `identity-cases.xml` holds the
      case, with a pointer in every involved screen's XML.
- [ ] A dense third-party app (the Maps target from `a2h-u68`, or your pick). Report the case rate,
      against the risk named in scope call 1.

## Non-goals

- **`a2h-c2b.13`:** recording who settled an identity; operator authority over it; closing a case on
  resume; the settle workflow end to end.
- Carrying settled identities across crawls or app versions (scope call 3; follow-up bead).
- Queueing cases while the crawl continues (rejected at Gate 1 in favour of pausing).
- Changing `TraitEvaluator`, any `ScreenIdentityPolicy`, entry restore, destination settling,
  route-step expectations, or any tolerance.
- A discriminating-power score or threshold on proposals.
- `a2h-c2b.6`, `.9`, `.11`, `.12`, and `a2h-9gj` (the weak-identity diagnostic).

`MAX_ROUNDS = 10`

Rounds are Assess/fix iterations: a circuit breaker, not a budget. If the same defect survives two
consecutive rounds, stop and escalate whatever the number of rounds remaining.
