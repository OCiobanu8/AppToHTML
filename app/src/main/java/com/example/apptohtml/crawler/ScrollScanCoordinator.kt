package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef

internal class ScrollScanCoordinator(
    private val maxAdditionalScrolls: Int = 8,
    private val maxViewportSettleCaptures: Int = 2,
    private val maxScrollToTopAttempts: Int = 12,
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
        currentRoot = scrollToTop(
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
            val viewportChanged = viewportFingerprint(settledRoot) != viewportFingerprint(currentRoot)
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

    private suspend fun scrollToTop(
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
            if (viewportFingerprint(settledRoot) == viewportFingerprint(currentRoot)) {
                return currentRoot
            }
            currentRoot = settledRoot
        }

        return currentRoot
    }

    private suspend fun settleViewport(
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

    private fun visiblePressableCount(root: AccessibilityNodeSnapshot): Int {
        return AccessibilityTreeSnapshotter.collectPressableElements(root)
            .distinctBy(::mergedElementFingerprint)
            .size
    }

    private fun viewportFingerprint(root: AccessibilityNodeSnapshot): String {
        val elements = AccessibilityTreeSnapshotter.collectPressableElements(root)
            .distinctBy(::mergedElementFingerprint)
            .map { element ->
                listOf(
                    element.label,
                    element.resourceId.orEmpty(),
                    element.className.orEmpty(),
                    element.isListItem.toString(),
                    element.bounds,
                ).joinToString("|")
            }
            .sorted()

        return buildString {
            append(root.className.orEmpty())
            append("::")
            append(root.bounds)
            append("::")
            append(elements.joinToString("||"))
        }
    }

    private fun mergedElementFingerprint(element: PressableElement): String {
        return listOf(
            element.label,
            element.resourceId.orEmpty(),
            element.className.orEmpty(),
            element.isListItem.toString(),
        ).joinToString("|")
    }
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
    private val packageName = selectedApp.packageName
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
        val snapshot = ScreenSnapshot(
            screenName = screenName,
            packageName = packageName,
            elements = mergedElements.values.toList(),
            xmlDump = "",
            stepSnapshots = stepSnapshots.toList(),
            scrollStepCount = stepSnapshots.size.coerceAtLeast(1),
        )
        return snapshot.copy(xmlDump = AccessibilityXmlSerializer.serialize(snapshot))
    }

    private fun toMergedKey(element: PressableElement): MergedElementKey {
        return MergedElementKey(
            label = element.label,
            resourceId = element.resourceId,
            className = element.className,
            isListItem = element.isListItem,
        )
    }

    private data class MergedElementKey(
        val label: String,
        val resourceId: String?,
        val className: String?,
        val isListItem: Boolean,
    )
}
