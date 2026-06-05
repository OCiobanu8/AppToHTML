package com.example.apptohtml.crawler

/**
 * Re-locates a previously seen element among the live clickable candidates during replay, when the
 * recorded `childIndexPath` no longer resolves.
 *
 * Identity is purely semantic: a candidate is eligible iff its [ElementFingerprint] equals the
 * target's. There is no geometry — no bounds, no tolerance — so a candidate matches regardless of
 * where it has moved on screen, and never matches merely because it shares a generic resource id
 * (the label is part of the fingerprint).
 */
internal object ClickFallbackMatcher {

    data class Candidate<T>(
        val handle: T,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val supportsClickAction: Boolean,
        val fingerprint: ElementFingerprint,
        val depth: Int,
    )

    data class Target(
        val fingerprint: ElementFingerprint,
    )

    enum class EligibilityReason {
        RESOURCE_ID_MATCH,
        LABEL_MATCH,
        CLASS_MATCH,
    }

    data class Match<T>(
        val candidate: Candidate<T>,
        val eligibilityReason: EligibilityReason,
        val rankScore: Int,
    )

    fun <T> selectMatches(
        candidates: List<Candidate<T>>,
        target: Target,
    ): List<Match<T>> {
        return candidates.mapNotNull { candidate ->
            evaluate(candidate, target)
        }.sortedByDescending { it.rankScore }
    }

    private fun <T> evaluate(
        candidate: Candidate<T>,
        target: Target,
    ): Match<T>? {
        if (!candidate.visible || !candidate.enabled) return null
        if (!candidate.clickable && !candidate.supportsClickAction) return null
        if (candidate.fingerprint != target.fingerprint) return null

        val hasResourceId = target.fingerprint.resourceId != null
        val hasLabel = target.fingerprint.label.isNotBlank()

        val eligibilityReason = when {
            hasResourceId -> EligibilityReason.RESOURCE_ID_MATCH
            hasLabel -> EligibilityReason.LABEL_MATCH
            else -> EligibilityReason.CLASS_MATCH
        }

        // All eligible candidates share the target fingerprint, so the resourceId/label terms are
        // constant across them; depth is the real tie-breaker (shallower wins). The absolute score
        // still communicates match strength in diagnostics.
        var rankScore = 0
        if (hasResourceId) rankScore += 1_000
        if (hasLabel) rankScore += 700
        rankScore += (100 - candidate.depth)

        return Match(
            candidate = candidate,
            eligibilityReason = eligibilityReason,
            rankScore = rankScore,
        )
    }
}
