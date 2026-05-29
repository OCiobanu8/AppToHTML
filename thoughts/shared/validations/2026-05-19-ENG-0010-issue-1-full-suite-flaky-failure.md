# ENG-0010 Validation Issue 1: Full Unit Suite Still Fails

Date: 2026-05-19
Plan: `thoughts/shared/plans/2026-05-19-ENG-0010-one-crawl-per-app.md`

## Summary

The ENG-0010 implementation passes targeted verification for the new saved-crawl,
XML, loader, resume, name-freeze, and approval flows, and `assembleDebug`
compiles. The full unit test suite is still not green.

## Evidence

Command:

```powershell
.\gradlew.bat test
```

Result:

```text
188 tests completed, 1 failed
Execution failed for task ':app:testDebugUnitTest'.
```

Failing test:

```text
com.example.apptohtml.crawler.DeepCrawlCoordinatorTest
discoveryWithRepeatedEligibleFingerprint_waitsFullDwellBeforeSelection
```

Failure location:

```text
DeepCrawlCoordinatorTest.kt:1690
```

The implementation plan already calls this out as a pre-existing flaky failure
introduced before ENG-0010, but the plan's success criteria still include a full
suite pass.

## Impact

ENG-0010 cannot be cleanly closed against its stated automated success criteria
while `.\gradlew.bat test` fails, even if the failure is unrelated to the saved
crawl implementation.

This also weakens confidence in later validation because the broad regression
signal remains noisy.

## Recommended Resolution

Fix the flaky dwell-selection test or quarantine it with an explicit tracking
issue so the full suite can provide a reliable pass/fail signal again.

After the fix or quarantine, rerun:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

