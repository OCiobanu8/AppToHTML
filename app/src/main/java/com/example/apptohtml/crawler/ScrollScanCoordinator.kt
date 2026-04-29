package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef

internal class ScrollScanCoordinator(
    private val maxAdditionalScrolls: Int = 8,
    private val maxViewportSettleCaptures: Int = 2,
    private val maxScrollToTopAttempts: Int = 12,
    private val maxBackToEntryAttempts: Int = 12,
) {
    suspend fun scan(
        selectedApp: SelectedAppRef,
        eventClassName: String?,
        initialRoot: AccessibilityNodeSnapshot,
        tryScrollForward: (List<Int>) -> Boolean,
        tryScrollBackward: (List<Int>) -> Boolean,
        captureCurrentRoot: suspend () -> AccessibilityNodeSnapshot?,
        onProgress: (String) -> Unit = {},
    ): ScreenSnapshot {
        var currentRoot = settleViewport(initialRoot, captureCurrentRoot)
        currentRoot = rewindToTop(
            selectedApp = selectedApp,
            initialRoot = currentRoot,
            tryScrollBackward = tryScrollBackward,
            captureCurrentRoot = captureCurrentRoot,
            onProgress = onProgress,
        )
        val accumulator = ScrollScanAccumulator(
            selectedApp = selectedApp,
            eventClassName = eventClassName,
            initialRoot = currentRoot,
        )
        accumulator.addStep(currentRoot)

        for (attempt in 1..maxAdditionalScrolls) {
            val scrollPath = AccessibilityTreeSnapshotter.findPrimaryScrollableNodePath(
                root = currentRoot,
                targetPackageName = selectedApp.packageName,
            ) ?: break

            onProgress(
                "Scanning scrollable content. Captured ${accumulator.stepCount} step(s) so far."
            )

            if (!tryScrollForward(scrollPath)) {
                break
            }

            val nextRoot = captureCurrentRoot() ?: break
            val settledRoot = settleViewport(nextRoot, captureCurrentRoot)
            val viewportChanged = geometrySensitiveViewportFingerprint(settledRoot) !=
                geometrySensitiveViewportFingerprint(currentRoot)
            currentRoot = settledRoot

            if (!viewportChanged) {
                break
            }

            val newElements = accumulator.addStep(currentRoot)

            onProgress(
                if (newElements > 0) {
                    "Scanning scrollable content. Step ${attempt + 1} added $newElements new element(s)."
                } else {
                    "Scanning scrollable content. Step ${attempt + 1} added no new elements."
                }
            )
        }

        return accumulator.build()
    }

    suspend fun rewindToTop(
        selectedApp: SelectedAppRef,
        initialRoot: AccessibilityNodeSnapshot,
        tryScrollBackward: (List<Int>) -> Boolean,
        captureCurrentRoot: suspend () -> AccessibilityNodeSnapshot?,
        onProgress: (String) -> Unit,
    ): AccessibilityNodeSnapshot {
        var currentRoot = initialRoot

        for (attempt in 1..maxScrollToTopAttempts) {
            val scrollPath = AccessibilityTreeSnapshotter.findPrimaryScrollableNodePath(
                root = currentRoot,
                targetPackageName = selectedApp.packageName,
            ) ?: return currentRoot

            onProgress("Preparing scroll scan. Moving to the top of the screen.")

            if (!tryScrollBackward(scrollPath)) {
                return currentRoot
            }

            val nextRoot = captureCurrentRoot() ?: return currentRoot
            val settledRoot = settleViewport(nextRoot, captureCurrentRoot)
            if (
                geometrySensitiveViewportFingerprint(settledRoot) ==
                geometrySensitiveViewportFingerprint(currentRoot)
            ) {
                return currentRoot
            }
            currentRoot = settledRoot
        }

        return currentRoot
    }

    suspend fun rewindToEntryScreen(
        initialRoot: AccessibilityNodeSnapshot,
        targetPackageName: String,
        expectedEntryLogicalFingerprint: String? = null,
        tryBack: () -> Boolean,
        captureCurrentRoot: suspend () -> AccessibilityNodeSnapshot?,
        onProgress: (String) -> Unit = {},
    ): EntryScreenResetResult {
        var currentRoot = initialRoot
        var backAttempts = 0

        while (true) {
            val observedLogicalFingerprint = logicalEntryViewportFingerprint(currentRoot)
            if (expectedEntryLogicalFingerprint != null &&
                observedLogicalFingerprint == expectedEntryLogicalFingerprint
            ) {
                onProgress("Resetting to the first screen. Matched the captured logical entry screen.")
                return entryScreenResetResult(
                    root = currentRoot,
                    outcome = EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            }

            if (!EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(currentRoot)) {
                onProgress("Resetting to the first screen. No visible in-app back button was found.")
                val outcome = if (expectedEntryLogicalFingerprint == null) {
                    EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY
                } else {
                    EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND
                }
                return entryScreenResetResult(
                    root = currentRoot,
                    outcome = outcome,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            }

            if (backAttempts >= maxBackToEntryAttempts) {
                return entryScreenResetResult(
                    root = currentRoot,
                    outcome = EntryScreenResetOutcome.MAX_ATTEMPTS_REACHED,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            }

            onProgress("Resetting to the first screen. Back attempt ${backAttempts + 1} of $maxBackToEntryAttempts.")

            if (!tryBack()) {
                return entryScreenResetResult(
                    root = currentRoot,
                    outcome = EntryScreenResetOutcome.BACK_ACTION_FAILED,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            }
            backAttempts += 1

            val nextRoot = captureCurrentRoot()
                ?: return entryScreenResetResult(
                    root = currentRoot,
                    outcome = EntryScreenResetOutcome.LEFT_TARGET_APP,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            if (nextRoot.packageName != targetPackageName) {
                return entryScreenResetResult(
                    root = currentRoot,
                    outcome = EntryScreenResetOutcome.LEFT_TARGET_APP,
                    observedLogicalFingerprint = observedLogicalFingerprint,
                    expectedLogicalFingerprint = expectedEntryLogicalFingerprint,
                )
            }

            val settledRoot = settleViewport(nextRoot) {
                captureCurrentRoot()?.takeIf { candidate ->
                    candidate.packageName == targetPackageName
                }
            }
            currentRoot = settledRoot
        }
    }

    suspend fun settleViewport(
        initialRoot: AccessibilityNodeSnapshot,
        captureCurrentRoot: suspend () -> AccessibilityNodeSnapshot?,
    ): AccessibilityNodeSnapshot {
        var bestRoot = initialRoot
        var bestCount = visiblePressableCount(initialRoot)
        var staleCaptures = 0

        repeat(maxViewportSettleCaptures) {
            val candidate = captureCurrentRoot() ?: return bestRoot
            val candidateCount = visiblePressableCount(candidate)

            if (candidateCount > bestCount) {
                bestRoot = candidate
                bestCount = candidateCount
                staleCaptures = 0
            } else {
                staleCaptures += 1
            }

            if (staleCaptures >= 1) {
                return bestRoot
            }
        }

        return bestRoot
    }

    suspend fun moveToStep(
        selectedApp: SelectedAppRef,
        initialRoot: AccessibilityNodeSnapshot,
        targetStepIndex: Int,
        tryScrollForward: (List<Int>) -> Boolean,
        captureCurrentRoot: suspend () -> AccessibilityNodeSnapshot?,
        onProgress: (String) -> Unit = {},
    ): AccessibilityNodeSnapshot? {
        var currentRoot = initialRoot
        if (targetStepIndex <= 0) {
            return currentRoot
        }

        repeat(targetStepIndex) { step ->
            val scrollPath = AccessibilityTreeSnapshotter.findPrimaryScrollableNodePath(
                root = currentRoot,
                targetPackageName = selectedApp.packageName,
            ) ?: return null

            onProgress("Preparing child traversal. Replaying root scroll step ${step + 1} of $targetStepIndex.")
            if (!tryScrollForward(scrollPath)) {
                return null
            }

            val nextRoot = captureCurrentRoot() ?: return null
            currentRoot = settleViewport(nextRoot, captureCurrentRoot)
        }

        return currentRoot
    }

    private fun entryScreenResetResult(
        root: AccessibilityNodeSnapshot,
        outcome: EntryScreenResetOutcome,
        observedLogicalFingerprint: String,
        expectedLogicalFingerprint: String?,
    ): EntryScreenResetResult {
        val matchedExpectedLogical = expectedLogicalFingerprint != null &&
            observedLogicalFingerprint == expectedLogicalFingerprint
        return EntryScreenResetResult(
            root = root,
            outcome = outcome,
            observedLogicalFingerprint = observedLogicalFingerprint,
            expectedLogicalFingerprint = expectedLogicalFingerprint,
            matchedExpectedLogical = matchedExpectedLogical,
            verifiedForReplay = outcome == EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL ||
                outcome == EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY,
        )
    }

    private fun visiblePressableCount(root: AccessibilityNodeSnapshot): Int {
        return AccessibilityTreeSnapshotter.collectPressableElements(root)
            .distinctBy(::mergedElementFingerprint)
            .size
    }

    fun geometrySensitiveViewportFingerprint(root: AccessibilityNodeSnapshot): String {
        return buildViewportFingerprint(
            root = root,
            includeBounds = true,
            excludeEntryBackAffordances = false,
        )
    }

    fun logicalViewportFingerprint(root: AccessibilityNodeSnapshot): String {
        return buildViewportFingerprint(
            root = root,
            includeBounds = false,
            excludeEntryBackAffordances = false,
        )
    }

    fun geometrySensitiveEntryViewportFingerprint(root: AccessibilityNodeSnapshot): String {
        return buildViewportFingerprint(
            root = root,
            includeBounds = true,
            excludeEntryBackAffordances = true,
        )
    }

    fun logicalEntryViewportFingerprint(root: AccessibilityNodeSnapshot): String {
        return buildViewportFingerprint(
            root = root,
            includeBounds = false,
            excludeEntryBackAffordances = true,
        )
    }

    private fun mergedElementFingerprint(element: PressableElement): String {
        return listOf(
            element.label,
            element.resourceId.orEmpty(),
            element.className.orEmpty(),
            element.isListItem.toString(),
            element.checkable.toString(),
            element.editable.toString(),
        ).joinToString("|")
    }

    private fun buildViewportFingerprint(
        root: AccessibilityNodeSnapshot,
        includeBounds: Boolean,
        excludeEntryBackAffordances: Boolean,
    ): String {
        val elements = AccessibilityTreeSnapshotter.collectPressableElements(root)
            .distinctBy(::mergedElementFingerprint)
            .let { elements ->
                if (excludeEntryBackAffordances) {
                    elements.filterNot(::looksLikeEntryBackAffordance)
                } else {
                    elements
                }
            }
            .map { element ->
                buildElementFingerprint(
                    element = element,
                    includeBounds = includeBounds,
                )
            }
            .sorted()

        return buildString {
            append(root.className.orEmpty())
            if (includeBounds) {
                append("::")
                append(root.bounds)
            }
            append("::")
            append(elements.joinToString("||"))
        }
    }

    private fun buildElementFingerprint(
        element: PressableElement,
        includeBounds: Boolean,
    ): String {
        return buildList {
            add(element.label)
            add(element.resourceId.orEmpty())
            add(element.className.orEmpty())
            add(element.isListItem.toString())
            add(element.checkable.toString())
            add(element.checked.toString())
            add(element.editable.toString())
            if (includeBounds) {
                add(element.bounds)
            }
        }.joinToString("|")
    }

    private fun looksLikeEntryBackAffordance(element: PressableElement): Boolean {
        val normalizedLabel = normalizeFingerprintToken(element.label)
        val normalizedResourceId = normalizeFingerprintToken(element.resourceId)
        val hasBackSignal = normalizedResourceId.contains("back") ||
            normalizedResourceId.contains("navigate up") ||
            normalizedResourceId.contains("navigateup") ||
            normalizedResourceId.contains("up button") ||
            normalizedResourceId.contains("upbutton") ||
            normalizedResourceId.contains("nav button") ||
            normalizedResourceId.contains("navbutton") ||
            normalizedLabel == "navigate up" ||
            normalizedLabel == "go back" ||
            normalizedLabel == "navigate back" ||
            normalizedLabel == "back" ||
            normalizedLabel == "up"
        if (!hasBackSignal) {
            return false
        }

        val top = parseFingerprintBounds(element.bounds)?.top ?: return false
        return top <= 300
    }

    private fun normalizeFingerprintToken(value: String?): String {
        return value
            ?.substringAfterLast('/')
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun parseFingerprintBounds(bounds: String): FingerprintBounds? {
        val match = BOUNDS_REGEX.matchEntire(bounds) ?: return null
        return FingerprintBounds(
            top = match.groupValues[2].toInt(),
        )
    }

    private data class FingerprintBounds(
        val top: Int,
    )

    private companion object {
        val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}

internal data class EntryScreenResetResult(
    val root: AccessibilityNodeSnapshot,
    val outcome: EntryScreenResetOutcome,
    val observedLogicalFingerprint: String,
    val expectedLogicalFingerprint: String?,
    val matchedExpectedLogical: Boolean,
    val verifiedForReplay: Boolean,
)

internal enum class EntryScreenResetOutcome {
    MATCHED_EXPECTED_LOGICAL,
    NO_BACK_AFFORDANCE_ASSUMED_ENTRY,
    EXPECTED_LOGICAL_NOT_FOUND,
    BACK_ACTION_FAILED,
    LEFT_TARGET_APP,
    MAX_ATTEMPTS_REACHED,
}

internal object EntryScreenBackAffordanceDetector {
    fun hasVisibleInAppBackAffordance(root: AccessibilityNodeSnapshot): Boolean {
        val rootBounds = parseBounds(root.bounds)
        return hasVisibleInAppBackAffordance(root, ancestorChain = emptyList(), rootBounds = rootBounds)
    }

    private fun hasVisibleInAppBackAffordance(
        node: AccessibilityNodeSnapshot,
        ancestorChain: List<AccessibilityNodeSnapshot>,
        rootBounds: ParsedBounds?,
    ): Boolean {
        if (isLikelyInAppBackAffordance(node, ancestorChain, rootBounds)) {
            return true
        }
        val nextAncestors = ancestorChain + node
        return node.children.any { child ->
            hasVisibleInAppBackAffordance(child, nextAncestors, rootBounds)
        }
    }

    private fun isLikelyInAppBackAffordance(
        node: AccessibilityNodeSnapshot,
        ancestorChain: List<AccessibilityNodeSnapshot>,
        rootBounds: ParsedBounds?,
    ): Boolean {
        if (!node.visibleToUser || !node.enabled || (!node.clickable && !node.supportsClickAction)) {
            return false
        }

        val normalizedLabel = normalize(resolveBackAffordanceLabel(node))
        val normalizedResourceId = normalize(node.viewIdResourceName)
        val hasResourceSignal = normalizedResourceId.contains("back") ||
            normalizedResourceId.contains("navigate up") ||
            normalizedResourceId.contains("navigateup") ||
            normalizedResourceId.contains("up button") ||
            normalizedResourceId.contains("upbutton") ||
            normalizedResourceId.contains("nav button") ||
            normalizedResourceId.contains("navbutton")
        val hasStrongLabelSignal = normalizedLabel == "navigate up" ||
            normalizedLabel == "go back" ||
            normalizedLabel == "navigate back"
        val hasWeakLabelSignal = normalizedLabel == "back" || normalizedLabel == "up"
        if (!hasResourceSignal && !hasStrongLabelSignal && !hasWeakLabelSignal) {
            return false
        }

        val toolbarContext = (ancestorChain + node).any(::isToolbarLikeNode)
        val topAligned = parseBounds(node.bounds)?.top?.let { top ->
            val rootTop = rootBounds?.top ?: 0
            top <= rootTop + 300
        } ?: false
        if (!toolbarContext && !topAligned) {
            return false
        }

        return hasResourceSignal || hasStrongLabelSignal || (hasWeakLabelSignal && toolbarContext)
    }

    private fun resolveBackAffordanceLabel(node: AccessibilityNodeSnapshot): String? {
        directLabel(node)?.let { return it }
        findNestedTitleLabel(node.children)?.let { return it }
        return findNestedTextLabel(node.children)
    }

    private fun directLabel(node: AccessibilityNodeSnapshot): String? {
        val text = node.text?.trim().orEmpty()
        if (text.isNotEmpty()) return text

        val contentDescription = node.contentDescription?.trim().orEmpty()
        if (contentDescription.isNotEmpty()) return contentDescription

        return null
    }

    private fun findNestedTitleLabel(children: List<AccessibilityNodeSnapshot>): String? {
        children.forEach { child ->
            if (child.viewIdResourceName?.substringAfterLast('/') == "title") {
                directLabel(child)?.let { return it }
            }
            findNestedTitleLabel(child.children)?.let { return it }
        }
        return null
    }

    private fun findNestedTextLabel(children: List<AccessibilityNodeSnapshot>): String? {
        children.forEach { child ->
            directLabel(child)?.let { return it }
            findNestedTextLabel(child.children)?.let { return it }
        }
        return null
    }

    private fun isToolbarLikeNode(node: AccessibilityNodeSnapshot): Boolean {
        val className = normalize(node.className)
        val resourceId = normalize(node.viewIdResourceName)
        return className.contains("toolbar") ||
            className.contains("actionbar") ||
            className.contains("appbar") ||
            className.contains("topappbar") ||
            resourceId.contains("toolbar") ||
            resourceId.contains("action bar") ||
            resourceId.contains("actionbar") ||
            resourceId.contains("app bar") ||
            resourceId.contains("appbar")
    }

    private fun normalize(value: String?): String {
        return value
            ?.substringAfterLast('/')
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun parseBounds(bounds: String): ParsedBounds? {
        val match = BOUNDS_REGEX.matchEntire(bounds) ?: return null
        return ParsedBounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
        )
    }

    private data class ParsedBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
}

internal class ScrollScanAccumulator(
    selectedApp: SelectedAppRef,
    eventClassName: String?,
    initialRoot: AccessibilityNodeSnapshot,
) {
    private val screenName = ScreenNaming.deriveScreenName(
        eventClassName = eventClassName,
        selectedApp = selectedApp,
        root = initialRoot,
    )
    private val packageName = initialRoot.packageName ?: selectedApp.packageName
    private val mergedElements = LinkedHashMap<MergedElementKey, PressableElement>()
    private val stepSnapshots = mutableListOf<ScrollCaptureStep>()

    val stepCount: Int
        get() = stepSnapshots.size

    fun addStep(root: AccessibilityNodeSnapshot): Int {
        val stepIndex = stepSnapshots.size
        val stepElements = AccessibilityTreeSnapshotter.collectPressableElements(root)
            .distinctBy(::toMergedKey)

        var newElementCount = 0
        stepElements.forEach { element ->
            val key = toMergedKey(element)
            if (mergedElements.containsKey(key)) {
                return@forEach
            }

            mergedElements[key] = element.copy(firstSeenStep = stepIndex)
            newElementCount += 1
        }

        stepSnapshots += ScrollCaptureStep(
            stepIndex = stepIndex,
            root = root,
            newElementCount = newElementCount,
        )

        return newElementCount
    }

    fun build(): ScreenSnapshot {
        val baseSnapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = packageName,
            elements = mergedElements.values.toList(),
            xmlDump = "",
            stepSnapshots = stepSnapshots.toList(),
            scrollStepCount = stepSnapshots.size.coerceAtLeast(1),
        )
        val mergedRoot = SyntheticAccessibilityTreeBuilder.build(baseSnapshot)
        val xmlDump = AccessibilityXmlSerializer.serialize(baseSnapshot)
        val mergedXmlDump = mergedRoot?.let { root ->
            AccessibilityXmlSerializer.serialize(
                screenName = screenName,
                packageName = packageName,
                root = root,
            )
        }
        return baseSnapshot.copy(
            xmlDump = xmlDump,
            mergedRoot = mergedRoot,
            mergedXmlDump = mergedXmlDump,
        )
    }

    private fun toMergedKey(element: PressableElement): MergedElementKey {
        return MergedElementKey(
            label = element.label,
            resourceId = element.resourceId,
            className = element.className,
            isListItem = element.isListItem,
            checkable = element.checkable,
            checked = element.checked,
            editable = element.editable,
        )
    }

    private data class MergedElementKey(
        val label: String,
        val resourceId: String?,
        val className: String?,
        val isListItem: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val editable: Boolean,
    )
}
