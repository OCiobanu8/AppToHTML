---
name: pull-crawl
description: Download AppToHTML crawl results from a connected Android device to a local folder. Use when the user wants to pull, copy, export, or back up the crawl from a manual run — "get the crawl off the device", "download the latest crawl", "copy the settings crawl to E:\Logs" — or to list which target apps currently have a crawl on the device.
allowed-tools: PowerShell, Read, Glob, Grep
---

# Pull Crawl

Copies the on-device crawl output produced by a manual AppToHTML run to a local folder.

## On-device layout

Crawls live under the AppToHTML app's external files dir, one **persistent directory per
target app** — there are no per-run session folders, so every run of the same target app
appends to and updates the same `crawl/` directory:

```
/sdcard/Android/data/com.example.apptohtml/files/html/<target-package>/crawl/
```

That directory holds `crawl-index.json`, `crawl-graph.json`, `crawl-graph.html`, one
`crawl_<yyyyMMdd_HHmmss>.log` per run, and the per-screen `.html`, `.xml`, and
`_merged_accessibility.xml` files. The path is defined by `CaptureFileStore` in
`app/src/main/java/com/example/apptohtml/crawler/CaptureFileStore.kt`; re-read it if a pull
comes up empty.

## Workflow

1. Run with `-List` first when the target app is unknown or the user said "the latest crawl" —
   the crawl is per-app, so "latest" is only meaningful once you know which apps have one.
2. Confirm the local destination path with the user. When it is outside the repo (`E:\Logs`,
   `Downloads`, …), get approval before running.
3. Run the script.
4. Report the target package, local directory, screen count, and the newest run log.

## Commands

List what is on the device — copies nothing:

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/pull-crawl/scripts/pull-crawl.ps1 -List
```

Pull one target app's crawl:

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/pull-crawl/scripts/pull-crawl.ps1 -DestinationRoot 'E:\Logs' -TargetPackage 'com.android.settings'
```

Pull every target app that has a crawl:

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/pull-crawl/scripts/pull-crawl.ps1 -DestinationRoot 'E:\Logs' -All
```

## Behavior

- Default destination is `<DestinationRoot>\<target-package>\crawl-<yyyyMMdd_HHmmss>` — each
  pull is its own snapshot, so repeated pulls of the same evolving crawl never collide and an
  older copy is never lost.
- `-Mirror` writes to `<DestinationRoot>\<target-package>\crawl` instead, replacing what is
  there. The old copy is removed only after the pull succeeds.
- `-TargetPackage` may be omitted when exactly one app has a crawl; with several the script
  lists them and stops rather than guessing.
- `adb` is resolved from `PATH`, then `ANDROID_SDK_ROOT`, `ANDROID_HOME`, the repo's
  `local.properties`, then the default Windows SDK location. Override with `-AdbPath`.
- `-DeviceSerial` targets a specific device; `-AppToHtmlPackage` overrides the default
  `com.example.apptohtml`; `-RemoteHtmlRoot` overrides the whole on-device root.
- Pulls stage into a temporary directory and are moved into place only on success.
- Screen files have long names, so a deep `-DestinationRoot` can exceed the Windows 260-character
  path limit. The script checks this before pulling and names the offending path; `adb` on its own
  only reports a misleading `Not a directory`. Keep the destination short (`C:\Temp\...`, `E:\Logs`).

## Notes

- The script only reads from the device — it never deletes the on-device crawl. Wiping is done
  from the app itself (`CaptureFileStore.wipePackageCrawl`).
- `tools/codex-skills/copy-latest-crawl/` is the older Codex-format equivalent. It targets the
  obsolete `crawl_<timestamp>` session layout and will not find anything on current builds.
