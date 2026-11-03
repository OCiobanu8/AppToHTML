package com.example.apptohtml.crawler

import java.io.File

/**
 * A saved screen's `.xml` and `.html` written to disk by the production writers, for tests that
 * then read, edit or settle them. One home, so the operator-path tests all start from files the
 * crawler itself would have written.
 */
internal object SavedScreenFixtures {
    const val PACKAGE = "com.example.target"

    fun element(label: String, resourceId: String?, isBack: Boolean = false) = ScreenElementIdentity(
        fingerprint = ElementFingerprint(
            resourceId = resourceId,
            label = label,
            className = "android.widget.Button",
            isListItem = false,
            checkable = false,
            editable = false,
        ),
        isBackAffordance = isBack,
    )

    /** An identity named [screenName], with [traits] stamped [settledBy] when there are any. */
    fun identity(
        screenName: String,
        traits: List<Trait> = emptyList(),
        settledBy: IdentityOrigin? = IdentityOrigin.OPERATOR.takeIf { traits.isNotEmpty() },
        elements: Set<ScreenElementIdentity> = setOf(element("Open", "$PACKAGE:id/open")),
    ): ScreenIdentity = ScreenIdentity(
        packageName = PACKAGE,
        rootClassName = "android.widget.FrameLayout",
        elements = elements,
        traits = traits,
        settledBy = settledBy,
    ).withName(
        ScreenNameIdentity(
            packageName = ScreenNaming.normalizeIdentityToken(PACKAGE),
            screenName = ScreenNaming.normalizeIdentityToken(screenName),
            titleDisambiguators = emptyList(),
            confidence = ScreenDedupConfidence.STRONG,
        )
    )

    fun snapshot(screenName: String, scrollSteps: List<ScrollCaptureStep> = emptyList()) = ScreenSnapshot(
        screenName = screenName,
        packageName = PACKAGE,
        elements = emptyList(),
        xmlDump = "",
        scrollStepCount = scrollSteps.size.coerceAtLeast(1),
        stepSnapshots = scrollSteps,
    )

    fun crawlState(
        screenId: String,
        identity: ScreenIdentity,
        isRoot: Boolean = screenId == "screen_00000",
        activeCaseIds: List<String> = emptyList(),
    ) = ScreenCrawlState(
        screenId = screenId,
        depth = if (isRoot) 0 else 1,
        expansionStatus = ScreenExpansionStatus.COMPLETE,
        isRoot = isRoot,
        screenIdentity = identity,
        parent = if (isRoot) null else ParentEdgeRef("screen_00000", "Open", "$PACKAGE:id/open"),
        route = CrawlRoute(),
        runLevel = if (isRoot) {
            RunLevelState(
                sessionId = "session-1",
                startedAt = 1_000L,
                finishedAt = null,
                status = CrawlRunStatus.IN_PROGRESS,
                maxDepthReached = 1,
            )
        } else {
            null
        },
        edgesByElement = emptyMap(),
        activeCaseIds = activeCaseIds,
    )

    /** Writes `<screenId>_<slug>.xml` and `.html` into [dir] and returns the XML file. */
    fun writeScreen(
        dir: File,
        screenId: String,
        screenName: String,
        identity: ScreenIdentity,
        activeCaseIds: List<String> = emptyList(),
        scrollSteps: List<ScrollCaptureStep> = emptyList(),
    ): File {
        val base = "${screenId}_${screenName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
        val snapshot = snapshot(screenName, scrollSteps)
        File(dir, "$base.html").writeText(HtmlRenderer.render(snapshot, emptyMap(), identity), Charsets.UTF_8)
        val xml = File(dir, "$base.xml")
        xml.writeText(
            AccessibilityXmlSerializer.serialize(snapshot, crawlState(screenId, identity, activeCaseIds = activeCaseIds)),
            Charsets.UTF_8,
        )
        return xml
    }

    /** The `.html` beside [xml]. */
    fun htmlOf(xml: File): File = File(xml.parentFile, xml.name.removeSuffix(".xml") + ".html")
}
