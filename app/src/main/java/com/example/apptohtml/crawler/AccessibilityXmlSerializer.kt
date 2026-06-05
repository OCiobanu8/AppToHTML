package com.example.apptohtml.crawler

import java.util.Locale

object AccessibilityXmlSerializer {
    fun serialize(snapshot: ScreenSnapshot): String = serialize(snapshot, crawlState = null)

    fun serialize(snapshot: ScreenSnapshot, crawlState: ScreenCrawlState?): String {
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="utf-8"?>""")
        builder.append('\n')
        builder.append("<screen")
        builder.append(""" name="${escape(snapshot.screenName)}"""")
        builder.append(""" package="${escape(snapshot.packageName)}"""")
        builder.append(""" scroll-steps="${snapshot.scrollStepCount}"""")
        builder.append(">")
        builder.append('\n')
        if (crawlState != null) {
            appendCrawlBlock(builder, crawlState, depth = 1)
        }
        appendMergedElements(
            builder = builder,
            elements = snapshot.elements,
            depth = 1,
            edgesByElement = crawlState?.edgesByElement,
        )
        appendScrollSteps(builder, snapshot.stepSnapshots, depth = 1)
        builder.append("</screen>")
        builder.append('\n')
        return builder.toString()
    }

    fun serialize(
        screenName: String,
        packageName: String,
        root: AccessibilityNodeSnapshot,
    ): String {
        val builder = StringBuilder()
        builder.append("""<?xml version="1.0" encoding="utf-8"?>""")
        builder.append('\n')
        builder.append("<screen")
        builder.append(""" name="${escape(screenName)}"""")
        builder.append(""" package="${escape(packageName)}"""")
        builder.append(">")
        builder.append('\n')
        appendNode(builder, root, depth = 1)
        builder.append("</screen>")
        builder.append('\n')
        return builder.toString()
    }

    private fun appendCrawlBlock(
        builder: StringBuilder,
        crawlState: ScreenCrawlState,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<crawl")
        builder.append(""" schema="v1"""")
        builder.append(""" screen-id="${escape(crawlState.screenId)}"""")
        builder.append(""" depth="${crawlState.depth}"""")
        builder.append(""" expansion-status="${escape(crawlState.expansionStatus.toXmlAttr())}"""")
        builder.append(""" is-root="${crawlState.isRoot}"""")
        if (crawlState.isRoot && crawlState.runLevel != null) {
            val runLevel = crawlState.runLevel
            builder.append(""" session-id="${escape(runLevel.sessionId)}"""")
            builder.append(""" started-at="${runLevel.startedAt}"""")
            if (runLevel.finishedAt != null) {
                builder.append(""" finished-at="${runLevel.finishedAt}"""")
            }
            builder.append(""" status="${escape(runLevel.status.toXmlAttr())}"""")
            builder.append(""" max-depth-reached="${runLevel.maxDepthReached}"""")
        }
        builder.append(">")
        builder.append('\n')

        appendScreenIdentity(builder, crawlState.screenIdentity, depth + 1)

        crawlState.parent?.let { parent ->
            appendParent(builder, parent, depth + 1)
        }

        appendRoute(builder, crawlState.route, depth + 1)

        builder.append(indent)
        builder.append("</crawl>")
        builder.append('\n')
    }

    private fun appendScreenIdentity(
        builder: StringBuilder,
        identity: ScreenIdentityFields,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<screen-identity")
        builder.append(""" package="${escape(identity.packageName)}"""")
        builder.append(""" title="${escape(identity.title)}"""")
        identity.hints.take(2).forEachIndexed { index, hint ->
            builder.append(""" hint-${index + 1}="${escape(hint)}"""")
        }
        builder.append(" />")
        builder.append('\n')
    }

    private fun appendParent(
        builder: StringBuilder,
        parent: ParentEdgeRef,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<parent")
        builder.append(""" screen-id="${escape(parent.screenId)}"""")
        if (parent.triggerLabel != null) {
            builder.append(""" trigger-label="${escape(parent.triggerLabel)}"""")
        }
        if (parent.triggerResourceId != null) {
            builder.append(""" trigger-resource-id="${escape(parent.triggerResourceId)}"""")
        }
        builder.append(" />")
        builder.append('\n')
    }

    private fun appendRoute(
        builder: StringBuilder,
        route: CrawlRoute,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        if (route.steps.isEmpty()) {
            builder.append(indent)
            builder.append("<route />")
            builder.append('\n')
            return
        }
        builder.append(indent)
        builder.append("<route>")
        builder.append('\n')
        route.steps.forEach { step ->
            appendRouteStep(builder, step, depth + 1)
        }
        builder.append(indent)
        builder.append("</route>")
        builder.append('\n')
    }

    private fun appendRouteStep(
        builder: StringBuilder,
        step: CrawlRouteStep,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<step")
        builder.append(""" label="${escape(step.label)}"""")
        builder.append(""" resource-id="${escape(step.resourceId.orEmpty())}"""")
        builder.append(""" class="${escape(step.className.orEmpty())}"""")
        builder.append(""" bounds="${escape(step.bounds)}"""")
        builder.append(""" child-index-path="${escape(step.childIndexPath.joinToString("."))}"""")
        builder.append(""" checkable="${step.checkable}"""")
        builder.append(""" checked="${step.checked}"""")
        builder.append(""" editable="${step.editable}"""")
        builder.append(""" first-seen-step="${step.firstSeenStep}"""")
        if (step.expectedPackageName != null) {
            builder.append(""" expected-package="${escape(step.expectedPackageName)}"""")
        }
        if (step.expectedReplayScreenName != null) {
            builder.append(""" expected-replay-screen-name="${escape(step.expectedReplayScreenName)}"""")
        }
        builder.append(">")
        builder.append('\n')

        val destinationFingerprint = step.expectedDestinationFingerprint
        if (destinationFingerprint != null) {
            val decoded = ScreenIdentityCodec.decode(destinationFingerprint)
            val childIndent = "  ".repeat(depth + 1)
            builder.append(childIndent)
            builder.append("<expected-destination-identity")
            if (decoded != null) {
                builder.append(""" package="${escape(decoded.packageName)}"""")
                builder.append(""" title="${escape(decoded.title)}"""")
                decoded.hints.take(2).forEachIndexed { index, hint ->
                    builder.append(""" hint-${index + 1}="${escape(hint)}"""")
                }
            } else {
                builder.append(""" fingerprint="${escape(destinationFingerprint)}"""")
            }
            builder.append(" />")
            builder.append('\n')
        }

        val replayFingerprint = step.expectedReplayFingerprint
        if (replayFingerprint != null) {
            appendExpectedReplay(builder, replayFingerprint, depth + 1)
        }

        builder.append(indent)
        builder.append("</step>")
        builder.append('\n')
    }

    private fun appendExpectedReplay(
        builder: StringBuilder,
        replayFingerprint: String,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        val decoded = ReplayFingerprintCodec.decode(replayFingerprint)
        if (decoded == null) {
            builder.append(indent)
            builder.append("<expected-replay")
            builder.append(""" fingerprint="${escape(replayFingerprint)}"""")
            builder.append(" />")
            builder.append('\n')
            return
        }
        builder.append(indent)
        builder.append("<expected-replay")
        builder.append(""" root-class="${escape(decoded.rootClass)}"""")
        if (decoded.elements.isEmpty()) {
            builder.append(" />")
            builder.append('\n')
            return
        }
        builder.append(">")
        builder.append('\n')
        val childIndent = "  ".repeat(depth + 1)
        decoded.elements.forEach { element ->
            builder.append(childIndent)
            builder.append("<element")
            builder.append(""" label="${escape(element.label)}"""")
            builder.append(""" resource-id="${escape(element.resourceId)}"""")
            builder.append(""" class="${escape(element.className)}"""")
            builder.append(""" list-item="${escape(element.isListItem)}"""")
            builder.append(""" checkable="${escape(element.checkable)}"""")
            builder.append(""" editable="${escape(element.editable)}"""")
            builder.append(" />")
            builder.append('\n')
        }
        builder.append(indent)
        builder.append("</expected-replay>")
        builder.append('\n')
    }

    private fun appendNode(
        builder: StringBuilder,
        node: AccessibilityNodeSnapshot,
        depth: Int,
        ancestors: List<AccessibilityNodeSnapshot> = emptyList(),
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<node")
        builder.append(""" class="${escape(node.className.orEmpty())}"""")
        builder.append(""" package="${escape(node.packageName.orEmpty())}"""")
        builder.append(""" resource-id="${escape(node.viewIdResourceName.orEmpty())}"""")
        builder.append(""" text="${escape(node.text.orEmpty())}"""")
        builder.append(""" content-description="${escape(node.contentDescription.orEmpty())}"""")
        builder.append(""" clickable="${node.clickable}"""")
        builder.append(""" click-action="${node.supportsClickAction}"""")
        builder.append(""" scrollable="${node.scrollable}"""")
        builder.append(""" checkable="${node.checkable}"""")
        builder.append(""" checked="${node.checked}"""")
        builder.append(""" editable="${node.editable}"""")
        builder.append(""" enabled="${node.enabled}"""")
        builder.append(""" visible-to-user="${node.visibleToUser}"""")
        builder.append(""" bounds="${escape(node.bounds)}"""")
        if (node.visibleToUser && (node.clickable || node.supportsClickAction)) {
            builder.append(""" fingerprint="${escape(nodeFingerprint(node, ancestors))}"""")
        }
        if (node.synthetic) {
            builder.append(""" synthetic="true"""")
        }
        if (node.merged) {
            builder.append(""" merged="true"""")
        }
        if (node.syntheticScrollContainer) {
            builder.append(""" synthetic-scroll-container="true"""")
        }
        if (node.firstSeenStep != null) {
            builder.append(""" first-seen-step="${node.firstSeenStep}"""")
        }
        if (node.sourceStepIndices.isNotEmpty()) {
            builder.append(""" source-step-indices="${escape(node.sourceStepIndices.joinToString(","))}"""")
        }
        if (node.children.isEmpty()) {
            builder.append(" />")
            builder.append('\n')
            return
        }

        builder.append(">")
        builder.append('\n')
        val childAncestors = ancestors + node
        node.children.forEach { child ->
            appendNode(builder, child, depth + 1, childAncestors)
        }
        builder.append(indent)
        builder.append("</node>")
        builder.append('\n')
    }

    /**
     * Fingerprint for a raw/synthetic `<node>` — only meaningful for pressable nodes. Reuses the
     * exact label resolution and list-item predicate that `collectPressableElements` uses, so the
     * node-level `fingerprint` equals the merged `<element>` fingerprint for the same button.
     */
    private fun nodeFingerprint(
        node: AccessibilityNodeSnapshot,
        ancestors: List<AccessibilityNodeSnapshot>,
    ): String {
        val isListItem = ancestors.any { ancestor ->
            AccessibilityTreeSnapshotter.isListLikeContainerClass(ancestor.className, ancestor.scrollable)
        }
        return ElementFingerprint.ofFields(
            label = AccessibilityTreeSnapshotter.resolveElementLabel(node),
            resourceId = node.viewIdResourceName,
            className = node.className,
            isListItem = isListItem,
            checkable = node.checkable,
            editable = node.editable,
        ).encoded
    }

    private fun appendMergedElements(
        builder: StringBuilder,
        elements: List<PressableElement>,
        depth: Int,
        edgesByElement: Map<PressableElementLinkKey, EdgeXmlView>? = null,
    ) {
        val indent = "  ".repeat(depth)
        if (elements.isEmpty()) {
            builder.append(indent)
            builder.append("<merged-elements />")
            builder.append('\n')
            return
        }

        builder.append(indent)
        builder.append("<merged-elements>")
        builder.append('\n')
        val elementIndent = "  ".repeat(depth + 1)
        elements.forEach { element ->
            builder.append(elementIndent)
            builder.append("<element")
            builder.append(""" label="${escape(element.label)}"""")
            builder.append(""" resource-id="${escape(element.resourceId.orEmpty())}"""")
            builder.append(""" class="${escape(element.className.orEmpty())}"""")
            builder.append(""" bounds="${escape(element.bounds)}"""")
            builder.append(""" list-item="${element.isListItem}"""")
            builder.append(""" child-index-path="${escape(element.childIndexPath.joinToString("."))}"""")
            builder.append(""" checkable="${element.checkable}"""")
            builder.append(""" checked="${element.checked}"""")
            builder.append(""" editable="${element.editable}"""")
            builder.append(""" first-seen-step="${element.firstSeenStep}"""")
            builder.append(""" fingerprint="${escape(ElementFingerprint.of(element).encoded)}"""")
            val edge = edgesByElement?.get(element.toLinkKey())
            if (edge == null) {
                builder.append(" />")
                builder.append('\n')
            } else {
                builder.append(">")
                builder.append('\n')
                appendEdge(builder, edge, depth + 2)
                builder.append(elementIndent)
                builder.append("</element>")
                builder.append('\n')
            }
        }
        builder.append(indent)
        builder.append("</merged-elements>")
        builder.append('\n')
    }

    private fun appendEdge(
        builder: StringBuilder,
        edge: EdgeXmlView,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<edge")
        builder.append(""" id="${escape(edge.edgeId)}"""")
        builder.append(""" status="${escape(edge.status.toXmlAttr())}"""")
        if (edge.childScreenId != null) {
            builder.append(""" child-screen-id="${escape(edge.childScreenId)}"""")
        }
        if (edge.childScreenName != null) {
            builder.append(""" child-screen-name="${escape(edge.childScreenName)}"""")
        }
        if (edge.message != null) {
            builder.append(""" message="${escape(edge.message)}"""")
        }
        if (edge.approval != CrawlEdgeApproval.NONE) {
            builder.append(""" approval="${escape(edge.approval.toXmlAttr())}"""")
        }
        if (edge.externalPackage != null) {
            builder.append(""" external-package="${escape(edge.externalPackage)}"""")
        }
        builder.append(" />")
        builder.append('\n')
    }

    private fun appendScrollSteps(
        builder: StringBuilder,
        steps: List<ScrollCaptureStep>,
        depth: Int,
    ) {
        val indent = "  ".repeat(depth)
        builder.append(indent)
        builder.append("<scroll-steps>")
        builder.append('\n')
        steps.forEach { step ->
            builder.append(indent)
            builder.append("  <step")
            builder.append(""" index="${step.stepIndex}"""")
            builder.append(""" new-elements="${step.newElementCount}"""")
            builder.append(">")
            builder.append('\n')
            appendNode(builder, step.root, depth + 2)
            builder.append(indent)
            builder.append("  </step>")
            builder.append('\n')
        }
        builder.append(indent)
        builder.append("</scroll-steps>")
        builder.append('\n')
    }

    private fun escape(input: String): String = buildString(input.length) {
        input.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> char
                }
            )
        }
    }

    private fun ScreenExpansionStatus.toXmlAttr(): String = name.lowercase(Locale.US)
    private fun CrawlEdgeStatus.toXmlAttr(): String = name.lowercase(Locale.US)
    private fun CrawlEdgeApproval.toXmlAttr(): String = name.lowercase(Locale.US)
    private fun CrawlRunStatus.toXmlAttr(): String = name.lowercase(Locale.US)
}
