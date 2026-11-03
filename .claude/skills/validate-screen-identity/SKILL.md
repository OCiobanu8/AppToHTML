---
name: validate-screen-identity
description: Check whether a screen's saved identity holds — does it match this screen, and does it match only this screen — against a live device screen or a second capture on disk, and settle a crawl's identity cases offline. Use when the user wants to settle or verify a screen fingerprint ("does this fingerprint still hold", "validate the cart screen", "why did replay diverge here", "handle the open cases", "settle case_000"), to open the case overview for a crawl, or to survey a whole crawl for screens that no longer match what was recorded.
allowed-tools: PowerShell, Read, Glob, Grep
---

# Validate Screen Identity

Runs AppToHTML's **own** matching code over saved captures on the host JVM. Nothing here
re-implements identity, trait evaluation, uniqueness or click eligibility — the tool composes the
crawler's calls and formats what comes back, so a verdict here is a verdict the crawler agrees with.

## What it answers

| Question | Answer |
|---|---|
| Do this screen's traits hold here? | `HOLDS` / `DOES_NOT_HOLD` / `UNSETTLED` |
| Do they hold **only** here? | `NOT_UNIQUE`, listing every colliding screen |
| Does the element set still match? | reported **beside** the headline, with missing and extra |
| Can each element still be clicked? | resolved / ambiguous / unresolved, per element |
| What should I paste? | the observed screen's elements, in the identity block's syntax |
| Which cases does this crawl have, and can I settle one? | the case overview in the browser, or `-Settle` |

The headline is the **trait** answer. The element-set answer travels beside it and is never folded
in: a screen's content legitimately churns between visits, so a changed element set is information,
not a verdict.

## Prerequisites

- A crawl or snapshot captured by a build that writes the identity block. Older captures have no
  `<screen-identity>` content and are reported as `INVALID_INPUT` asking for a recapture — the tool
  never invents one. A crawl from before the `settled-by` stamp carries unstamped traits, which are
  refused the same way: crawl the app again with the current build.
- No device is needed for the disk path. Pushing a settle needs `adb` and the device the crawl came from.

## Workflow

Validate one screen against its own capture — the usual starting point, which tells you whether the
identity you just edited holds:

```powershell
.\.claude\skills\validate-screen-identity\scripts\validate-screen-identity.ps1 `
    -Target 'E:\Logs\com.android.settings\crawl\screen_00001_network_internet.xml'
```

Validate against a **second** capture of the same screen, taken later:

```powershell
... -Target '<first>.xml' -Observed '<second>.xml'
```

Validate against what is **on the device right now**. This runs the `capture-screen` skill first and
validates the capture it returns:

```powershell
... -Target '<screen>.xml' -Live
```

Survey a whole crawl as text — every screen validated against its own capture, then the crawl's
cases with their status:

```powershell
... -Crawl 'E:\Logs\com.android.settings\crawl' -Survey
```

Open the **case overview** for a crawl — the operator's way to work its cases (see below):

```powershell
... -Crawl 'E:\Logs\com.android.settings\crawl-20261016_130954'
```

## Settling a case

When the crawl pauses on an identity case, choose **Stop**, then pull the crawl (`pull-crawl`). Work
only the cases you want to; the rest stay open and the resume leaves them exactly as they are.

1. Run `-Crawl <pulled crawl>` alone. The tool starts (the first start builds it, which can take a
   minute) and opens the **case overview** in your browser: every case with its status, kind, screen
   and reason. Open and handled cases link to their case page; closed ones are history.
2. On a case page, the left side is a traits editor, pre-filled with what the crawler tried (or, for a
   settled screen that stopped holding, the identity it has now) and commented-out examples of every
   trait. The right side is the screen: its pressable elements, each with *copy as has-control*, the
   identity as recorded, and the first viewport's tree for container and row ids.
3. Write the traits and press **Check Traits**. The answer is the crawler's own: the traits must hold on
   this screen's capture, no other settled identity may hold there, and they may hold on no other
   screen. Nothing is written by a check. Do **not** write `settled-by`: the tool records who settled.
4. When the check passes, **Settle + Push** appears (just **Settle** with no device). It checks again,
   writes the identity into the screen's `.xml` and `.html` (only the identity block changes, stamped
   `settled-by="operator"`), and marks the case **handled**. With a device it then pushes exactly those
   three files, after confirming the device copies still match the local files the settle replaces.
   If they do not — the device crawl changed since the pull, or an earlier settle in this folder was
   written but never pushed — it refuses and writes nothing anywhere. Settle while the crawl is stopped.
   With no device connected, a settle writes locally and lists the `adb push` commands for the three
   files. Run them before resuming, or the device crawl will not see the settle; until they are run, a
   later settle with a device is refused, because the device no longer matches the local copy.
5. Back on the overview the case reads **handled**. Press **Done** when finished; the tool exits.
   It also stops by itself after 30 minutes without a request. The page then says the tool is not
   answering; the editor keeps its text, so copy it out and start the tool again.
6. Resume the crawl on the device. It revisits each handled screen: if the identity holds on the live
   screen the case is **closed** and its pointers go; if not, the case reopens with the live reason and
   the crawl pauses on it. A screen not yet expanded is then expanded, so the crawl carries on along it.

The same settle from the command line, for scripts:

```powershell
... -Crawl '<crawl>' -Settle case_000 -Traits '.\battery.traits.xml' -DryRun   # check only
... -Crawl '<crawl>' -Settle case_000 -Traits '.\battery.traits.xml' -Push     # settle and push
```

The traits file holds one unstamped `<traits>` block. A settled-does-not-hold case is fixed by settling
new traits on its screen; the case file records the identity that was replaced.

Hand-editing a screen's identity is still possible, but use the case page where you can. A resume
refuses, naming the files, a `<traits>` block it cannot read (an unknown or malformed trait, or a
missing or unknown stamp) and an `.xml` and `.html` whose identities disagree. It does **not** notice a
deleted `<screen-identity>` element or a misspelt tag such as `<trait>`. Those read as a screen with no
traits, and the next rewrite drops the edit.

Known limit: a uniqueness case raised because **another** screen's settled identity holds on this one
cannot be settled from this screen alone — any traits written here leave the crawler recognising the
other screen first. Tighten the other screen's identity instead (validate it with `-Target`), or leave
the case open; merging two screens is not something the tool does.

Known limit: the check runs against the crawl's saved captures. When a settled screen has changed so
much that nothing on its old capture still holds on the live screen, no traits can pass both the
offline check and the live one, and the resume reopens the case each time. Leave such a case open for
now (follow-up bead `a2h-c2b.25`).

Run one settle at a time per crawl folder. The tool takes a lock, so a second settle on the same folder
(from the page and the command line at once, say) is refused rather than allowed to undo the first.

Author traits from what the capture shows, not from guesswork. The `<scroll-steps>` block holds the
real tree, so container and row resource ids can be read straight out of it.

```xml
<traits>
  <has-list container-resource-id="com.android.settings:id/recycler_view" min-rows="5">
    <row-child resource-id="android:id/title" />
    <row-child resource-id="android:id/summary" />
  </has-list>
  <has-control>
    <element label="checkout" resource-id="com.example.shop:id/checkout"
             class="android.widget.Button" list-item="false" checkable="false"
             editable="false" back="false" />
  </has-control>
</traits>
```

Prefer `has-list` over `has-control` on dense screens. Measured across two crawls of Settings taken
40 minutes apart, **every** element that changed had no resource id (11 of 11), and ~88% of a typical
screen's identity elements have none — so assertions built on individual elements are fragile there. `has-list` reads
container and row ids out of the *tree*, which stay stable even when the rows themselves do not.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | `HOLDS` |
| 1 | `DOES_NOT_HOLD` |
| 2 | `NOT_UNIQUE` |
| 3 | `UNSETTLED` |
| 4 | `INVALID_INPUT` |
| 5 | usage error |
| 6 | `SETTLE_FAILED`: the traits hold, but the write or push did not complete (the message says which) |

`-Strict` additionally fails when the element set does not match or any element is unresolved.

## What it will not do

- **Writes only when settling a case**, and only after the identity holds: the screen's `.xml` and
  `.html` identity block and `identity-cases.xml`. Every other mode is read-only.
- **Never picks a winner** on a uniqueness collision, or between a disagreeing `.xml` and `.html`.
  Both are operator decisions.
- Does not propose traits. The crawler does that at capture (see `documentation/screen-identity.md`);
  this tool only checks what a screen asserts.
