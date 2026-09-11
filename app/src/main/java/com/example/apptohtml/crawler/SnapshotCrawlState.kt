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

        val identity = ScreenIdentityFields(
            packageName = name.packageName,
            title = name.screenName,
            titleDisambiguators = name.titleDisambiguators,
        )

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
