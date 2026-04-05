package com.example.apptohtml.crawler

data class TraversalPlan(
    val eligibleElements: List<PressableElement>,
    val skippedElements: List<SkippedTraversalElement>,
)

data class SkippedTraversalElement(
    val element: PressableElement,
    val reason: String,
)

object TraversalPlanner {
    fun planRootTraversal(
        snapshot: ScreenSnapshot,
        blacklist: CrawlBlacklist,
    ): TraversalPlan {
        val eligible = mutableListOf<PressableElement>()
        val skipped = mutableListOf<SkippedTraversalElement>()

        orderedElements(snapshot.elements).forEach { element ->
            val skipReason = blacklist.skipReason(element)
            if (skipReason != null) {
                skipped += SkippedTraversalElement(
                    element = element,
                    reason = skipReason,
                )
            } else {
                eligible += element
            }
        }

        return TraversalPlan(
            eligibleElements = eligible,
            skippedElements = skipped,
        )
    }

    internal fun orderedElements(elements: List<PressableElement>): List<PressableElement> {
        return elements.sortedWith(
            compareBy<PressableElement> { it.firstSeenStep }
                .thenBy { boundsTop(it.bounds) }
                .thenBy { boundsLeft(it.bounds) }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun boundsTop(bounds: String): Int = parseBounds(bounds)?.top ?: Int.MAX_VALUE

    private fun boundsLeft(bounds: String): Int = parseBounds(bounds)?.left ?: Int.MAX_VALUE

    private fun parseBounds(bounds: String): ParsedBounds? {
        val match = BOUNDS_REGEX.matchEntire(bounds) ?: return null
        return ParsedBounds(
            left = match.groupValues[1].toInt(),
            top = match.groupValues[2].toInt(),
        )
    }

    private data class ParsedBounds(
        val left: Int,
        val top: Int,
    )

    private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
}
