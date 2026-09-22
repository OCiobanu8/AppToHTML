# Screen Identity: the on-disk block and the validation tool

Every captured screen carries its whole identity — name, element set and traits — inside its own
`.xml` and `.html`. One command tells an operator whether that identity **holds**: does it match the
screen in front of it, and does it match **only** that screen.

This page covers the format and the tool. The identity model itself (why identity is structured
rather than a flattened string, and what each comparison policy is for) lives with the code in
`ScreenIdentity.kt`, `ScreenIdentityPolicy.kt` and `ScreenTraits.kt`.

## The identity block

`<crawl><screen-identity>` carries all of it:

```xml
<screen-identity package="com.android.settings" name-package="com_android_settings"
                 title="network_internet" title-disambiguator-1="collapsing_toolbar"
                 root-class="android.widget.FrameLayout">
  <element label="internet" resource-id="" class="android.widget.LinearLayout"
           list-item="true" checkable="false" editable="false" back="false" />
  <traits>
    <has-list container-resource-id="com.android.settings:id/recycler_view" min-rows="5">
      <row-child resource-id="android:id/title" />
      <row-child resource-id="android:id/summary" />
    </has-list>
  </traits>
</screen-identity>
```

- `package` is the app's real package, meaning exactly what it means on a route-step identity,
  because the **same reader decodes both**. `name-package` is the name half's normalized identity
  token, which is a different value and so gets its own attribute.
- `<element>` uses the shape the route-step expectations already used; both go through
  `ScreenIdentityXml`, so the screen's identity and the ones its route steps carry cannot drift into
  two formats.
- Elements serialize sorted by their encoded form, so the same identity always produces the same
  bytes and a rewrite stays diff-clean.
- `<traits>` is omitted entirely when empty. An absent block and an empty one would mean the same
  thing, so only one of them is ever written.

The page carries the same block as an XML data block in `<head>`:

```html
<script type="application/xml" id="screen-identity">
  <screen-identity …>…</screen-identity>
</script>
```

One syntax and one parser for both files, so an operator edits the same text wherever they are. The
two copies differ only by the indentation the page adds, which is why the tool compares them as
**parsed identities** rather than as text.

**The two must agree.** When they do not, the tool refuses and names the difference. Preferring one
would silently discard an edit made to the other, which is the failure the check exists to prevent.

### What a capture proposes

A capture writes its name and element set and **no traits**. Every fresh capture therefore validates
as `UNSETTLED`: it proposes an element set but asserts nothing. Machine-proposed traits are
`a2h-c2b.3`; today an operator adds them by hand.

## What the crawler reads back

`SavedCrawlLoader` recovers the whole identity on resume, traits included. It previously blanked the
content half — correctly at the time, because nothing wrote it — which meant a resumed crawl also
wrote an empty `replayFingerprint` into its own manifest and graph.

One crawl decision consults it: **route-replay arrival**. The screen must still be called what it was
called, and — if its identity is settled — its traits must hold there.

- The **element set is deliberately not compared** at that site. A screen's content legitimately
  churns between visits, and requiring byte equality of it is the brittleness the identity redesign
  exists to remove.
- An `UNSETTLED` identity is **not consulted at all**. Nothing proposes traits automatically, so
  every machine-proposed identity is unsettled and every existing crawl behaves exactly as before.
  Only a screen an operator deliberately settled can newly fail a replay.
- A non-holding trait takes the existing `handlePreparationFailure` recovery path, the same one a
  name divergence already took. No new pause, queue or case — those are `a2h-c2b.3`.

## The validation tool

`.claude/skills/validate-screen-identity/` runs the crawler's **own** matching code over saved
captures on the host JVM, with no device needed for the disk path. Nothing in it re-implements
identity, trait evaluation, uniqueness or click eligibility: it composes calls and formats what comes
back, so a verdict there is a verdict the crawler agrees with.

The headline is the **trait** answer — `HOLDS`, `NOT_UNIQUE`, `DOES_NOT_HOLD`, `UNSETTLED` — and the
element-set answer travels beside it, never folded in. They answer different questions, and blending
them would hide a failing one behind a passing one.

See the skill's `SKILL.md` for invocation, exit codes and the hand-settling workflow.

### Authoring traits

Prefer `has-list` over `has-control` on dense screens. Measured across two crawls of Settings taken
40 minutes apart: **every** element that changed had no resource id (11 of 11), and ~88% of a typical
screen's identity elements have none. Assertions built on individual elements are fragile there, while `has-list` reads
container and row ids out of the *tree*, which stay stable even when the rows do not.

The measurements behind that — two Settings crawls, joined on the full name key — are summarised
in the notes of bead `a2h-c2b.3`, which carries them because this cycle's evidence directory is
git-ignored by design.
