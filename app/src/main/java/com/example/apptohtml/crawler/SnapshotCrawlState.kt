package com.example.apptohtml.crawler

/**
 * Builds the minimal [ScreenCrawlState] a snapshot needs so its XML has the *same document shape*
 * as a crawl root screen's.
 *
 * Passing `crawlState = null` to [AccessibilityXmlSerializer] silently drops the `<crawl>` block
 * and every per-element edge attribute, producing structurally different XML that downstream
 * readers would have to special-case. A snapshot follows nothing, so the state it synthesizes has
 * no edges — which is honest — but is otherwise built exactly the way
 * `DeepCrawlCoordinator.buildScreenCrawlState` builds a root screen, taking the name
 * half of the structured identity directly rather than round-tripping it through an encoded string.
 */
internal object SnapshotCrawlState {

    /**
     * [root] names the screen. The identity's **element set** is taken from the first viewport
     * instead, whatever [root] is.
     *
     * The two differ on any scrolling screen, and taking the element set from a scroll-merged root
     * made a snapshot fail validation against *itself*: the stored set held below-the-fold controls
     * that the first viewport does not, so the tool reported them missing and offered to paste back
     * a set with them deleted. Every production identity is built from the first viewport; a
     * snapshot's must be too, or the artifact does not describe what the crawler would see on
     * arrival.
     */
    fun build(
        snapshot: ScreenSnapshot,
        root: AccessibilityNodeSnapshot?,
        sessionId: String,
        startedAt: Long,
        finishedAt: Long,
    ): ScreenCrawlState {
        val name = ScreenNaming.buildScreenNameIdentity(
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            root = root,
        )

        // The content half comes from the FIRST VIEWPORT, deliberately not from the root the name
        // was derived from — see this function's contract. No traits: proposing them is a2h-c2b.3,
        // and an operator adds them by hand.
        val identityRoot = snapshot.stepSnapshots.firstOrNull()?.root ?: root
        val identity = (identityRoot?.let(ScreenIdentity::fromRoot) ?: ScreenIdentity.EMPTY)
            .withName(name)

        return ScreenCrawlState(
            screenId = SnapshotFileStore.SNAPSHOT_SCREEN_ID,
            depth = 0,
            expansionStatus = ScreenExpansionStatus.NOT_STARTED,
            isRoot = true,
            screenIdentity = identity,
            parent = null,
            route = CrawlRoute(),
            runLevel = RunLevelState(
                sessionId = sessionId,
                startedAt = startedAt,
                finishedAt = finishedAt,
                status = CrawlRunStatus.COMPLETED,
                maxDepthReached = 0,
            ),
            edgesByElement = emptyMap(),
        )
    }
}
