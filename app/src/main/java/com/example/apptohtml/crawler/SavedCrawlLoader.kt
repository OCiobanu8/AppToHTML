package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import java.io.File

data class LoadedCrawlState(
    val screens: List<CrawlScreenRecord>,
    val edges: List<CrawlEdgeRecord>,
    val dedupKeyToScreenId: LinkedHashMap<String, String>,
    val rootScreenId: String?,
    val nextScreenSequence: Int,
    val nextEdgeSequence: Int,
    val runLevel: RunLevelState?,
    val resolvedLinks: Map<String, Map<PressableElementLinkKey, String>>,
)

object SavedCrawlLoader {
    fun load(crawlDir: File): LoadedCrawlState? {
        if (!crawlDir.exists() || !crawlDir.isDirectory) return null
        val xmlFiles = crawlDir.listFiles()
            ?.filter { it.isFile && isCrawlScreenXml(it) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (xmlFiles.isEmpty()) return null

        data class Loaded(val file: File, val payload: ScreenXmlPayload)

        val loadedList = mutableListOf<Loaded>()
        for (file in xmlFiles) {
            val payload = ScreenXmlReader.readFull(file)
            if (payload == null) {
                runCatching {
                    DiagnosticLogger.error("Skipping malformed crawl XML: ${file.absolutePath}")
                }
                continue
            }
            loadedList += Loaded(file, payload)
        }

        val screens = mutableListOf<CrawlScreenRecord>()
        val edges = mutableListOf<CrawlEdgeRecord>()
        val dedupKeyToScreenId = LinkedHashMap<String, String>()
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
            // The content half is not recoverable from the artifacts and is recomputed from the
            // live screen after resume — exactly as the blanked replay fingerprint used to be.
            val identity = ScreenIdentity(
                packageName = head.screenPackage,
                rootClassName = "",
                elements = emptySet(),
                name = ScreenNameIdentity(
                    packageName = head.screenIdentity.packageName,
                    screenName = head.screenIdentity.title,
                    titleDisambiguators = head.screenIdentity.titleDisambiguators,
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
            DedupPolicy.keyFor(identity)?.let { dedupKey ->
                dedupKeyToScreenId.putIfAbsent(dedupKey, head.screenId)
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

        return LoadedCrawlState(
            screens = screens,
            edges = edges,
            dedupKeyToScreenId = dedupKeyToScreenId,
            rootScreenId = rootScreenId,
            nextScreenSequence = maxScreenSeq + 1,
            nextEdgeSequence = maxEdgeSeq + 1,
            runLevel = runLevel,
            resolvedLinks = resolvedLinks,
        )
    }

    private fun isCrawlScreenXml(file: File): Boolean {
        val name = file.name
        return name.endsWith(".xml") && !name.endsWith("_merged_accessibility.xml")
    }
}
