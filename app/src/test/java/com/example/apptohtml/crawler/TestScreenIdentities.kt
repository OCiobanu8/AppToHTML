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
