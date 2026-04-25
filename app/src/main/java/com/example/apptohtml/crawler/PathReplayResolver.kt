package com.example.apptohtml.crawler

internal object PathReplayResolver {
    internal enum class ResolutionStatus {
        FULL,
        PARTIAL,
        NONE,
    }

    internal data class Resolution<T>(
        val nodes: List<T>,
        val status: ResolutionStatus,
        val intendedPath: List<Int>,
        val resolvedDepth: Int,
        val failingChildIndex: Int? = null,
        val availableChildCount: Int? = null,
    ) {
        fun usableNodes(): List<T> {
            return when (status) {
                ResolutionStatus.NONE -> emptyList()
                ResolutionStatus.FULL,
                ResolutionStatus.PARTIAL,
                -> nodes
            }
        }
    }

    internal fun <T> resolve(
        root: T,
        childIndexPath: List<Int>,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
    ): Resolution<T> {
        val nodes = mutableListOf(root)
        var current = root

        childIndexPath.forEach { childIndex ->
            val availableChildCount = childCount(current)
            if (childIndex < 0 || childIndex >= availableChildCount) {
                return divergence(
                    nodes = nodes,
                    intendedPath = childIndexPath,
                    failingChildIndex = childIndex,
                    availableChildCount = availableChildCount,
                )
            }

            val child = childAt(current, childIndex)
                ?: return divergence(
                    nodes = nodes,
                    intendedPath = childIndexPath,
                    failingChildIndex = childIndex,
                    availableChildCount = availableChildCount,
                )
            nodes += child
            current = child
        }

        return Resolution(
            nodes = nodes,
            status = ResolutionStatus.FULL,
            intendedPath = childIndexPath,
            resolvedDepth = childIndexPath.size,
        )
    }

    private fun <T> divergence(
        nodes: List<T>,
        intendedPath: List<Int>,
        failingChildIndex: Int,
        availableChildCount: Int,
    ): Resolution<T> {
        val resolvedDepth = (nodes.size - 1).coerceAtLeast(0)
        val status = if (resolvedDepth == 0) {
            ResolutionStatus.NONE
        } else {
            ResolutionStatus.PARTIAL
        }

        return Resolution(
            nodes = nodes,
            status = status,
            intendedPath = intendedPath,
            resolvedDepth = resolvedDepth,
            failingChildIndex = failingChildIndex,
            availableChildCount = availableChildCount,
        )
    }
}
