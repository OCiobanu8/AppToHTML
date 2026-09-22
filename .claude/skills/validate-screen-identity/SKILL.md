---
name: validate-screen-identity
description: Check whether a screen's saved identity holds — does it match this screen, and does it match only this screen — against a live device screen or a second capture on disk. Use when the user wants to settle or verify a screen fingerprint by hand ("does this fingerprint still hold", "validate the cart screen", "why did replay diverge here", "check my identity edit"), or to survey a whole crawl for screens that no longer match what was recorded.
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

The headline is the **trait** answer. The element-set answer travels beside it and is never folded
in: a screen's content legitimately churns between visits, so a changed element set is information,
not a verdict.

## Prerequisites

- A crawl or snapshot captured by a build that writes the identity block. Older captures have no
  `<screen-identity>` content and are reported as `INVALID_INPUT` asking for a recapture — the tool
  never invents one.
- No device is needed for the disk path.

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

Survey a whole crawl — every screen validated against its own capture:

```powershell
... -Crawl 'E:\Logs\com.android.settings\crawl'
```

## Settling a screen by hand

1. Run with `-Target` on a fresh capture. It reports `UNSETTLED`: a capture proposes an element set
   but asserts nothing.
2. Add a `<traits>` block to the `<screen-identity>` element in **both** the `.xml` and the `.html`.
   The two must agree; if they differ the tool refuses and names the difference rather than picking
   one, because picking one would discard the edit made to the other.
3. Re-run. `HOLDS` means the assertion is true of this screen and of no other known screen.
4. `NOT_UNIQUE` means another screen's identity also holds here — tighten one of them.

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

`-Strict` additionally fails when the element set does not match or any element is unresolved.

## What it will not do

- **Never writes to your captures.** The report goes to stdout; paste text is printed for you to
  apply, never applied for you.
- **Never picks a winner** on a uniqueness collision, or between a disagreeing `.xml` and `.html`.
  Both are operator decisions.
- Does not propose traits. Machine proposal is `a2h-c2b.3`; today every capture starts `UNSETTLED`.
