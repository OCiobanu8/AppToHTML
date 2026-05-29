# ENG-0010 Issue 1 Full Suite Flaky Failure Root Cause

Date: 2026-05-29
Branch: `codex/issue-4-one-crawl-per-app`
Commit: `d7e09c206982c438a366c68b1ba3c9a6fa6cf266`
Repository: `OCiobanu8/AppToHTML`

## Research Question

What is the root cause of the failure described in
`thoughts/shared/validations/2026-05-19-ENG-0010-issue-1-full-suite-flaky-failure.md`?

## Summary

The failure is caused by a stale coordinator-level test assertion, not by a
current full-suite ordering dependency.

`DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`
still expects the crawl log to contain `sampleCount=10`, but the current
destination-settle dwell window is 3,000 ms. The test fake advances the
post-click settle clock by 1,000 ms per sample, so the repeated eligible
destination now deterministically produces three samples before stopping with
`fixed_dwell_exhausted`.

This matches the lower-level `DestinationSettlerTest` expectation and the
current `DeepCrawlCoordinator` default. The stale expectation appears to have
survived commit `f94f79c` (`Lower destination settle timeout`), which lowered
the default timeout and updated `DestinationSettlerTest` but did not update this
coordinator test.

## Verification

Targeted command run on 2026-05-29:

```powershell
.\gradlew.bat testDebugUnitTest --tests com.example.apptohtml.crawler.DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection
```

Result:

```text
1 test completed, 1 failed
java.lang.AssertionError at DeepCrawlCoordinatorTest.kt:1690
```

The generated test result XML confirms the failed assertion is line 1690:

```kotlin
assertTrue(crawlLogText.contains("sampleCount=10"))
```

## Detailed Findings

`DeepCrawlCoordinator` defaults post-click destination settling to 3,000 ms via
`DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS`.

The coordinator passes that value into `DestinationSettleRequest` as
`maxSettleMillis` for child destination observation and replay route settling.

`DestinationSettler` stops sampling when `elapsedMillis >= request.maxSettleMillis`
and returns `DestinationSettleStopReason.FIXED_DWELL_EXHAUSTED` when it has an
eligible sample but no earlier known-destination match.

The `DeepCrawlCoordinatorTest` fake clock advances `postClickSettleTimeMs` by
1,000 ms each time `postClickSettleTimeProvider` is called. With a 3,000 ms
settle window, that creates samples at 1,000 ms, 2,000 ms, and 3,000 ms.

The lower-level repeated-fingerprint test in `DestinationSettlerTest` already
expects this current behavior:

```kotlin
assertEquals(3_000L, result.elapsedMillis)
assertEquals(3, result.samples.size)
```

The failing coordinator test still expects the previous 10,000 ms behavior:

```kotlin
assertTrue(crawlLogText.contains("sampleCount=10"))
```

## Historical Context

Commit `91c7591` introduced the coordinator-level assertion when the default
post-click settle timeout was 10,000 ms. Under the existing fake clock, that
made `sampleCount=10` correct.

Commit `f94f79c` lowered the destination-settle timeout from 10,000 ms to
3,000 ms in `DeepCrawlCoordinator` and `DestinationSettler`. It also updated
`DestinationSettlerTest` expectations from ten samples to three samples, but it
did not update
`DeepCrawlCoordinatorTest.discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection`.

## Code References

- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:1690`
  contains the stale `sampleCount=10` assertion.
- `app/src/test/java/com/example/apptohtml/crawler/DeepCrawlCoordinatorTest.kt:3020`
  defines the fake post-click settle time provider that advances by 1,000 ms.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:26`
  wires `maxPostClickSettleMillis` to the default.
- `app/src/main/java/com/example/apptohtml/crawler/DeepCrawlCoordinator.kt:2278`
  defines `DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS = 3_000L`.
- `app/src/main/java/com/example/apptohtml/crawler/DestinationSettler.kt:65`
  stops sampling when elapsed time reaches the configured maximum.
- `app/src/test/java/com/example/apptohtml/crawler/DestinationSettlerTest.kt:209`
  and `:210` assert the current repeated-fingerprint behavior: 3,000 ms and
  three samples.

Stable repository links for the base commit:

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

## Root Cause

The root cause is an incomplete test update after the destination-settle timeout
was lowered from 10 seconds to 3 seconds. The production/test helper behavior
now produces three samples, while one coordinator-level assertion still expects
the old ten-sample dwell.

## Open Questions

- Should the coordinator test assert the exact new `sampleCount=3`, or should it
  avoid coupling to the sample count and assert the behavior-level signals:
  `stopReason=fixed_dwell_exhausted` and `sameFingerprintAsPrevious=true`?
- Should the failure be fixed as part of ENG-0010 closeout so the full-suite
  verification criterion is restored?
