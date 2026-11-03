# ASSESS: Code (a2h-c2b.13)

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Builds | yes | `./gradlew assembleDebug` is green, with no new warnings in touched files. |
| 2 | Tests pass | yes | `./gradlew test` is green at the final artifact state, re-run after the last edit. |
| 3 | Red→green archived | yes | Three tests each have a red run against pre-change behaviour and a green run after, archived under `evidence/`: the refused-traits resume, the disagreeing-pair resume, and the handled case closed on resume. |
| 4 | Pins bite | yes | SCOPE mutations 1–11 each turn at least one named test red, each archived and reverted. The authoritative runs are the grader's, against the final artifact. |
| 5 | Tool and crawler agree | yes | Three agreements are each pinned by a test that computes both sides independently. The settle verdict equals the crawler's own two-way check, `IdentitySettlement.decide` plus `matchKnownScreens`. `/check` equals the command-line check. The overview rows equal the survey rows. |
| 6 | Never a silent write | yes | Every refused settle, check and push leaves all three files byte-identical, locally and on the fake device. A settle changes only the identity block of each screen file. No crawler path changes a recorded identity's bytes across a resume. |
| 7 | Server safety | yes | Every route refuses a request without the token. The server is bound to loopback. `/settle` re-checks rather than trusting a prior check. `/done` and the idle timeout stop it. |
| 8 | Operator scope respected | yes | A resume changes nothing about an `open` case: its status, detail, pointers and bytes. Only handled cases are confirmed, closed or reopened. |
| 9 | Scope fidelity | no | Changes stay inside SCOPE's affected surface. These stay unmodified: `TraitEvaluator` semantics, `TraitProposer`, the `IdentitySettlement` decision, the policies, recognition, entry restore, settling, click and back paths, and the case-raising rules. No non-goal is pulled in. |
| 10 | Hygiene and docs | yes | No commented-out code, no added `TODO`, and no debug output. New public types and functions carry KDoc. `SKILL.md` and `documentation/` point to the process. The follow-up bead for unreadable XML is filed. |

## Lettered failure modes

A. **Never-red pin.** A "red→green" test that was not red on pre-change behaviour, or a mutation that
   no test catches.
B. **Stale green.** Evidence produced before the last edit to the artifact.
C. **Second copy of a rule.** The tool, the page or the server computes holding, uniqueness or
   agreement itself, instead of calling `TraitEvaluator`, `IdentitySettlement` or
   `CaptureIdentitySource`.
D. **Half a settle.** The `.xml`, the `.html` and the case file can end up in different states
   after any refusal, verify failure or rename failure, or the two screen files can parse to
   different identities.
E. **Clobbered device state.** A push proceeds when a device file differs from the pulled copy, or a
   refused push still wrote locally.
F. **Overwritten operator identity.** A crawler path re-stamps, replaces or reorders the bytes of a
   recorded identity: a rewrite, the root re-scan, a link, re-expansion, a confirm, a close or a
   reopen.
G. **Silent drop on resume.** A refused or disagreeing screen is skipped instead of refused. A
   handled case is closed without a live check. An open case is touched.
H. **Wrong edge on a confirm-only visit.** Confirming an already expanded screen adds, changes or
   re-queues edges on it or on its parent, or expands it again.
I. **Duplicate case on a live failure.** A handled screen that fails live opens a new
   `SETTLED_DOES_NOT_HOLD` case instead of reopening its own.
J. **Unescaped page content.** Crawl text (labels, ids, details) reaches the HTML or JavaScript of
   a served page without escaping.
K. **Mocked framework.** A test mocks Android classes instead of using `FakeHost`, synthetic trees
   and temporary folders.
L. **Non-determinism.** A verdict, the overview order or written bytes depend on file-listing
   order or on map iteration order.
M. **Off-spec deviation.** A change outside SCOPE's affected surface, or a non-goal pulled in.

## Known acceptable

- Whole-repo adapter noise under `tools/spear-cli/**`, `tmp/`, `.codex-temp/`, `.obsidian/` and other
  in-flight, untracked work. None of it was touched by this cycle.
- The page's look and its JavaScript behaviour in a real browser, and real `adb`, are judged at
  Gate 3 by hand (SCOPE). The grader judges the served HTML, the routes and the decision functions.

## Convergence

PASS when metrics 1–8 and 10 are fully met, metric 9 is judged met by an independent grader, no
failure mode is open, and metrics 1–2 have been re-run at the final state.
