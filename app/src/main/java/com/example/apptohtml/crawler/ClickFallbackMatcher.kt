package com.example.apptohtml.crawler

import kotlin.math.abs

internal object ClickFallbackMatcher {
    const val DEFAULT_BOUNDS_TOLERANCE_PX = 24

    private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        companion object {
            fun parse(value: String): Bounds? {
                val match = BOUNDS_REGEX.matchEntire(value) ?: return null
                return Bounds(
                    left = match.groupValues[1].toInt(),
                    top = match.groupValues[2].toInt(),
                    right = match.groupValues[3].toInt(),
                    bottom = match.groupValues[4].toInt(),
                )
            }
        }
    }

    data class Candidate<T>(
        val handle: T,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val supportsClickAction: Boolean,
        val resolvedLabel: String?,
        val resourceId: String?,
        val className: String?,
        val bounds: Bounds?,
        val checkable: Boolean,
        val checked: Boolean,
        val depth: Int,
    )

    data class Target(
        val label: String,
        val resourceId: String?,
        val className: String?,
        val bounds: String,
        val checkable: Boolean,
        val checked: Boolean,
    )

    enum class EligibilityReason {
        RESOURCE_ID_MATCH,
        LABEL_MATCH,
        CLASS_PLUS_BOUNDS_MATCH,
        BOUNDS_ICON_MATCH,
    }

    data class Match<T>(
        val candidate: Candidate<T>,
        val eligibilityReason: EligibilityReason,
        val rankScore: Int,
    )

    fun <T> selectMatches(
        candidates: List<Candidate<T>>,
        target: Target,
        boundsTolerancePx: Int = DEFAULT_BOUNDS_TOLERANCE_PX,
    ): List<Match<T>> {
        val targetBounds = Bounds.parse(target.bounds)
        return candidates.mapNotNull { candidate ->
            evaluate(candidate, target, targetBounds, boundsTolerancePx)
        }.sortedByDescending { it.rankScore }
    }

    private fun <T> evaluate(
        candidate: Candidate<T>,
        target: Target,
        targetBounds: Bounds?,
        boundsTolerancePx: Int,
    ): Match<T>? {
        if (!candidate.visible || !candidate.enabled) return null
        if (!candidate.clickable && !candidate.supportsClickAction) return null

        val resourceIdMatch = !target.resourceId.isNullOrBlank() &&
            !candidate.resourceId.isNullOrBlank() &&
            candidate.resourceId == target.resourceId

        val labelMatch = target.label.isNotBlank() &&
            !candidate.resolvedLabel.isNullOrBlank() &&
            candidate.resolvedLabel == target.label

        val classMatch = !target.className.isNullOrBlank() &&
            !candidate.className.isNullOrBlank() &&
            candidate.className == target.className

        val boundsCompatible = targetBounds != null &&
            candidate.bounds != null &&
            isBoundsCompatible(candidate.bounds, targetBounds, boundsTolerancePx)

        val classPlusBounds = classMatch && boundsCompatible
        val boundsIconMatch = target.label.isBlank() && boundsCompatible

        val eligibilityReason = when {
            resourceIdMatch -> EligibilityReason.RESOURCE_ID_MATCH
            labelMatch -> EligibilityReason.LABEL_MATCH
            classPlusBounds -> EligibilityReason.CLASS_PLUS_BOUNDS_MATCH
            boundsIconMatch -> EligibilityReason.BOUNDS_ICON_MATCH
            else -> return null
        }

        var rankScore = 0
        if (resourceIdMatch) rankScore += 1_000
        if (labelMatch) rankScore += 700
        if (classMatch) rankScore += 300
        if (boundsCompatible) rankScore += 200
        if (candidate.checkable == target.checkable) rankScore += 75
        if (candidate.checked == target.checked) rankScore += 25
        rankScore += (100 - candidate.depth)

        return Match(
            candidate = candidate,
            eligibilityReason = eligibilityReason,
            rankScore = rankScore,
        )
    }

    private fun isBoundsCompatible(
        candidate: Bounds,
        target: Bounds,
        tolerancePx: Int,
    ): Boolean {
        return abs(candidate.left - target.left) <= tolerancePx &&
            abs(candidate.top - target.top) <= tolerancePx &&
            abs(candidate.right - target.right) <= tolerancePx &&
            abs(candidate.bottom - target.bottom) <= tolerancePx
    }
}
