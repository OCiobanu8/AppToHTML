# ENG-0010 Full Suite Flaky Failure Research

Date: 2026-05-19
Branch: `codex/issue-4-one-crawl-per-app`
Commit: `d7e09c206982c438a366c68b1ba3c9a6fa6cf266`
Repository: `OCiobanu8/AppToHTML`

## Research Question

Why does the full unit suite still fail on
`DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`,
as recorded in
`thoughts/shared/validations/2026-05-19-ENG-0010-issue-1-full-suite-flaky-failure.md`?

## Summary

The failing test is reproducible as a single targeted test in the current
workspace. The failure is not dependent on full-suite ordering in the current
state: the assertion at
`app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1690`
expects the crawl log to contain `sampleCount=10`, but the current destination
settle timeout and fake test clock produce three samples for a stable repeated
destination.

The mismatch appears to be a stale coordinator-level assertion after commit
`f94f79c` (`Lower destination settle timeout`). That commit lowered destination
settling from 10,000 ms to 3,000 ms and updated `DestinationSettlerTest` to
expect three samples, but the corresponding `DeepCrawlCoordinatorTest` log
assertion still expects ten samples.

## Evidence

Targeted command:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection
```

Result:

```text
1 test completed, 1 failed
DeepCrawlCoordinatorTest > discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection FAILED
java.lang.AssertionError at DeepCrawlCoordinatorTest.kt:1690
```

Line 1690 is:

```kotlin
assertTrue(crawlLogText.contains("sampleCount=10"))
```

The rest of the test asserts that the child destination settle result is logged,
that it is for trigger label `Open B`, that the stop reason is
`fixed_dwell_exhausted`, and that at least one repeated fingerprint sample was
observed.

## Detailed Findings

`DeepCrawlCoordinator` defaults post-click destination settling to a 3,000 ms
dwell window:

- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:26`
  wires `maxPostClickSettleMillis` to `DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS`.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:2278`
  defines `DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS = 3_000L`.

`DestinationSettler` samples until elapsed time reaches the request maximum:

- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt:5`
  records `startedAt` from `request.timeProvider()`.
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt:16`
  computes each sample's elapsed time from another `timeProvider()` call.
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt:65`
  stops when `elapsedMillis >= request.maxSettleMillis`.
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt:370`
  sets the request default `maxSettleMillis = 3_000L`.

The `DeepCrawlCoordinatorTest` helper advances the post-click settle clock by
1,000 ms on every `postClickSettleTimeProvider` call:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:3021`
  increments `postClickSettleTimeMs += 1_000L`.

With the current settler flow, that means a stable discovery settle produces
samples at elapsed 1,000 ms, 2,000 ms, and 3,000 ms, then stops with
`FIXED_DWELL_EXHAUSTED`. The lower-level unit test now encodes this expectation:

- `app/src/test/java/com/example/apptohtml/crawler/DestinationSettlerTest.kt:210`
  asserts `assertEquals(3, result.samples.size)` for repeated identical eligible
  fingerprints.

The coordinator-level test still expects the old ten-sample log text:

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1690`
  asserts `crawlLogText.contains("sampleCount=10")`.

## Historical Context

Commit `91c7591` introduced both the current `DestinationSettler` behavior and
the coordinator-level log assertion that expected `sampleCount=10`. At that
time, `DeepCrawlCoordinator` used `DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS =
10_000L`, and the fake post-click settle clock advanced by 1,000 ms per
provider call, so the assertion matched the default dwell window.

Commit `f94f79c` lowered the default post-click and request settle timeout from
10,000 ms to 3,000 ms. The same commit updated `DestinationSettlerTest` expected
elapsed time and sample counts from ten to three, but it did not update
`DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`.

## Code References

- Current coordinator timeout:
  `https://github.com/OCiobanu8/AppToHTML/blob/d7e09c206982c438a366c68b1ba3c9a6fa6cf266/app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt#L2278`
- Current settler stop condition:
  `https://github.com/OCiobanu8/AppToHTML/blob/d7e09c206982c438a366c68b1ba3c9a6fa6cf266/app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt#L65`
- Current lower-level sample-count expectation:
  `https://github.com/OCiobanu8/AppToHTML/blob/d7e09c206982c438a366c68b1ba3c9a6fa6cf266/app/src/test/java/com/example/apptohtml/crawler/DestinationSettlerTest.kt#L210`
- Current failing coordinator assertion:
  `https://github.com/OCiobanu8/AppToHTML/blob/d7e09c206982c438a366c68b1ba3c9a6fa6cf266/app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt#L1690`
- Timeout-lowering commit:
  `https://github.com/OCiobanu8/AppToHTML/commit/f94f79ca78740b300fab02f7d348088a8c2e4e80`

## Open Questions

- Should the coordinator test assert the current deterministic count
  (`sampleCount=3`), or should it assert only the behavior-level signal that the
  dwell was exhausted and repeated fingerprints were observed?
- Should this issue be treated as part of ENG-0010 closeout, or handled as a
  small follow-up to restore the full-suite signal before closing ENG-0010?
