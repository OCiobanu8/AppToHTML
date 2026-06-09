package com.example.apptohtml.crawler

class CrawlRunTracker private constructor(
    val sessionId: String,
    val packageName: String,
    val startedAt: Long,
    private val screens: MutableList<CrawlScreenRecord>,
    private val edges: MutableList<CrawlEdgeRecord>,
    private val screenFingerprintToId: LinkedHashMap<String, String>,
    private var rootScreenId: String?,
    private var nextScreenSequence: Int,
    private var nextEdgeSequence: Int,
) {
    val currentRootScreenId: String?
        get() = rootScreenId

    constructor(
        sessionId: String,
        packageName: String,
        startedAt: Long,
    ) : this(
        sessionId = sessionId,
        packageName = packageName,
        startedAt = startedAt,
        screens = mutableListOf(),
        edges = mutableListOf(),
        screenFingerprintToId = linkedMapOf(),
        rootScreenId = null,
        nextScreenSequence = 0,
        nextEdgeSequence = 0,
    )

    fun nextScreenSequenceNumber(): Int = nextScreenSequence++

    fun addScreen(
        screenId: String,
        snapshot: ScreenSnapshot,
        screenFingerprint: String,
        replayFingerprint: String,
        indexFingerprint: Boolean = true,
        files: CapturedScreenFiles,
        parentScreenId: String?,
        triggerElement: PressableElement?,
        route: CrawlRoute,
        depth: Int,
        expansionStatus: ScreenExpansionStatus = ScreenExpansionStatus.NOT_STARTED,
    ) {
        screens += CrawlScreenRecord(
            screenId = screenId,
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            screenFingerprint = screenFingerprint,
            replayFingerprint = replayFingerprint,
            htmlPath = files.htmlFile.absolutePath,
            xmlPath = files.xmlFile.absolutePath,
            mergedXmlPath = files.mergedXmlFile?.absolutePath,
            scrollStepCount = snapshot.scrollStepCount,
            parentScreenId = parentScreenId,
            triggerLabel = triggerElement?.label,
            triggerResourceId = triggerElement?.resourceId,
            route = route,
            depth = depth,
            expansionStatus = expansionStatus,
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
        childScreenName: String? = null,
        approval: CrawlEdgeApproval = CrawlEdgeApproval.NONE,
        externalPackage: String? = null,
    ): String {
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
            childScreenName = childScreenName,
            approval = approval,
            externalPackage = externalPackage,
            isListItem = element.isListItem,
            checkable = element.checkable,
            editable = element.editable,
        )
        return edgeId
    }

    fun addPendingEdge(
        parentScreenId: String,
        element: PressableElement,
    ): String {
        return addEdge(
            parentScreenId = parentScreenId,
            element = element,
            status = CrawlEdgeStatus.PENDING,
        )
    }

    fun updateEdgeStatus(
        edgeId: String,
        status: CrawlEdgeStatus,
        childScreenId: String? = null,
        childScreenName: String? = null,
        message: String? = null,
        approval: CrawlEdgeApproval? = null,
        externalPackage: String? = null,
    ): CrawlEdgeRecord {
        val index = edges.indexOfFirst { it.edgeId == edgeId }
        require(index >= 0) { "Edge not found: $edgeId" }
        val existing = edges[index]
        val updated = existing.copy(
            status = status,
            childScreenId = childScreenId ?: existing.childScreenId,
            childScreenName = childScreenName ?: existing.childScreenName,
            message = message ?: existing.message,
            approval = approval ?: existing.approval,
            externalPackage = externalPackage ?: existing.externalPackage,
        )
        edges[index] = updated
        return updated
    }

    fun setEdgeApproval(edgeId: String, approval: CrawlEdgeApproval): CrawlEdgeRecord {
        val index = edges.indexOfFirst { it.edgeId == edgeId }
        require(index >= 0) { "Edge not found: $edgeId" }
        val existing = edges[index]
        val updated = existing.copy(approval = approval)
        edges[index] = updated
        return updated
    }

    fun setScreenExpansionStatus(
        screenId: String,
        status: ScreenExpansionStatus,
    ): CrawlScreenRecord {
        val index = screens.indexOfFirst { it.screenId == screenId }
        require(index >= 0) { "Screen not found: $screenId" }
        val updated = screens[index].copy(expansionStatus = status)
        screens[index] = updated
        return updated
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

    fun findEdge(edgeId: String): CrawlEdgeRecord? {
        return edges.firstOrNull { edge -> edge.edgeId == edgeId }
    }

    fun outboundEdges(screenId: String): List<CrawlEdgeRecord> {
        return edges.filter { it.parentScreenId == screenId }
    }

    fun allScreens(): List<CrawlScreenRecord> = screens.toList()

    fun allEdges(): List<CrawlEdgeRecord> = edges.toList()

    fun clearOutboundEdges(screenId: String) {
        edges.removeAll { it.parentScreenId == screenId }
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

    companion object {
        fun fromExistingState(
            sessionId: String,
            packageName: String,
            startedAt: Long,
            screens: List<CrawlScreenRecord>,
            edges: List<CrawlEdgeRecord>,
            screenFingerprintToId: LinkedHashMap<String, String>,
            rootScreenId: String?,
            nextScreenSequence: Int,
            nextEdgeSequence: Int,
        ): CrawlRunTracker {
            val maxScreenSeq = screens.maxOfOrNull { parseScreenSequence(it.screenId) ?: -1 } ?: -1
            require(nextScreenSequence >= maxScreenSeq + 1) {
                "nextScreenSequence ($nextScreenSequence) must be > max loaded sequence ($maxScreenSeq)"
            }
            val maxEdgeSeq = edges.maxOfOrNull { parseEdgeSequence(it.edgeId) ?: -1 } ?: -1
            require(nextEdgeSequence >= maxEdgeSeq + 1) {
                "nextEdgeSequence ($nextEdgeSequence) must be > max loaded sequence ($maxEdgeSeq)"
            }
            require(rootScreenId == null || screens.any { it.screenId == rootScreenId }) {
                "rootScreenId $rootScreenId not present in loaded screens"
            }
            val inProgressCount = screens.count { it.expansionStatus == ScreenExpansionStatus.IN_PROGRESS }
            require(inProgressCount <= 1) {
                "At most one screen may be IN_PROGRESS; found $inProgressCount"
            }
            return CrawlRunTracker(
                sessionId = sessionId,
                packageName = packageName,
                startedAt = startedAt,
                screens = screens.toMutableList(),
                edges = edges.toMutableList(),
                screenFingerprintToId = LinkedHashMap(screenFingerprintToId),
                rootScreenId = rootScreenId,
                nextScreenSequence = nextScreenSequence,
                nextEdgeSequence = nextEdgeSequence,
            )
        }

        internal fun parseScreenSequence(screenId: String): Int? {
            val prefix = "screen_"
            if (!screenId.startsWith(prefix)) return null
            return screenId.removePrefix(prefix).toIntOrNull()
        }

        internal fun parseEdgeSequence(edgeId: String): Int? {
            val prefix = "edge_"
            if (!edgeId.startsWith(prefix)) return null
            return edgeId.removePrefix(prefix).toIntOrNull()
        }
    }
}
