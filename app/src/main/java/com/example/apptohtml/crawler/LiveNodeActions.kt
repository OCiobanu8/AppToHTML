package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger

/**
 * The subset of a live accessibility node's attributes that the action layer reads.
 *
 * Extracted so [LiveNodeActions] never touches `AccessibilityNodeInfo` (or `android.graphics.Rect`)
 * directly and can therefore be exercised against synthetic nodes in plain JVM unit tests, per
 * `factory/FUNCTION.md`.
 */
internal data class LiveNodeAttributes(
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val boundsShortString: String = "",
    val visibleToUser: Boolean = false,
    val enabled: Boolean = false,
    val scrollable: Boolean = false,
    val clickable: Boolean = false,
    val checkable: Boolean = false,
    val editable: Boolean = false,
)

/**
 * The action id vocabulary, injected rather than read from `AccessibilityNodeInfo`.
 *
 * `ACTION_SCROLL_FORWARD`/`_BACKWARD`/`_CLICK` are compile-time constants and would inline safely,
 * but `AccessibilityAction.ACTION_SCROLL_DOWN.id` and friends resolve through framework statics
 * that throw "not mocked" under the JVM unit-test runtime. Injecting the whole vocabulary keeps the
 * class testable without adding `unitTests.isReturnDefaultValues` to the build file (which SCOPE
 * forbids touching).
 */
internal data class LiveActionIds(
    val scrollForward: Int,
    val scrollBackward: Int,
    val click: Int,
    val scrollDown: Int,
    val scrollUp: Int,
    val pageDown: Int,
    val pageUp: Int,
)

/**
 * Performs scroll and click gestures against a *live* accessibility tree.
 *
 * Extracted verbatim from `AppToHtmlAccessibilityService` — behavior is intentionally unchanged.
 * The tree is supplied as lambdas (the shape [PathReplayResolver] already uses) so both the crawl
 * path and the snapshot path can share it and so the logic is unit-testable.
 *
 * Two ordering rules here are load-bearing and easy to "clean up" into a regression; both are
 * pinned by tests:
 *  - [preferredActionIds] appends preferred ids the node does *not* advertise, rather than
 *    filtering them out — some implementations honor an action they never list.
 *  - path candidates are attempted deepest-first ([List.asReversed]) while fallback candidates are
 *    attempted in their natural (score/pre-order) order.
 */
internal class LiveNodeActions<N>(
    private val childCount: (N) -> Int,
    private val childAt: (N, Int) -> N?,
    private val attributes: (N) -> LiveNodeAttributes,
    private val supportedActionIds: (N) -> Set<Int>,
    private val performAction: (N, Int) -> Boolean,
    private val actionIds: LiveActionIds,
    private val logger: () -> CrawlLogger?,
    private val diagnostics: (String) -> Unit = DiagnosticLogger::log,
) {

    fun performScroll(
        root: N,
        childIndexPath: List<Int>,
        action: Int,
    ): Boolean {
        val pathResolution = resolvePathNodes(root, childIndexPath)
        logPathDivergenceIfNeeded(action, pathResolution)
        val pathNodes = pathResolution.usableNodes()
        logger()?.info(
            "live_action_start type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "resolvedNodeCount=${pathNodes.size} resolvedPathDepth=${pathResolution.resolvedDepth} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} candidateSource=path"
        )
        if (attemptActionOnCandidates(pathNodes.asReversed(), action, "path")) {
            return true
        }

        val fallbackCandidates = collectScrollableCandidates(root)
            .filterNot { candidate -> pathNodes.any { it === candidate } }
        logger()?.info(
            "live_action_fallback type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "fallbackCandidateCount=${fallbackCandidates.size}"
        )
        if (attemptActionOnCandidates(fallbackCandidates, action, "fallback")) {
            return true
        }

        logger()?.warn(
            "live_action_failed type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "resolvedNodeCount=${pathNodes.size} fallbackCandidateCount=${fallbackCandidates.size} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()}"
        )
        diagnostics(
            "Scroll action ${actionName(action)} failed for path=$childIndexPath; no candidate accepted the gesture."
        )
        return false
    }

    fun performClick(
        root: N,
        element: PressableElement,
    ): Boolean {
        val pathResolution = resolvePathNodes(root, element.childIndexPath)
        logPathDivergenceIfNeeded(actionIds.click, pathResolution)
        val pathNodes = pathResolution.usableNodes()
            .filter { node ->
                val attrs = attributes(node)
                attrs.visibleToUser && attrs.enabled
            }
        logger()?.info(
            "live_action_start type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "resolvedNodeCount=${pathNodes.size} resolvedPathDepth=${pathResolution.resolvedDepth} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} candidateSource=path " +
                "label=${quoteForLog(element.label)} resourceId=${quoteForLog(element.resourceId.orEmpty())} " +
                "className=${quoteForLog(element.className.orEmpty())} bounds=${quoteForLog(element.bounds)}"
        )
        if (attemptActionOnCandidates(pathNodes.asReversed(), actionIds.click, "path")) {
            return true
        }

        val target = clickFallbackTargetFor(element)
        val allCandidates = collectClickFallbackCandidates(root)
            .filterNot { entry -> pathNodes.any { it === entry.node } }
        val matches = ClickFallbackMatcher.selectMatches(
            candidates = allCandidates.map { it.candidate },
            target = target,
        )
        val fallbackCandidates = matches.map { match -> match.candidate.handle }
        logger()?.info(
            "live_action_fallback type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "fallbackCandidateCount=${fallbackCandidates.size} totalLiveCandidateCount=${allCandidates.size} " +
                "label=${quoteForLog(element.label)} eligibilityReasons=${quoteForLog(formatEligibilityReasons(matches))} " +
                "topRankScore=${matches.firstOrNull()?.rankScore ?: 0}"
        )
        if (attemptActionOnCandidates(fallbackCandidates, actionIds.click, "fallback")) {
            return true
        }

        logger()?.warn(
            "live_action_failed type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "resolvedNodeCount=${pathNodes.size} fallbackCandidateCount=${fallbackCandidates.size} " +
                "totalLiveCandidateCount=${allCandidates.size} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} label=${quoteForLog(element.label)}"
        )
        diagnostics(
            "Click action failed for '${element.label}' with path=${element.childIndexPath}; no candidate accepted the gesture."
        )
        return false
    }

    internal fun collectScrollableCandidates(root: N): List<N> {
        val candidates = mutableListOf<ScoredCandidate<N>>()

        fun walk(node: N, depth: Int) {
            val attrs = attributes(node)
            if (attrs.visibleToUser && attrs.scrollable) {
                candidates += ScoredCandidate(node = node, score = scrollableCandidateScore(attrs, depth))
            }
            repeat(childCount(node)) { index ->
                childAt(node, index)?.let { child ->
                    walk(child, depth + 1)
                }
            }
        }

        walk(root, depth = 0)
        return candidates.sortedByDescending { it.score }.map { it.node }
    }

    internal fun collectClickFallbackCandidates(root: N): List<LiveClickCandidate<N>> {
        val candidates = mutableListOf<LiveClickCandidate<N>>()

        // Ancestors are carried as already-resolved attributes rather than as nodes: the original
        // read two fields per ancestor, so re-resolving a full attribute bundle per ancestor per
        // node would turn an O(n·depth) field read into O(n·depth) object constructions.
        fun walk(node: N, depth: Int, ancestors: List<LiveNodeAttributes>) {
            val attrs = attributes(node)
            val supportsClick = actionIds.click in supportedActionIds(node)
            if (attrs.visibleToUser && attrs.enabled && (attrs.clickable || supportsClick)) {
                val isListItem = ancestors.any { ancestorAttrs ->
                    AccessibilityTreeSnapshotter.isListLikeContainerClass(
                        className = ancestorAttrs.className,
                        scrollable = ancestorAttrs.scrollable,
                    )
                }
                candidates += LiveClickCandidate(
                    node = node,
                    candidate = ClickFallbackMatcher.Candidate(
                        handle = node,
                        visible = attrs.visibleToUser,
                        enabled = attrs.enabled,
                        clickable = attrs.clickable,
                        supportsClickAction = supportsClick,
                        fingerprint = ElementFingerprint.ofFields(
                            label = resolveLiveElementLabel(node),
                            resourceId = attrs.viewIdResourceName,
                            className = attrs.className,
                            isListItem = isListItem,
                            checkable = attrs.checkable,
                            editable = attrs.editable,
                        ),
                        depth = depth,
                    ),
                )
            }
            val nextAncestors = ancestors + attrs
            repeat(childCount(node)) { index ->
                childAt(node, index)?.let { child ->
                    walk(child, depth + 1, nextAncestors)
                }
            }
        }

        walk(root, depth = 0, ancestors = emptyList())
        return candidates
    }

    /**
     * Live-tree equivalent of [AccessibilityTreeSnapshotter]'s `resolveElementLabel`: text →
     * content description → nested title/text → `ScreenNaming.chooseElementLabel` fallback
     * (resource-id segment, then a bounds-derived placeholder). Kept byte-identical to the snapshot
     * path so the live [ElementFingerprint] equals the recorded one.
     */
    internal fun resolveLiveElementLabel(node: N): String {
        resolveLiveLabel(node)?.takeIf { it.isNotBlank() }?.let { return it }
        val attrs = attributes(node)
        return ScreenNaming.chooseElementLabel(
            text = null,
            contentDescription = null,
            viewIdResourceName = attrs.viewIdResourceName,
            bounds = attrs.boundsShortString,
        )
    }

    /**
     * Preferred-first, then the remaining preferred ids the node never advertised. The trailing
     * append is deliberate: some views honor a scroll action they omit from `actionList`.
     */
    internal fun preferredActionIds(
        node: N,
        requestedAction: Int,
    ): List<Int> {
        val supported = supportedActionIds(node)
        val preferred = when (requestedAction) {
            actionIds.scrollForward -> listOf(
                actionIds.scrollForward,
                actionIds.scrollDown,
                actionIds.pageDown,
            )

            actionIds.scrollBackward -> listOf(
                actionIds.scrollBackward,
                actionIds.scrollUp,
                actionIds.pageUp,
            )

            actionIds.click -> listOf(
                actionIds.click,
            )

            else -> listOf(requestedAction)
        }

        val preferredSupported = preferred.filter { actionId -> actionId in supported }
        return (preferredSupported + preferred.filterNot { it in preferredSupported }).distinct()
    }

    internal fun scrollableCandidateScore(attrs: LiveNodeAttributes, depth: Int): Int {
        val className = attrs.className.orEmpty()
        val classScore = when {
            className.contains("RecyclerView") -> 600
            className.contains("ListView") -> 575
            className.contains("GridView") -> 550
            className.contains("NestedScrollView") -> 525
            className.contains("ScrollView") -> 500
            className.endsWith("LinearLayout") -> 350
            else -> 250
        }
        return classScore + depth
    }

    internal fun actionName(action: Int): String {
        return when (action) {
            actionIds.scrollForward -> "ACTION_SCROLL_FORWARD"
            actionIds.scrollBackward -> "ACTION_SCROLL_BACKWARD"
            actionIds.click -> "ACTION_CLICK"
            actionIds.scrollDown -> "ACTION_SCROLL_DOWN"
            actionIds.scrollUp -> "ACTION_SCROLL_UP"
            actionIds.pageDown -> "ACTION_PAGE_DOWN"
            actionIds.pageUp -> "ACTION_PAGE_UP"
            else -> "ACTION_$action"
        }
    }

    internal fun describeNode(node: N): String {
        val attrs = attributes(node)
        return buildString {
            append(attrs.className.orEmpty())
            append('[')
            append(attrs.viewIdResourceName.orEmpty())
            append(']')
            append('@')
            append(attrs.boundsShortString)
        }
    }

    private fun attemptActionOnCandidates(
        candidates: List<N>,
        requestedAction: Int,
        source: String,
    ): Boolean {
        candidates.forEach { candidate ->
            val actionIdsToTry = preferredActionIds(candidate, requestedAction)
            actionIdsToTry.forEach { actionId ->
                val success = performAction(candidate, actionId)
                logger()?.info(
                    "live_action_attempt requestedAction=${actionName(requestedAction)} source=$source " +
                        "candidate=${quoteForLog(describeNode(candidate))} actionId=${actionName(actionId)} success=$success"
                )
                diagnostics(
                    "Tried ${actionName(actionId)} on ${describeNode(candidate)} from $source candidate; success=$success"
                )
                if (success) {
                    return true
                }
            }
        }
        return false
    }

    private fun resolveLiveLabel(node: N): String? {
        val attrs = attributes(node)
        val directText = attrs.text?.trim().orEmpty()
        if (directText.isNotEmpty()) {
            return directText
        }

        val directDescription = attrs.contentDescription?.trim().orEmpty()
        if (directDescription.isNotEmpty()) {
            return directDescription
        }

        findNestedTitleLabel(node)?.let { return it }
        return findNestedTextLabel(node)
    }

    private fun findNestedTitleLabel(node: N): String? {
        repeat(childCount(node)) { index ->
            val child = childAt(node, index) ?: return@repeat
            if (attributes(child).viewIdResourceName?.substringAfterLast('/') == "title") {
                val label = resolveLiveLabel(child)
                if (!label.isNullOrBlank()) {
                    return label
                }
            }
            findNestedTitleLabel(child)?.let { return it }
        }
        return null
    }

    private fun findNestedTextLabel(node: N): String? {
        repeat(childCount(node)) { index ->
            val child = childAt(node, index) ?: return@repeat
            val label = resolveLiveLabel(child)
            if (!label.isNullOrBlank()) {
                return label
            }
            findNestedTextLabel(child)?.let { return it }
        }
        return null
    }

    private fun resolvePathNodes(
        root: N,
        childIndexPath: List<Int>,
    ): PathReplayResolver.Resolution<N> {
        return PathReplayResolver.resolve(
            root = root,
            childIndexPath = childIndexPath,
            childCount = childCount,
            childAt = childAt,
        )
    }

    private fun logPathDivergenceIfNeeded(
        action: Int,
        resolution: PathReplayResolver.Resolution<N>,
    ) {
        if (resolution.status == PathReplayResolver.ResolutionStatus.FULL) {
            return
        }

        logger()?.warn(
            "live_action_path_diverged type=${actionName(action)} intendedChildIndexPath=${resolution.intendedPath} " +
                "resolvedDepth=${resolution.resolvedDepth} failingChildIndex=${resolution.failingChildIndex} " +
                "availableChildCount=${resolution.availableChildCount} status=${resolution.status.name.lowercase()}"
        )
        diagnostics(
            "Accessibility path replay diverged for ${actionName(action)} path=${resolution.intendedPath} " +
                "resolvedDepth=${resolution.resolvedDepth} failingChildIndex=${resolution.failingChildIndex} " +
                "availableChildCount=${resolution.availableChildCount}."
        )
    }

    private fun formatEligibilityReasons(
        matches: List<ClickFallbackMatcher.Match<N>>,
    ): String {
        if (matches.isEmpty()) {
            return ""
        }
        return matches
            .groupingBy { it.eligibilityReason.name.lowercase() }
            .eachCount()
            .entries
            .joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private fun clickFallbackTargetFor(element: PressableElement): ClickFallbackMatcher.Target {
        return ClickFallbackMatcher.Target(
            fingerprint = ElementFingerprint.of(element),
        )
    }

    private fun quoteForLog(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
    }

    private data class ScoredCandidate<N>(
        val node: N,
        val score: Int,
    )

    internal data class LiveClickCandidate<N>(
        val node: N,
        val candidate: ClickFallbackMatcher.Candidate<N>,
    )
}
