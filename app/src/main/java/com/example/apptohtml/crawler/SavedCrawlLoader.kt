package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import java.io.File

data class LoadedCrawlState(
    val screens: List<CrawlScreenRecord>,
    val edges: List<CrawlEdgeRecord>,
    val rootScreenId: String?,
    val nextScreenSequence: Int,
    val nextEdgeSequence: Int,
    val runLevel: RunLevelState?,
    val resolvedLinks: Map<String, Map<PressableElementLinkKey, String>>,
    /** Every case in the crawl's case file, open and closed. */
    val cases: List<IdentityCase> = emptyList(),
)

object SavedCrawlLoader {
    /**
     * The saved crawl in [crawlDir], or null when there is none.
     *
     * Throws [MalformedIdentityCases] when the case file cannot be read, or a screen points at a case
     * the file does not hold open: a resume that dropped a case would carry on past a divergence an
     * operator was meant to see.
     */
    fun load(crawlDir: File): LoadedCrawlState? {
        if (!crawlDir.exists() || !crawlDir.isDirectory) return null
        val xmlFiles = crawlDir.listFiles()
            ?.filter { it.isFile && isCrawlScreenXml(it) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (xmlFiles.isEmpty()) return null

        data class Loaded(val file: File, val payload: ScreenXmlPayload)

        val loadedList = mutableListOf<Loaded>()
        val unreadable = mutableListOf<File>()
        for (file in xmlFiles) {
            val payload = ScreenXmlReader.readFull(file)
            if (payload == null) {
                runCatching {
                    DiagnosticLogger.error("Skipping malformed crawl XML: ${file.absolutePath}")
                }
                unreadable += file
                continue
            }
            loadedList += Loaded(file, payload)
        }

        val screens = mutableListOf<CrawlScreenRecord>()
        val edges = mutableListOf<CrawlEdgeRecord>()
        val caseFile = IdentityCaseStore.fileIn(crawlDir)
        val cases = IdentityCaseStore.read(caseFile)
        val openCasesById = cases.filter { it.status == IdentityCaseStatus.OPEN }.associateBy { it.id }
        val screensById = mutableMapOf<String, CrawlScreenRecord>()
        val htmlFilenameByScreenId = mutableMapOf<String, String>()
        val resolvedLinks = mutableMapOf<String, MutableMap<PressableElementLinkKey, String>>()
        var rootScreenId: String? = null
        var runLevel: RunLevelState? = null
        var maxScreenSeq = -1
        var maxEdgeSeq = -1

        for (loaded in loadedList) {
            val head = loaded.payload.head
            val xmlFile = loaded.file
            val baseName = xmlFile.name.removeSuffix(".xml")
            val htmlFile = File(xmlFile.parentFile, "$baseName.html")
            val mergedXmlFile = File(xmlFile.parentFile, "${baseName}_merged_accessibility.xml")
            val mergedXmlPath = if (mergedXmlFile.exists()) mergedXmlFile.absolutePath else null
            val nameConfidence = ScreenNaming.buildScreenNameIdentity(
                screenName = head.screenName,
                packageName = head.screenPackage,
                root = null,
            ).confidence
            // The whole identity is recovered — element set and traits included. It used to be
            // blanked here because the artifacts did not carry it; they do now, and blanking it
            // would discard on every resume whatever an operator had settled by hand.
            //
            // The name's confidence is still recomputed rather than read: it is a judgement about
            // the live screen's naming signals, and computing it from the same inputs as before is
            // what keeps every dedup key exactly where it was.
            val persistedName = head.screenIdentity.name
            val identity = head.screenIdentity.withName(
                ScreenNameIdentity(
                    packageName = persistedName?.packageName.orEmpty(),
                    screenName = persistedName?.screenName.orEmpty(),
                    titleDisambiguators = persistedName?.titleDisambiguators.orEmpty(),
                    confidence = nameConfidence,
                ),
            )
            val record = CrawlScreenRecord(
                screenId = head.screenId,
                screenName = head.screenName,
                packageName = head.screenPackage,
                identity = identity,
                htmlPath = htmlFile.absolutePath,
                xmlPath = xmlFile.absolutePath,
                mergedXmlPath = mergedXmlPath,
                scrollStepCount = head.scrollStepCount,
                parentScreenId = head.parent?.screenId,
                triggerLabel = head.parent?.triggerLabel,
                triggerResourceId = head.parent?.triggerResourceId,
                route = head.route,
                depth = head.depth,
                expansionStatus = head.expansionStatus,
            )
            screens += record
            screensById[head.screenId] = record
            htmlFilenameByScreenId[head.screenId] = htmlFile.name
            CrawlRunTracker.parseScreenSequence(head.screenId)?.let { seq ->
                if (seq > maxScreenSeq) maxScreenSeq = seq
            }
            if (head.isRoot) {
                rootScreenId = head.screenId
                runLevel = head.runLevel
            }
            head.openCaseIds.firstOrNull { id ->
                openCasesById[id]?.involvedScreenIds?.contains(head.screenId) != true
            }?.let { dangling ->
                throw MalformedIdentityCases(
                    "${xmlFile.absolutePath} points at case $dangling, which ${caseFile.absolutePath} " +
                        "does not hold open for ${head.screenId}.",
                )
            }
        }

        for (loaded in loadedList) {
            val parentScreenId = loaded.payload.head.screenId
            val elements = loaded.payload.elements
            val edgesByElement = loaded.payload.edgeByElement
            for (element in elements) {
                val edgeView = edgesByElement[element.toLinkKey()] ?: continue
                val normalizedStatus = if (edgeView.status == CrawlEdgeStatus.FAILED) {
                    CrawlEdgeStatus.PENDING
                } else {
                    edgeView.status
                }
                val normalizedMessage = if (edgeView.status == CrawlEdgeStatus.FAILED) {
                    null
                } else {
                    edgeView.message
                }
                edges += CrawlEdgeRecord(
                    edgeId = edgeView.edgeId,
                    parentScreenId = parentScreenId,
                    childScreenId = edgeView.childScreenId,
                    label = element.label,
                    resourceId = element.resourceId,
                    className = element.className,
                    bounds = element.bounds,
                    childIndexPath = element.childIndexPath,
                    firstSeenStep = element.firstSeenStep,
                    status = normalizedStatus,
                    message = normalizedMessage,
                    childScreenName = edgeView.childScreenName,
                    externalPackage = edgeView.externalPackage,
                    isListItem = element.isListItem,
                    checkable = element.checkable,
                    editable = element.editable,
                )
                CrawlRunTracker.parseEdgeSequence(edgeView.edgeId)?.let { seq ->
                    if (seq > maxEdgeSeq) maxEdgeSeq = seq
                }
                if (edgeView.status == CrawlEdgeStatus.CAPTURED ||
                    edgeView.status == CrawlEdgeStatus.LINKED_EXISTING
                ) {
                    val childId = edgeView.childScreenId
                    val childHtml = childId?.let { htmlFilenameByScreenId[it] }
                    if (!childHtml.isNullOrBlank()) {
                        val parentLinks = resolvedLinks.getOrPut(parentScreenId) { mutableMapOf() }
                        parentLinks[element.toLinkKey()] = childHtml
                    }
                }
            }
        }

        val loadedScreenIds = screens.mapTo(hashSetOf()) { it.screenId }
        cases.forEach { case ->
            case.involvedScreenIds.firstOrNull { it !in loadedScreenIds }?.let { missing ->
                // An operator editing a case's screen and breaking its XML is the likely cause; name
                // that file rather than blame the case.
                val brokenFile = unreadable.firstOrNull { it.name.startsWith("${missing}_") }
                throw MalformedIdentityCases(
                    if (brokenFile != null) {
                        "Case ${case.id} in ${caseFile.absolutePath} names $missing, whose XML " +
                            "${brokenFile.absolutePath} could not be read."
                    } else {
                        "Case ${case.id} in ${caseFile.absolutePath} names $missing, which this crawl does not hold."
                    },
                )
            }
        }

        return LoadedCrawlState(
            screens = screens,
            edges = edges,
            rootScreenId = rootScreenId,
            nextScreenSequence = maxScreenSeq + 1,
            nextEdgeSequence = maxEdgeSeq + 1,
            runLevel = runLevel,
            resolvedLinks = resolvedLinks,
            cases = cases,
        )
    }

    /**
     * Whether [file] is a screen's own XML — not its merged-accessibility sibling, and not the crawl's
     * case file, which sits in the same folder and would otherwise be parsed as a malformed screen.
     */
    internal fun isCrawlScreenXml(file: File): Boolean {
        val name = file.name
        return name.endsWith(".xml") &&
            !name.endsWith("_merged_accessibility.xml") &&
            name != IdentityCaseStore.FILE_NAME
    }
}
