package com.example.apptohtml.crawler

/**
 * Builds a whole [ScreenIdentity] for fixtures that only care about one half of it.
 *
 * Before `a2h-c2b.2` these fixtures passed two loose strings — a name-based `screenFingerprint`
 * and a content-based `replayFingerprint` — which is exactly the split the structured identity
 * replaces. They now build one value, and the half a given test does not exercise stays empty
 * rather than being invented.
 */
internal fun testIdentity(
    screenName: String,
    packageName: String = "com.example.app",
    titleDisambiguators: List<String> = emptyList(),
    rootClassName: String = "android.widget.FrameLayout",
    elements: Set<ScreenElementIdentity> = emptySet(),
    confidence: ScreenDedupConfidence = ScreenDedupConfidence.STRONG,
): ScreenIdentity {
    return ScreenIdentity(
        packageName = packageName,
        rootClassName = rootClassName,
        elements = elements,
        name = ScreenNameIdentity(
            packageName = ScreenNaming.normalizeIdentityToken(packageName),
            screenName = ScreenNaming.normalizeIdentityToken(screenName),
            titleDisambiguators = titleDisambiguators.map(ScreenNaming::normalizeIdentityToken),
            confidence = confidence,
        ),
    )
}

/** The dedup key a fixture identity resolves to, for tests that index by it. */
internal fun testDedupKey(identity: ScreenIdentity): String =
    requireNotNull(DedupPolicy.keyFor(identity)) {
        "fixture identity is not eligible for dedup: $identity"
    }

/**
 * A screen identity carrying only its name half, taking the name tokens **exactly as given**.
 *
 * Distinct from [testIdentity], which normalizes what it is passed. These fixtures pass values that
 * are already in identity form (`com_example_target`), so normalizing again would be a second
 * opinion about a fact the caller already settled.
 *
 * `ScreenCrawlState` used to hold a name-only record and every such fixture said exactly this; it
 * now holds a whole [ScreenIdentity], and this keeps that intent in one place rather than repeating
 * the widened construction across six test files.
 */
internal fun nameOnlyIdentity(
    packageName: String,
    title: String,
    titleDisambiguators: List<String> = emptyList(),
    confidence: ScreenDedupConfidence = ScreenDedupConfidence.STRONG,
): ScreenIdentity = ScreenIdentity.EMPTY.withName(
    ScreenNameIdentity(
        packageName = packageName,
        screenName = title,
        titleDisambiguators = titleDisambiguators,
        confidence = confidence,
    )
)
