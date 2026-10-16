package com.example.apptohtml.crawler

class CrawlRunTracker private constructor(
    val sessionId: String,
    val packageName: String,
    val startedAt: Long,
    private val screens: MutableList<CrawlScreenRecord>,
    private val edges: MutableList<CrawlEdgeRecord>,
    private val cases: MutableList<IdentityCase>,
    private var rootScreenId: String?,
    private var nextScreenSequence: Int,
    private var nextEdgeSequence: Int,
    private var nextCaseSequence: Int,
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
        cases = mutableListOf(),
        rootScreenId = null,
        nextScreenSequence = 0,
        nextEdgeSequence = 0,
        nextCaseSequence = 0,
    )

    fun nextScreenSequenceNumber(): Int = nextScreenSequence++

    fun addScreen(
        screenId: String,
        snapshot: ScreenSnapshot,
        identity: ScreenIdentity,
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
            identity = identity,
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
            externalPackage = externalPackage ?: existing.externalPackage,
        )
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

    /**
     * Every known screen's identity by screen id, in capture order — what [ScreenRecognition] runs
     * against the screen in front of the crawler. Dedup resolves through these, not a name index.
     */
    fun knownIdentities(): Map<String, ScreenIdentity> =
        screens.associateTo(linkedMapOf()) { it.screenId to it.identity }

    /**
     * Opens a case raised on [raisedOn], naming every screen in [relatedScreenIds], with the next
     * case id — unless an open case of the same [kind] on the same screens already exists. That case
     * keeps its id but takes the new [detail] and [rejectedProposal]: the same divergence seen again
     * (a replay that fails on every resume) is one case for the operator, not a new one each time, and
     * it describes what failed this time, not the first time. The caller writes it out; the tracker
     * only holds it.
     */
    fun openCase(
        kind: IdentityCaseKind,
        raisedOn: String,
        relatedScreenIds: List<String>,
        detail: String,
        rejectedProposal: List<Trait> = emptyList(),
    ): IdentityCase {
        val related = relatedScreenIds.filterNot { it == raisedOn }.distinct().sorted()
        val existingIndex = cases.indexOfFirst { existing ->
            existing.status == IdentityCaseStatus.OPEN &&
                existing.kind == kind &&
                existing.raisedOn == raisedOn &&
                existing.relatedScreenIds == related
        }
        if (existingIndex >= 0) {
            val refreshed = cases[existingIndex].copy(detail = detail, rejectedProposal = rejectedProposal)
            cases[existingIndex] = refreshed
            return refreshed
        }
        val case = IdentityCase(
            id = "case_%03d".format(nextCaseSequence++),
            status = IdentityCaseStatus.OPEN,
            kind = kind,
            raisedOn = raisedOn,
            relatedScreenIds = related,
            detail = detail,
            rejectedProposal = rejectedProposal,
        )
        cases += case
        return case
    }

    /**
     * Closes [caseId]: it stays in the case file as history, and every pointer to it disappears on
     * the involved screens' next write. The contract `a2h-c2b.13` builds on; no crawl path closes a
     * case yet, so only tests call this.
     */
    internal fun closeCase(caseId: String) {
        val index = cases.indexOfFirst { it.id == caseId }
        require(index >= 0) { "Unknown case $caseId." }
        cases[index] = cases[index].copy(status = IdentityCaseStatus.CLOSED)
    }

    /** Every case, open and closed, sorted by id. */
    fun cases(): List<IdentityCase> = cases.sortedBy(IdentityCase::id)

    /** The open cases involving [screenId], as the ids its pointers name. */
    fun openCaseIdsFor(screenId: String): List<String> = cases
        .filter { it.status == IdentityCaseStatus.OPEN && screenId in it.involvedScreenIds }
        .map(IdentityCase::id)
        .sorted()

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
            rootScreenId: String?,
            nextScreenSequence: Int,
            nextEdgeSequence: Int,
            cases: List<IdentityCase> = emptyList(),
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
                cases = cases.toMutableList(),
                rootScreenId = rootScreenId,
                nextScreenSequence = nextScreenSequence,
                nextEdgeSequence = nextEdgeSequence,
                nextCaseSequence = (cases.maxOfOrNull { parseCaseSequence(it.id) ?: -1 } ?: -1) + 1,
            )
        }

        internal fun parseScreenSequence(screenId: String): Int? {
            val prefix = "screen_"
            if (!screenId.startsWith(prefix)) return null
            return screenId.removePrefix(prefix).toIntOrNull()
        }

        internal fun parseCaseSequence(caseId: String): Int? =
            caseId.removePrefix("case_").takeIf { caseId.startsWith("case_") }?.toIntOrNull()

        internal fun parseEdgeSequence(edgeId: String): Int? {
            val prefix = "edge_"
            if (!edgeId.startsWith(prefix)) return null
            return edgeId.removePrefix(prefix).toIntOrNull()
        }
    }
}
