package com.example.apptohtml.crawler

class CrawlRunTracker(
    private val sessionId: String,
    private val packageName: String,
    private val startedAt: Long,
) {
    private val screens = mutableListOf<CrawlScreenRecord>()
    private val edges = mutableListOf<CrawlEdgeRecord>()
    private val screenFingerprintToId = linkedMapOf<String, String>()
    private var rootScreenId: String? = null
    private var nextScreenSequence = 0
    private var nextEdgeSequence = 0

    fun nextScreenSequenceNumber(): Int = nextScreenSequence++

    fun addScreen(
        screenId: String,
        snapshot: ScreenSnapshot,
        screenFingerprint: String,
        indexFingerprint: Boolean = true,
        files: CapturedScreenFiles,
        parentScreenId: String?,
        triggerElement: PressableElement?,
        route: CrawlRoute,
        depth: Int,
    ) {
        screens += CrawlScreenRecord(
            screenId = screenId,
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            screenFingerprint = screenFingerprint,
            htmlPath = files.htmlFile.absolutePath,
            xmlPath = files.xmlFile.absolutePath,
            mergedXmlPath = files.mergedXmlFile?.absolutePath,
            scrollStepCount = snapshot.scrollStepCount,
            parentScreenId = parentScreenId,
            triggerLabel = triggerElement?.label,
            triggerResourceId = triggerElement?.resourceId,
            route = route,
            depth = depth,
        )
        if (indexFingerprint) {
            screenFingerprintToId.putIfAbsent(screenFingerprint, screenId)
        }
        if (depth == 0) {
            rootScreenId = screenId
        }
    }

    fun addEdge(
        parentScreenId: String,
        element: PressableElement,
        status: CrawlEdgeStatus,
        childScreenId: String? = null,
        message: String? = null,
    ) {
        val edgeId = "edge_%03d".format(nextEdgeSequence++)
        edges += CrawlEdgeRecord(
            edgeId = edgeId,
            parentScreenId = parentScreenId,
            childScreenId = childScreenId,
            label = element.label,
            resourceId = element.resourceId,
            className = element.className,
            bounds = element.bounds,
            childIndexPath = element.childIndexPath,
            firstSeenStep = element.firstSeenStep,
            status = status,
            message = message,
        )
    }

    fun buildManifest(
        status: CrawlRunStatus,
        finishedAt: Long = System.currentTimeMillis(),
    ): CrawlManifest {
        return CrawlManifest(
            sessionId = sessionId,
            packageName = packageName,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status,
            rootScreenId = rootScreenId,
            maxDepthReached = maxDiscoveredDepth(),
            screens = screens.toList(),
            edges = edges.toList(),
        )
    }

    fun findScreenIdByFingerprint(screenFingerprint: String): String? {
        return screenFingerprintToId[screenFingerprint]
    }

    fun findScreen(screenId: String): CrawlScreenRecord? {
        return screens.firstOrNull { screen -> screen.screenId == screenId }
    }

    fun capturedScreenCount(): Int = screens.size

    fun capturedChildScreenCount(): Int = screens.count { it.depth > 0 }

    fun maxDiscoveredDepth(): Int = screens.maxOfOrNull { it.depth } ?: 0

    fun skippedElementCount(): Int {
        return edges.count { edge ->
            edge.status == CrawlEdgeStatus.SKIPPED_BLACKLIST ||
                edge.status == CrawlEdgeStatus.SKIPPED_NO_NAVIGATION ||
                edge.status == CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE
        }
    }
}
