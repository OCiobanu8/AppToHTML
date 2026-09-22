package com.example.apptohtml.crawler

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Rebuilds the accessibility tree a capture recorded, so a saved screen can be asked the same
 * questions as a live one.
 *
 * `<node>` trees are written by every capture but nothing has ever read one back. That is what makes
 * validating a saved screen possible at all: the policies and the trait evaluator take a tree, not a
 * file, so without this a capture can only be compared as text.
 *
 * **Step 0, not the merged tree.** Every production identity is built from the first viewport
 * (`stepSnapshots.first().root`), which is what the crawler sees the moment it arrives. Validating
 * against the scroll-merged tree would certify an identity the crawler cannot observe without
 * scrolling first.
 *
 * Read-only: it opens the file and returns a value, and never writes.
 */
internal object CaptureTreeReader {

    /**
     * The first viewport's tree, or null when [file] is unreadable or records no steps.
     *
     * [childIndexPath] is not serialized; it is rebuilt from each node's position, which is exactly
     * how `AccessibilityTreeSnapshotter` assigns it. It takes no part in an element's identity, so
     * it cannot move a verdict — it only feeds click-candidate ranking.
     */
    fun readFirstViewport(file: File): AccessibilityNodeSnapshot? {
        if (!file.exists() || !file.isFile) return null
        val document = runCatching {
            DocumentBuilderFactory.newInstance()
                .apply {
                    isNamespaceAware = false
                    isValidating = false
                    runCatching {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                }
                .newDocumentBuilder()
                .parse(file)
        }.getOrNull() ?: return null

        val screen = document.documentElement ?: return null
        val steps = screen.firstChildElement("scroll-steps") ?: return null
        val firstStep = steps.childElementsNamed("step")
            .minByOrNull { step -> step.getAttribute("index").toIntOrNull() ?: Int.MAX_VALUE }
            ?: return null
        val rootNode = firstStep.firstChildElement("node") ?: return null
        return parseNode(rootNode, childIndexPath = emptyList())
    }

    private fun parseNode(
        element: Element,
        childIndexPath: List<Int>,
    ): AccessibilityNodeSnapshot {
        val children = element.childElementsNamed("node").mapIndexed { index, child ->
            parseNode(child, childIndexPath + index)
        }
        return AccessibilityNodeSnapshot(
            className = element.getAttribute("class").takeIf { it.isNotEmpty() },
            packageName = element.getAttribute("package").takeIf { it.isNotEmpty() },
            viewIdResourceName = element.getAttribute("resource-id").takeIf { it.isNotEmpty() },
            // An empty attribute is an absent value here: the writer emits "" for null, and a node
            // whose text is the empty string is not distinguishable from one that has none.
            text = element.getAttribute("text").takeIf { it.isNotEmpty() },
            contentDescription = element.getAttribute("content-description").takeIf { it.isNotEmpty() },
            clickable = element.booleanAttribute("clickable"),
            supportsClickAction = element.booleanAttribute("click-action"),
            scrollable = element.booleanAttribute("scrollable"),
            checkable = element.booleanAttribute("checkable"),
            checked = element.booleanAttribute("checked"),
            editable = element.booleanAttribute("editable"),
            enabled = element.booleanAttribute("enabled"),
            visibleToUser = element.booleanAttribute("visible-to-user"),
            bounds = element.getAttribute("bounds"),
            children = children,
            childIndexPath = childIndexPath,
            synthetic = element.booleanAttribute("synthetic"),
            merged = element.booleanAttribute("merged"),
            syntheticScrollContainer = element.booleanAttribute("synthetic-scroll-container"),
            sourceStepIndices = element.getAttribute("source-step-indices")
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() },
            firstSeenStep = element.getAttribute("first-seen-step").toIntOrNull(),
        )
    }

    private fun Element.booleanAttribute(name: String): Boolean = getAttribute(name) == "true"
}
