---
name: copy-latest-crawl
description: Copy the newest Android AppToHTML crawl session from a connected device to a local folder. Use when the user wants to pull the latest `crawl_yyyyMMdd_HHmmss[_n]` result to a Windows path such as `E:\Logs`, export a crawl run from device storage, or automate repeated crawl-folder backups.
---

# Copy Latest Crawl

Use the bundled PowerShell script to export the newest crawl session from the connected Android device.

## Workflow

1. Confirm the local destination path.
2. Ask for the target package only when the latest crawl could be ambiguous.
3. Run `scripts/copy-latest-crawl.ps1`.
4. Report the copied session id, target package, and local destination.

## Command

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\copy-latest-crawl.ps1 -DestinationRoot 'E:\Logs' -TargetPackage 'com.android.settings'
```

## Notes

- The script defaults the AppToHTML package to `com.example.apptohtml`.
- The script checks both `files/html/<target-package>` and `files/crawler/html/<target-package>` because local builds may use either layout.
- If `adb` is not on `PATH`, the script tries `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, and the default Windows SDK location.
- When the destination is outside the current workspace, request approval before running the script.
