---
name: capture-screen
description: Capture the screen currently visible on a connected Android device or emulator as crawl-quality HTML and XML, then pull it locally. Use when the user wants a snapshot of whatever is on screen right now — "capture the current screen", "grab this screen as HTML", "snapshot the cart page", "dump the visible screen" — especially for screens reachable only by manual navigation (logged-in states, dialogs, deep flows) that a crawl cannot reach.
allowed-tools: PowerShell, Read, Glob, Grep
---

# Capture Screen

Fires one ADB broadcast at the AppToHTML accessibility service, waits for it to write the
artifacts, and pulls them locally. Round trip is a few seconds with **no rebuild**.

## When to use this instead of a crawl

A crawl always launches the target app, back-navigates to its entry screen, and foregrounds
AppToHTML when it finishes. Each of those destroys whatever screen the operator navigated to, so
any state reached by hand is uncapturable that way. This skill captures the foreground screen in
place: the target app never loses focus and AppToHTML never comes forward.

Use `pull-crawl` instead when the user wants the results of a full multi-screen crawl.

## Prerequisites

- AppToHTML installed and its accessibility service enabled. **No target app needs to be
  selected** — the capture attributes itself to whatever package owns the foreground window.
- The screen you want must already be visible on the device.

The script pre-checks the accessibility service and fails in ~2 s with a specific message if it is
disabled, rather than timing out.

## Workflow

1. Confirm with the user that the device is showing the screen they want. This is the one thing
   the script cannot verify.
2. Confirm the local destination. When it is outside the repo (`E:\Logs`, `Downloads`, …), get
   approval before running.
3. Run the script.
4. Report the package, screen name, element count, scroll steps, and the local paths.

## Commands

Capture and pull:

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/capture-screen/scripts/capture-screen.ps1 -DestinationRoot 'E:\Logs'
```

Capture the visible viewport only — no scrolling, no merge (faster, but off-screen content is
missing):

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/capture-screen/scripts/capture-screen.ps1 -DestinationRoot 'E:\Logs' -NoScroll
```

Give the output folder a specific label:

```powershell
powershell -ExecutionPolicy Bypass -File .claude/skills/capture-screen/scripts/capture-screen.ps1 -DestinationRoot 'E:\Logs' -Name 'empty-cart'
```

## What the operator sees on the device

Nothing takes focus. The one visible side effect is the scroll scan: the screen rewinds to top,
walks down through the scrollable content, then rewinds to top again. A toast reports the result.

**The scan does not restore the screen's original scroll position** — it always ends at the top.
Use `-NoScroll` when the screen must not be disturbed at all.

## On-device layout

Snapshots are a **sibling** of `crawl/`, never inside it, so a snapshot can never be destroyed by
the next fresh crawl and never disturbs crawl output:

```
/sdcard/Android/data/com.example.apptohtml/files/html/<target-package>/
├── crawl/                                    # untouched by this skill
└── snapshots/
    └── <yyyyMMdd_HHmmss>_<token>_<label>/
        ├── screen_00000_<label>.html
        ├── screen_00000_<label>.xml
        ├── screen_00000_<label>_merged_accessibility.xml
        ├── snapshot.json
        ├── capture.log
        └── .done                             # written LAST
```

`.done` is written strictly after every other file, so a poller that sees it is guaranteed a
complete directory. On failure the device writes `.failed` containing the reason instead; the
script prints that reason verbatim.

The XML is structurally identical to a crawl root screen's XML — same `<crawl>` block, same
element attributes — with no resolved edges, because a snapshot follows nothing. `ScreenXmlReader`
and every other consumer works unchanged.

## Behavior

- Local destination is `<DestinationRoot>\<target-package>\snapshot-<yyyyMMdd_HHmmss>`, with a
  numeric suffix if that already exists, so repeated captures never collide.
- The on-device directory is deleted after a successful pull; `-KeepOnDevice` retains it.
- Omitting `-DestinationRoot` captures on the device and reports the remote path without pulling.
- `-TimeoutSeconds` defaults to 45. A long scrollable screen takes a few seconds; the timeout is
  slack, not a target.
- `adb` is resolved from `PATH`, then `ANDROID_SDK_ROOT`, `ANDROID_HOME`, the repo's
  `local.properties`, then the default Windows SDK location. Override with `-AdbPath`.
- `-DeviceSerial` targets a specific device; `-AppToHtmlPackage` overrides the default
  `com.example.apptohtml`; `-RemoteHtmlRoot` overrides the whole on-device root.
- Pulls stage into a temporary directory and move into place only on success, and the Windows
  260-character path limit is checked before pulling.

## Failure modes worth recognizing

| Reported reason | Meaning |
|---|---|
| accessibility service not enabled | Pre-check failed; enable it in Settings > Accessibility. |
| `crawl_in_progress` | A crawl is running. Two coroutines driving one screen would corrupt both. Wait for it. |
| `own_ui_in_foreground` | AppToHTML itself is on screen. Switch to the target app. |
| `system_ui_in_foreground` | The notification shade or another SystemUI window is up. Dismiss it. |
| `no_foreground_window` | The service could not read an active window at all. |

## Notes

- The broadcast receiver is registered in **every** build variant, including release. That is a
  deliberate trade for a local dev tool: anyone who can reach `am broadcast` on a device with the
  service enabled can dump the foreground app's tree to disk. Gating it before any production
  release is tracked as bead `a2h-oo0`.
- Implementation: `SnapshotCaptureCoordinator`, `SnapshotFileStore`, and `SnapshotCrawlState` under
  `app/src/main/java/com/example/apptohtml/crawler/`. Re-read those if the on-device layout here
  ever disagrees with what you find.
