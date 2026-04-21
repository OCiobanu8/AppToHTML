package com.example.apptohtml.crawler

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.apptohtml.model.SelectedAppRef

object AccessibilityTreeSnapshotter {
    fun capture(
        root: AccessibilityNodeInfo,
        selectedApp: SelectedAppRef,
        eventClassName: String?,
    ): ScreenSnapshot {
        val rootSnapshot = captureRootSnapshot(root)
        return buildMergedScreenSnapshot(
            selectedApp = selectedApp,
            eventClassName = eventClassName,
            stepRoots = listOf(rootSnapshot),
        )
    }

    fun captureRootSnapshot(root: AccessibilityNodeInfo): AccessibilityNodeSnapshot {
        return snapshotNode(root, childIndexPath = emptyList())
    }

    internal fun buildMergedScreenSnapshot(
        selectedApp: SelectedAppRef,
        eventClassName: String?,
        stepRoots: List<AccessibilityNodeSnapshot>,
    ): ScreenSnapshot {
        val accumulator = ScrollScanAccumulator(
            selectedApp = selectedApp,
            eventClassName = eventClassName,
            initialRoot = stepRoots.first(),
        )
        stepRoots.forEach(accumulator::addStep)
        return accumulator.build()
    }

    private fun snapshotNode(
        node: AccessibilityNodeInfo,
        childIndexPath: List<Int>,
    ): AccessibilityNodeSnapshot {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = buildList {
            repeat(node.childCount) { index ->
                val child = node.getChild(index) ?: return@repeat
                add(snapshotNode(child, childIndexPath + index))
            }
        }

        return AccessibilityNodeSnapshot(
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            supportsClickAction = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = checkedState(node),
            editable = node.isEditable,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            bounds = bounds.toShortString(),
            children = children,
            childIndexPath = childIndexPath,
        )
    }

    @Suppress("DEPRECATION")
    private fun checkedState(node: AccessibilityNodeInfo): Boolean = node.isChecked

    internal fun collectPressableElements(node: AccessibilityNodeSnapshot): List<PressableElement> {
        return collectPressableElements(node, ancestorChain = emptyList())
    }

    internal fun findPrimaryScrollableNodePath(
        root: AccessibilityNodeSnapshot,
        targetPackageName: String,
    ): List<Int>? {
        return collectScrollableCandidates(root, targetPackageName)
            .maxByOrNull { candidate -> candidate.score }
            ?.childIndexPath
    }

    private fun collectPressableElements(
        node: AccessibilityNodeSnapshot,
        ancestorChain: List<AccessibilityNodeSnapshot>,
    ): List<PressableElement> {
        val current = if (node.visibleToUser && (node.clickable || node.supportsClickAction)) {
            listOf(
                PressableElement(
                    label = resolveElementLabel(node),
                    resourceId = node.viewIdResourceName,
                    bounds = node.bounds,
                    className = node.className,
                    isListItem = isInsideListLikeContainer(ancestorChain),
                    childIndexPath = node.childIndexPath,
                    checkable = node.checkable,
                    checked = node.checked,
                    editable = node.editable,
                )
            )
        } else {
            emptyList()
        }

        val nextAncestors = ancestorChain + node
        return current + node.children.flatMap { child ->
            collectPressableElements(child, nextAncestors)
        }
    }

    private fun resolveElementLabel(node: AccessibilityNodeSnapshot): String {
        directLabel(node)?.let { return it }
        findNestedTitleLabel(node.children)?.let { return it }
        findNestedTextLabel(node.children)?.let { return it }

        return ScreenNaming.chooseElementLabel(
            text = null,
            contentDescription = null,
            viewIdResourceName = node.viewIdResourceName,
            bounds = node.bounds,
        )
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

    private fun isInsideListLikeContainer(ancestorChain: List<AccessibilityNodeSnapshot>): Boolean {
        return ancestorChain.any { ancestor ->
            val className = ancestor.className.orEmpty()
            className.contains("RecyclerView") ||
                className.contains("ListView") ||
                className.contains("GridView") ||
                (ancestor.scrollable && className.endsWith("LinearLayout"))
        }
    }

    private fun collectScrollableCandidates(
        node: AccessibilityNodeSnapshot,
        targetPackageName: String,
    ): List<ScrollableCandidate> {
        val current = buildList {
            val score = scrollableCandidateScore(node, targetPackageName)
            if (score != null) {
                add(
                    ScrollableCandidate(
                        childIndexPath = node.childIndexPath,
                        score = score,
                    )
                )
            }
        }

        return current + node.children.flatMap { child ->
            collectScrollableCandidates(child, targetPackageName)
        }
    }

    private fun scrollableCandidateScore(
        node: AccessibilityNodeSnapshot,
        targetPackageName: String,
    ): Int? {
        if (!node.visibleToUser || !node.scrollable) {
            return null
        }

        val className = node.className.orEmpty()
        val classScore = when {
            className.contains("RecyclerView") -> 600
            className.contains("ListView") -> 575
            className.contains("GridView") -> 550
            className.contains("NestedScrollView") -> 525
            className.contains("ScrollView") -> 500
            className.endsWith("LinearLayout") -> 350
            else -> 250
        }
        val packageScore = when (node.packageName) {
            targetPackageName -> 100
            null -> 25
            else -> -200
        }

        return classScore + packageScore + node.childIndexPath.size
    }

    private data class ScrollableCandidate(
        val childIndexPath: List<Int>,
        val score: Int,
    )
}
