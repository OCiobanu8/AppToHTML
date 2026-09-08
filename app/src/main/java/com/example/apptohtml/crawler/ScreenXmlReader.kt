package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import org.w3c.dom.Document

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ScreenCrawlHead(
    val screenName: String,
    val screenPackage: String,
    val scrollStepCount: Int,
    val screenId: String,
    val depth: Int,
    val expansionStatus: ScreenExpansionStatus,
    val isRoot: Boolean,
    val screenIdentity: ScreenIdentityFields,
    val parent: ParentEdgeRef?,
    val route: CrawlRoute,
    val runLevel: RunLevelState?,
)

data class ScreenXmlPayload(
    val head: ScreenCrawlHead,
    val elements: List<PressableElement>,
    val edgeByElement: Map<PressableElementLinkKey, EdgeXmlView>,
)

object ScreenXmlReader {
    private const val MERGED_ELEMENTS_TAG = "<merged-elements"
    private const val SCROLL_STEPS_TAG = "<scroll-steps"

    fun readHead(file: File): ScreenCrawlHead? {
        val document = parseUpTo(file, MERGED_ELEMENTS_TAG) ?: return null
        return parseHead(document, file)
    }

    fun readFull(file: File): ScreenXmlPayload? {
        val document = parseUpTo(file, SCROLL_STEPS_TAG) ?: return null
        val head = parseHead(document, file) ?: return null
        val (elements, edgeByElement) = parseMergedElements(document)
        return ScreenXmlPayload(
            head = head,
            elements = elements,
            edgeByElement = edgeByElement,
        )
    }

    private fun parseUpTo(file: File, stopMarker: String): Document? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val idx = raw.indexOf(stopMarker)
            val truncated = if (idx < 0) {
                raw
            } else {
                buildString(idx + 16) {
                    append(raw, 0, idx)
                    append("</screen>\n")
                }
            }
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {
                // Feature not supported on this parser; safe to ignore for our trusted inputs.
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(truncated.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            logSafely("Failed to parse screen XML at ${file.absolutePath}", e)
            null
        }
    }

    private fun logSafely(message: String, throwable: Throwable? = null) {
        runCatching { DiagnosticLogger.error(message, throwable) }
    }

    private fun parseHead(document: Document, file: File): ScreenCrawlHead? {
        val screen = document.documentElement ?: return null
        if (screen.tagName != "screen") {
            logSafely("Expected <screen> root in ${file.absolutePath}, found <${screen.tagName}>")
            return null
        }
        val screenName = screen.getAttribute("name")
        val screenPackage = screen.getAttribute("package")
        val scrollStepCount = screen.getAttribute("scroll-steps").toIntOrNull() ?: 1
        val crawl = firstElementChild(screen, "crawl")
        if (crawl == null) {
            logSafely("Missing <crawl> block in ${file.absolutePath}")
            return null
        }
        val screenId = crawl.getAttribute("screen-id")
        if (screenId.isBlank()) {
            logSafely("Missing screen-id in <crawl> at ${file.absolutePath}")
            return null
        }
        val depth = crawl.getAttribute("depth").toIntOrNull() ?: 0
        val expansionStatus = parseExpansionStatus(crawl.getAttribute("expansion-status"))
        val isRoot = crawl.getAttribute("is-root") == "true"
        val identityElement = firstElementChild(crawl, "screen-identity")
        if (identityElement == null) {
            logSafely("Missing <screen-identity> in ${file.absolutePath}")
            return null
        }
        val identity = parseScreenIdentity(identityElement)
        val parent = firstElementChild(crawl, "parent")?.let(::parseParent)
        val route = firstElementChild(crawl, "route")?.let(::parseRoute) ?: CrawlRoute()
        val runLevel = if (isRoot) parseRunLevel(crawl) else null
        return ScreenCrawlHead(
            screenName = screenName,
            screenPackage = screenPackage,
            scrollStepCount = scrollStepCount,
            screenId = screenId,
            depth = depth,
            expansionStatus = expansionStatus,
            isRoot = isRoot,
            screenIdentity = identity,
            parent = parent,
            route = route,
            runLevel = runLevel,
        )
    }

    private fun parseScreenIdentity(element: Element): ScreenIdentityFields {
        val packageName = element.getAttribute("package")
        val title = element.getAttribute("title")
        val hints = listOfNotNull(
            element.getAttribute("hint-1").takeIf { it.isNotBlank() },
            element.getAttribute("hint-2").takeIf { it.isNotBlank() },
        )
        return ScreenIdentityFields(
            packageName = packageName,
            title = title,
            hints = hints,
        )
    }

    private fun parseParent(element: Element): ParentEdgeRef {
        return ParentEdgeRef(
            screenId = element.getAttribute("screen-id"),
            triggerLabel = element.optionalAttribute("trigger-label"),
            triggerResourceId = element.optionalAttribute("trigger-resource-id"),
        )
    }

    private fun parseRoute(routeElement: Element): CrawlRoute {
        val steps = childElements(routeElement, "step").map(::parseRouteStep)
        return CrawlRoute(steps = steps)
    }

    private fun parseRouteStep(stepElement: Element): CrawlRouteStep {
        val childIndexPath = stepElement.getAttribute("child-index-path")
            .split('.')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
        val expectedPackageName = stepElement.optionalAttribute("expected-package")
        val expectedReplayScreenName = stepElement.optionalAttribute("expected-replay-screen-name")
        val expectedDestinationFingerprint = firstElementChild(stepElement, "expected-destination-identity")
            ?.let(::decodeDestinationIdentity)
        val expectedReplayFingerprint = firstElementChild(stepElement, "expected-replay")
            ?.let(::decodeReplay)
        return CrawlRouteStep(
            childIndexPath = childIndexPath,
            bounds = stepElement.getAttribute("bounds"),
            resourceId = stepElement.getAttribute("resource-id").takeIf { it.isNotEmpty() },
            className = stepElement.getAttribute("class").takeIf { it.isNotEmpty() },
            label = stepElement.getAttribute("label"),
            checkable = stepElement.getAttribute("checkable") == "true",
            checked = stepElement.getAttribute("checked") == "true",
            editable = stepElement.getAttribute("editable") == "true",
            firstSeenStep = stepElement.getAttribute("first-seen-step").toIntOrNull() ?: 0,
            expectedPackageName = expectedPackageName,
            expectedDestinationFingerprint = expectedDestinationFingerprint,
            expectedReplayFingerprint = expectedReplayFingerprint,
            expectedReplayScreenName = expectedReplayScreenName,
        )
    }

    private fun decodeDestinationIdentity(element: Element): String? {
        val raw = element.optionalAttribute("fingerprint")
        if (raw != null) return raw
        val packageName = element.getAttribute("package")
        val title = element.getAttribute("title")
        if (packageName.isBlank() && title.isBlank()) return null
        val hints = listOfNotNull(
            element.getAttribute("hint-1").takeIf { it.isNotBlank() },
            element.getAttribute("hint-2").takeIf { it.isNotBlank() },
        )
        return ScreenIdentityCodec.encode(packageName, title, hints)
    }

    private fun decodeReplay(element: Element): String? {
        val raw = element.optionalAttribute("fingerprint")
        if (raw != null) return raw
        val rootClass = element.getAttribute("root-class")
        val elements = childElements(element, "element").map { child ->
            ReplayFingerprintCodec.ElementFields(
                resourceId = child.getAttribute("resource-id"),
                label = child.getAttribute("label"),
                className = child.getAttribute("class"),
                isListItem = child.getAttribute("list-item"),
                checkable = child.getAttribute("checkable"),
                editable = child.getAttribute("editable"),
            )
        }
        return ReplayFingerprintCodec.encode(rootClass, elements)
    }

    private fun parseRunLevel(crawl: Element): RunLevelState? {
        val sessionId = crawl.optionalAttribute("session-id") ?: return null
        val startedAt = crawl.getAttribute("started-at").toLongOrNull() ?: return null
        val finishedAt = crawl.optionalAttribute("finished-at")?.toLongOrNull()
        val status = parseRunStatus(crawl.getAttribute("status"))
        val maxDepthReached = crawl.getAttribute("max-depth-reached").toIntOrNull() ?: 0
        return RunLevelState(
            sessionId = sessionId,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = status,
            maxDepthReached = maxDepthReached,
        )
    }

    private fun parseMergedElements(
        document: Document,
    ): Pair<List<PressableElement>, Map<PressableElementLinkKey, EdgeXmlView>> {
        val screen = document.documentElement ?: return emptyList<PressableElement>() to emptyMap()
        val merged = firstElementChild(screen, "merged-elements")
            ?: return emptyList<PressableElement>() to emptyMap()
        val elements = mutableListOf<PressableElement>()
        val edges = linkedMapOf<PressableElementLinkKey, EdgeXmlView>()
        childElements(merged, "element").forEach { elementNode ->
            val element = parsePressableElement(elementNode)
            elements += element
            firstElementChild(elementNode, "edge")?.let { edgeNode ->
                edges[element.toLinkKey()] = parseEdge(edgeNode)
            }
        }
        return elements to edges
    }

    private fun parsePressableElement(node: Element): PressableElement {
        val childIndexPath = node.getAttribute("child-index-path")
            .split('.')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
        return PressableElement(
            label = node.getAttribute("label"),
            resourceId = node.getAttribute("resource-id").takeIf { it.isNotEmpty() },
            bounds = node.getAttribute("bounds"),
            className = node.getAttribute("class").takeIf { it.isNotEmpty() },
            isListItem = node.getAttribute("list-item") == "true",
            childIndexPath = childIndexPath,
            checkable = node.getAttribute("checkable") == "true",
            checked = node.getAttribute("checked") == "true",
            editable = node.getAttribute("editable") == "true",
            firstSeenStep = node.getAttribute("first-seen-step").toIntOrNull() ?: 0,
        )
    }

    private fun parseEdge(node: Element): EdgeXmlView {
        return EdgeXmlView(
            edgeId = node.getAttribute("id"),
            status = parseEdgeStatus(node.getAttribute("status")),
            childScreenId = node.optionalAttribute("child-screen-id"),
            childScreenName = node.optionalAttribute("child-screen-name"),
            message = node.optionalAttribute("message"),
            externalPackage = node.optionalAttribute("external-package"),
        )
    }

    private fun parseExpansionStatus(value: String): ScreenExpansionStatus {
        val upper = value.uppercase(Locale.US)
        return ScreenExpansionStatus.entries.firstOrNull { it.name == upper }
            ?: ScreenExpansionStatus.NOT_STARTED
    }

    private fun parseEdgeStatus(value: String): CrawlEdgeStatus {
        val upper = value.uppercase(Locale.US)
        return CrawlEdgeStatus.entries.firstOrNull { it.name == upper }
            ?: CrawlEdgeStatus.PENDING
    }

    private fun parseRunStatus(value: String): CrawlRunStatus {
        val upper = value.uppercase(Locale.US)
        return CrawlRunStatus.entries.firstOrNull { it.name == upper }
            ?: CrawlRunStatus.IN_PROGRESS
    }

    private fun Element.optionalAttribute(name: String): String? {
        if (!hasAttribute(name)) return null
        val value = getAttribute(name)
        return value.takeIf { it.isNotEmpty() }
    }

    private fun firstElementChild(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tag) {
                return node
            }
        }
        return null
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tag) {
                result += node
            }
        }
        return result
    }
}
