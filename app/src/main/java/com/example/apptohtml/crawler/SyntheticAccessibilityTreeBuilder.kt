package com.example.apptohtml.crawler

import kotlin.math.max
import kotlin.math.min

internal object SyntheticAccessibilityTreeBuilder {
    fun build(snapshot: ScreenSnapshot): AccessibilityNodeSnapshot? {
        val stepRoots = snapshot.stepSnapshots
            .sortedBy { it.stepIndex }
            .map { it.root }
        if (stepRoots.isEmpty()) {
            return null
        }

        val allStepIndices = snapshot.stepSnapshots.map { it.stepIndex }
        val scrollPath = dominantScrollPath(stepRoots, snapshot.packageName)
        if (scrollPath == null) {
            return annotateSynthetic(stepRoots.first(), allStepIndices)
        }

        val mergedScrollContainer = mergeScrollContainer(
            stepRoots = stepRoots,
            scrollPath = scrollPath,
            allStepIndices = allStepIndices,
        ) ?: return annotateSynthetic(stepRoots.first(), allStepIndices)

        return replaceNodeAtPath(
            node = annotateSynthetic(stepRoots.first(), allStepIndices),
            path = scrollPath,
            replacement = mergedScrollContainer,
            allStepIndices = allStepIndices,
        )
    }

    private fun dominantScrollPath(
        stepRoots: List<AccessibilityNodeSnapshot>,
        targetPackageName: String,
    ): List<Int>? {
        return stepRoots
            .mapNotNull { root ->
                AccessibilityTreeSnapshotter.findPrimaryScrollableNodePath(root, targetPackageName)
            }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(
                compareBy<Map.Entry<List<Int>, Int>> { it.value }
                    .thenByDescending { it.key.size }
            )
            ?.key
    }

    private fun mergeScrollContainer(
        stepRoots: List<AccessibilityNodeSnapshot>,
        scrollPath: List<Int>,
        allStepIndices: List<Int>,
    ): AccessibilityNodeSnapshot? {
        val stepContainers = stepRoots.mapIndexedNotNull { index, root ->
            resolveNodeAtPath(root, scrollPath)?.let { index to it }
        }
        if (stepContainers.isEmpty()) {
            return null
        }

        val orderedChildren = LinkedHashMap<ChildMergeKey, AccessibilityNodeSnapshot>()
        stepContainers.forEach { (stepIndex, container) ->
            container.children
                .sortedBy { child -> parseBounds(child.bounds)?.top ?: Int.MAX_VALUE }
                .forEach { child ->
                    val key = childMergeKey(child)
                    val existing = orderedChildren[key]
                    val mergedChild = if (existing == null) {
                        annotateSynthetic(child, listOf(stepIndex))
                            .copy(firstSeenStep = stepIndex)
                    } else {
                        mergeNodes(existing, child, stepIndex)
                    }
                    orderedChildren[key] = mergedChild
                }
        }

        val baseContainer = stepContainers.first().second
        val baseBounds = parseBounds(baseContainer.bounds)
        val stackedChildren = if (baseBounds == null) {
            orderedChildren.values.toList()
        } else {
            var cursorTop = baseBounds.top
            orderedChildren.values.map { child ->
                val childBounds = parseBounds(child.bounds)
                if (childBounds == null) {
                    child
                } else {
                    val shifted = shiftNode(child, cursorTop - childBounds.top)
                    cursorTop += childBounds.height.coerceAtLeast(1)
                    shifted
                }
            }
        }

        val containerBounds = expandedBounds(
            original = baseContainer.bounds,
            children = stackedChildren,
        )

        return annotateSynthetic(baseContainer, allStepIndices).copy(
            bounds = containerBounds,
            children = stackedChildren,
            synthetic = true,
            merged = stepContainers.size > 1 || orderedChildren.size != baseContainer.children.size,
            syntheticScrollContainer = true,
            sourceStepIndices = allStepIndices,
            firstSeenStep = 0,
        )
    }

    private fun annotateSynthetic(
        node: AccessibilityNodeSnapshot,
        stepIndices: List<Int>,
    ): AccessibilityNodeSnapshot {
        val normalizedSteps = stepIndices.distinct().sorted()
        return node.copy(
            children = node.children.map { child -> annotateSynthetic(child, normalizedSteps) },
            synthetic = true,
            merged = node.merged || normalizedSteps.size > 1,
            sourceStepIndices = normalizedSteps,
            firstSeenStep = node.firstSeenStep ?: normalizedSteps.firstOrNull(),
        )
    }

    private fun mergeNodes(
        existing: AccessibilityNodeSnapshot,
        incoming: AccessibilityNodeSnapshot,
        stepIndex: Int,
    ): AccessibilityNodeSnapshot {
        val mergedSteps = (existing.sourceStepIndices + stepIndex).distinct().sorted()
        val mergedChildren = LinkedHashMap<ChildMergeKey, AccessibilityNodeSnapshot>()
        existing.children.forEach { child ->
            mergedChildren[childMergeKey(child)] = child
        }
        incoming.children.forEach { child ->
            val key = childMergeKey(child)
            val prior = mergedChildren[key]
            mergedChildren[key] = if (prior == null) {
                annotateSynthetic(child, listOf(stepIndex)).copy(firstSeenStep = stepIndex)
            } else {
                mergeNodes(prior, child, stepIndex)
            }
        }

        return existing.copy(
            packageName = existing.packageName ?: incoming.packageName,
            viewIdResourceName = existing.viewIdResourceName ?: incoming.viewIdResourceName,
            text = preferredString(existing.text, incoming.text),
            contentDescription = preferredString(existing.contentDescription, incoming.contentDescription),
            clickable = existing.clickable || incoming.clickable,
            supportsClickAction = existing.supportsClickAction || incoming.supportsClickAction,
            scrollable = existing.scrollable || incoming.scrollable,
            checkable = existing.checkable || incoming.checkable,
            checked = existing.checked || incoming.checked,
            enabled = existing.enabled || incoming.enabled,
            visibleToUser = existing.visibleToUser || incoming.visibleToUser,
            children = mergedChildren.values.toList(),
            synthetic = true,
            merged = true,
            sourceStepIndices = mergedSteps,
            firstSeenStep = minOfNullable(existing.firstSeenStep, stepIndex),
        )
    }

    private fun replaceNodeAtPath(
        node: AccessibilityNodeSnapshot,
        path: List<Int>,
        replacement: AccessibilityNodeSnapshot,
        allStepIndices: List<Int>,
    ): AccessibilityNodeSnapshot {
        if (path.isEmpty()) {
            return replacement
        }

        val index = path.first()
        if (index !in node.children.indices) {
            return node
        }

        val updatedChildren = node.children.mapIndexed { childIndex, child ->
            if (childIndex == index) {
                replaceNodeAtPath(child, path.drop(1), replacement, allStepIndices)
            } else {
                child
            }
        }

        return node.copy(
            children = updatedChildren,
            bounds = expandedBounds(node.bounds, updatedChildren),
            synthetic = true,
            merged = true,
            sourceStepIndices = allStepIndices,
            firstSeenStep = allStepIndices.firstOrNull(),
        )
    }

    private fun resolveNodeAtPath(
        root: AccessibilityNodeSnapshot,
        path: List<Int>,
    ): AccessibilityNodeSnapshot? {
        var current: AccessibilityNodeSnapshot? = root
        path.forEach { childIndex ->
            current = current?.children?.getOrNull(childIndex) ?: return null
        }
        return current
    }

    private fun shiftNode(
        node: AccessibilityNodeSnapshot,
        deltaY: Int,
    ): AccessibilityNodeSnapshot {
        val shiftedBounds = shiftBounds(node.bounds, deltaY)
        return node.copy(
            bounds = shiftedBounds ?: node.bounds,
            children = node.children.map { child -> shiftNode(child, deltaY) },
            synthetic = true,
        )
    }

    private fun expandedBounds(
        original: String,
        children: List<AccessibilityNodeSnapshot>,
    ): String {
        val originalBounds = parseBounds(original) ?: return original
        val childBounds = children.mapNotNull { child -> parseBounds(child.bounds) }
        if (childBounds.isEmpty()) {
            return original
        }

        val expanded = childBounds.fold(originalBounds) { acc, bounds ->
            Bounds(
                left = min(acc.left, bounds.left),
                top = min(acc.top, bounds.top),
                right = max(acc.right, bounds.right),
                bottom = max(acc.bottom, bounds.bottom),
            )
        }
        return expanded.toBoundsString()
    }

    private fun preferredString(
        primary: String?,
        fallback: String?,
    ): String? {
        return primary?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
    }

    private fun childMergeKey(node: AccessibilityNodeSnapshot): ChildMergeKey {
        val bounds = parseBounds(node.bounds)
        return ChildMergeKey(
            className = node.className.orEmpty(),
            resourceId = node.viewIdResourceName.orEmpty(),
            primaryLabel = primaryLabel(node),
            clickState = "${node.clickable}|${node.supportsClickAction}|${node.checkable}|${node.checked}",
            geometryBand = bounds?.geometryBand().orEmpty(),
            descendantSignature = descendantSignature(node),
        )
    }

    private fun primaryLabel(node: AccessibilityNodeSnapshot): String {
        val directText = node.text?.trim().orEmpty()
        if (directText.isNotEmpty()) {
            return directText
        }
        val directDescription = node.contentDescription?.trim().orEmpty()
        if (directDescription.isNotEmpty()) {
            return directDescription
        }

        return descendantText(node)
    }

    private fun descendantText(node: AccessibilityNodeSnapshot): String {
        node.children.forEach { child ->
            val direct = child.text?.trim().orEmpty()
            if (direct.isNotEmpty()) {
                return direct
            }
            val description = child.contentDescription?.trim().orEmpty()
            if (description.isNotEmpty()) {
                return description
            }
            val nested = descendantText(child)
            if (nested.isNotEmpty()) {
                return nested
            }
        }
        return ""
    }

    private fun descendantSignature(node: AccessibilityNodeSnapshot): String {
        val parts = mutableListOf<String>()

        fun walk(current: AccessibilityNodeSnapshot) {
            val label = current.text?.trim()
                ?: current.contentDescription?.trim()
                ?: current.viewIdResourceName?.substringAfterLast('/')
            if (!label.isNullOrBlank()) {
                parts += "${current.className.orEmpty()}:$label"
            }
            current.children.forEach(::walk)
        }

        node.children.forEach(::walk)
        return parts.joinToString("|")
    }

    private fun shiftBounds(
        bounds: String,
        deltaY: Int,
    ): String? {
        return parseBounds(bounds)?.let { parsed ->
            parsed.copy(
                top = parsed.top + deltaY,
                bottom = parsed.bottom + deltaY,
            ).toBoundsString()
        }
    }

    private fun parseBounds(bounds: String): Bounds? {
        val match = BOUNDS_REGEX.matchEntire(bounds) ?: return null
        return Bounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
            right = match.groupValues[3].toInt(),
            bottom = match.groupValues[4].toInt(),
        )
    }

    private fun minOfNullable(
        existing: Int?,
        incoming: Int,
    ): Int {
        return existing?.let { min(it, incoming) } ?: incoming
    }

    private data class ChildMergeKey(
        val className: String,
        val resourceId: String,
        val primaryLabel: String,
        val clickState: String,
        val geometryBand: String,
        val descendantSignature: String,
    )

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val height: Int
            get() = (bottom - top).coerceAtLeast(0)

        fun geometryBand(): String {
            val width = (right - left).coerceAtLeast(0)
            return listOf(left / 10, width / 10, height / 10).joinToString(":")
        }

        fun toBoundsString(): String = "[$left,$top][$right,$bottom]"
    }

    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
}
