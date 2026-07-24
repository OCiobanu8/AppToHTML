# ASSESS — Code

## Scored metrics

| # | Metric | Mechanical | What |
|---|---|---|---|
| 1 | Type-check passes | yes | tsc / mypy zero errors. |
| 2 | Tests added | yes | Every public function has a test. |
| 3 | Tests pass | yes | CI green. |
| 4 | Lint clean | yes | Zero warnings. |
| 5 | Format clean | yes | Prettier / Black clean. |
| 6 | Contract documented | no | Public functions have docstring. |
| 7 | PR description complete | no | Summary, test plan, rollback. |
| 8 | Diff size sane | yes | ≤ SCOPE cap. |
| 9 | No leftover debug | yes | No `console.log`, `debugger`. |
| 10 | Migration safe | no | Forward + backward compat OR explicit breaking. |

## Lettered failure modes

A. **`any` type** — escape hatches.
B. **Mocked test for real code** — passes only because mocked.
C. **Missing edge case** — null, empty, error path uncovered.
D. **Public function without contract** — exported without docstring.
E. **Snapshot test churn** — large unreviewed snapshot diffs.
F. **Hidden side effect** — pure-named function writes to disk/network.
G. **Off-spec deviation** — implementation diverges from SCOPE.
H. **Race condition** — shared state without lock.
I. **N+1 / unbounded loop** — query inside loop, fetch-all without pagination.
J. **String-typed enum** — magic strings.
K. **Leaked credential** — API key in code/log/test.
L. **Circular dependency** — A imports B imports A.

## Convergence

PASS when all 10 metrics 10/10 + zero failure modes + CI green.
