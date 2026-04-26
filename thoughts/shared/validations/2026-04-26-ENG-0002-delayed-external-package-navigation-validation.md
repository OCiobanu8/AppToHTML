# ENG-0002 Delayed External Package Navigation Validation

Date: 2026-04-26
Plan: `thoughts/shared/plans/2026-04-26-ENG-0002-delayed-external-package-navigation.md`
Manual crawl: `E:\Logs\com.android.settings\crawl_20260426_161827`

## Summary

The implementation matches the delayed-navigation plan, automated tests pass, and the new manual crawl validates the original failure mode.

The `Google` edge now waits through a bounded post-click observation window. In the manual crawl, attempt 1 still captured `com.android.settings` and returned `result=unchanged_retry`; attempt 2 captured `com.google.android.gms` and returned `result=changed`. The edge then entered the existing external-package boundary flow, accepted `Continue outside package`, restored the external destination, and saved the Google Services child artifact.

No new blocking issue was found.

## Automated Verification

Command:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\octak\AndroidStudioProjects\AppToHTML\.gradle-user'
.\gradlew.bat test
```

Result:

```text
BUILD SUCCESSFUL in 9s
24 actionable tasks: 4 executed, 20 up-to-date
```

## Implementation Status By Phase

- Phase 1: complete. `openChildFromScreen(...)` now uses bounded post-click destination observation with up to four captures.
- Phase 2: complete. No-navigation classification still lives in `expandScreen(...)`, but now receives the observed child root.
- Phase 3: complete. Logs include `child_destination_observe_attempt` and `child_destination_observe_result` with package, fingerprint, trigger, and result fields.
- Phase 4: complete. Unit coverage includes delayed initial external foreground behavior.
- Phase 5: complete. Unit coverage includes expected-package restore retry and missing-package failure behavior.
- Phase 6: complete. Manual crawl `crawl_20260426_161827` satisfies the acceptance checks.

## Manual Crawl Evidence

Important `Google` edge log sequence:

```text
16:18:35.041 child_destination_observe_attempt ... triggerLabel="Google" attempt=1/4 expectedPackageName=""
16:18:35.400 child_destination_observe_result ... triggerLabel="Google" attempt=1/4 actualPackageName="com.android.settings" result=unchanged_retry
16:18:35.401 child_destination_observe_attempt ... triggerLabel="Google" attempt=2/4 expectedPackageName=""
16:18:35.786 child_destination_observe_result ... triggerLabel="Google" attempt=2/4 actualPackageName="com.google.android.gms" result=changed
16:18:35.799 crawl_pause reason=external_package_boundary ... nextPackageName="com.google.android.gms" triggerLabel="Google"
16:18:39.193 external_package_accepted ... triggerLabel="Google" nextPackageName="com.google.android.gms"
16:18:39.193 external_boundary_restore_attempt ... triggerLabel="Google" nextPackageName="com.google.android.gms"
16:18:41.891 external_boundary_restore_result ... actualPackageName="com.google.android.gms" destinationFingerprintMatched=true
```

Artifact evidence:

- `crawl-index.json` includes `screen_001`, `screenName: "Google services"`, `packageName: "com.google.android.gms"`.
- The `Google` route step records `expectedPackageName: "com.google.android.gms"`.
- `001_child_google_services.xml` and `001_child_google_services_merged_accessibility.xml` contain `com.google.android.gms`.
- The same Google Services XML files do not contain `package="com.example.apptohtml"`.
- The `Google` edge is captured as the child screen route; observed `skipped_no_navigation` entries belong to other child edges, such as `Recommended` under `screen_001`.

## Deviations

None found for the delayed external package navigation plan.

## Remaining Manual Testing

No additional manual testing is required for Phase 6. Future device sweeps should still watch for other external-package rows with different timing or intermediate loading screens, but this crawl validates the targeted Google edge regression.
