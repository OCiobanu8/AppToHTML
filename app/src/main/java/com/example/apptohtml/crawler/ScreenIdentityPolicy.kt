package com.example.apptohtml.crawler

/**
 * Why a [ScreenIdentityPolicy] reached its verdict.
 *
 * The eight reasons carried over from the deleted entry-screen matcher keep their exact former
 * names so the migration is auditable line by line. `DECODE_FAILED` is gone: it existed only
 * because the expected value was a string that had to be parsed before it could be compared, and
 * there is no longer a parse step at comparison time. Two reasons are new: `ELEMENT_SET_DIFFERS`
 * names the verdict the zero-tolerance sites used to express as a bare `!=`, and `NAME_DIFFERS` is
 * what [SameNamePolicy] reports when the replayed screen is no longer called what it was.
 */
enum class ScreenIdentityMatchReason {
    NO_EXPECTED_FINGERPRINT,
    EXACT_FINGERPRINT_MATCH,
    ROOT_CLASS_MISMATCH,
    EMPTY_IDENTITY_SET,
    ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
    IDENTITY_OVERLAP_THRESHOLD_MET,
    IDENTITY_OVERLAP_TOO_LOW,
    UNRELATED_ENTRY_IDENTITIES,
    ELEMENT_SET_DIFFERS,
    NAME_DIFFERS,
}

/**
 * The outcome of comparing an expected identity against an observed one.
 *
 * [missing] and [extra] are the point of the whole structure: a non-matching comparison says what
 * differed, rather than leaving a caller to decode two strings by hand after the fact. The
 * count/coverage/Dice fields are carried forward because the crawl log already reports all six.
 */
data class ScreenIdentityComparison(
    val matched: Boolean,
    val reason: ScreenIdentityMatchReason,
    val missing: Set<ScreenElementIdentity> = emptySet(),
    val extra: Set<ScreenElementIdentity> = emptySet(),
    val expectedCount: Int = 0,
    val observedCount: Int = 0,
    val overlapCount: Int = 0,
    val expectedCoverage: Double = 0.0,
    val observedCoverage: Double = 0.0,
    val diceSimilarity: Double = 0.0,
)

/**
 * A named rule for deciding whether an observed screen is the expected one.
 *
 * Every screen-level content comparison in the crawler resolves through one of these. The rules
 * are not interchangeable and are not meant to converge: entry restore has to survive churn on a
 * screen it is standing on, while a route step must not accept a lookalike.
 */
sealed interface ScreenIdentityPolicy {
    fun compare(expected: ScreenIdentity?, observed: ScreenIdentity): ScreenIdentityComparison
}

/**
 * Entry-screen restore. The most forgiving rule in the system, and the only one that was already
 * asking the useful question — *are the things I recorded still present?* — because it was the
 * only site that decoded its stored string back into a set first.
 *
 * Back affordances are excluded from both sides: the entry screen grows one once the crawler has
 * navigated away and come back, and that must not count as a different screen.
 */
data class EntryRestorePolicy(
    val targetPackageName: String,
) : ScreenIdentityPolicy {
    override fun compare(
        expected: ScreenIdentity?,
        observed: ScreenIdentity,
    ): ScreenIdentityComparison {
        if (expected == null) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.NO_EXPECTED_FINGERPRINT,
            )
        }
        if (observed.packageName != targetPackageName) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES,
            )
        }

        val expectedIdentities = expected.elementsFor(countBackAffordances = false)
        val observedIdentities = observed.elementsFor(countBackAffordances = false)
        val sameRootClass = expected.rootClassName == observed.rootClassName

        if (sameRootClass && expectedIdentities == observedIdentities) {
            return ScreenIdentityComparison(
                matched = true,
                reason = ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH,
                expectedCoverage = 1.0,
                observedCoverage = 1.0,
                diceSimilarity = 1.0,
            )
        }
        if (!sameRootClass) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH,
            )
        }
        if (expectedIdentities.isEmpty() || observedIdentities.isEmpty()) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.EMPTY_IDENTITY_SET,
                missing = expectedIdentities - observedIdentities,
                extra = observedIdentities - expectedIdentities,
                expectedCount = expectedIdentities.size,
                observedCount = observedIdentities.size,
            )
        }

        val overlapCount = (expectedIdentities intersect observedIdentities).size
        val expectedCoverage = overlapCount.toDouble() / expectedIdentities.size
        val observedCoverage = overlapCount.toDouble() / observedIdentities.size
        val diceSimilarity =
            (2.0 * overlapCount) / (expectedIdentities.size + observedIdentities.size)

        val reason = when {
            overlapCount == 0 -> ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES
            observedIdentities.containsAll(expectedIdentities) &&
                observedIdentities.size >= expectedIdentities.size ->
                ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES
            diceSimilarity >= DICE_SIMILARITY_THRESHOLD ->
                ScreenIdentityMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET
            else -> ScreenIdentityMatchReason.IDENTITY_OVERLAP_TOO_LOW
        }

        return ScreenIdentityComparison(
            matched = reason == ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES ||
                reason == ScreenIdentityMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
            reason = reason,
            missing = expectedIdentities - observedIdentities,
            extra = observedIdentities - expectedIdentities,
            expectedCount = expectedIdentities.size,
            observedCount = observedIdentities.size,
            overlapCount = overlapCount,
            expectedCoverage = expectedCoverage,
            observedCoverage = observedCoverage,
            diceSimilarity = diceSimilarity,
        )
    }

    private companion object {
        const val DICE_SIMILARITY_THRESHOLD = 2.0 / 3.0
    }
}

/**
 * Whether a comparison counts back affordances — the one home for that rule.
 *
 * The rule is always read off **the screen the comparison is about**, never off whatever screen the
 * caller happens to be standing on. Spelling it out here rather than inline at each call site is
 * what makes the one deliberate exception visible as an exception instead of looking like a bug.
 */
object BackAffordanceCounting {

    /**
     * The ordinary rule: a screen counts back affordances unless it is the crawl root, whose stored
     * identity was captured before the crawler had ever navigated away and so carries none.
     */
    fun forScreen(isRootScreen: Boolean): Boolean = !isRootScreen

    /**
     * The exception: a route step's **destination child**, which is never the crawl root, so back
     * affordances always count however deep the parent sits.
     *
     * Deliberately a constant rather than [forScreen] of anything. Deriving it from the parent's
     * root-ness loosened this comparison for depth-1 children — a tolerance change, and the reason
     * this value is named and pinned rather than written inline.
     */
    const val FOR_ROUTE_STEP_DESTINATION: Boolean = true
}

/**
 * S4 — "is the screen I replayed to the one this step recorded?"
 *
 * A seam with one job: hold the destination comparison to [BackAffordanceCounting]'s exception. The
 * call site inside the replay loop is unreachable from a unit test, so without this the exception
 * could only be asserted by reading a comment.
 */
internal object RouteStepDestinationCheck {
    fun compare(
        expected: ScreenIdentity,
        observed: ScreenIdentity,
    ): ScreenIdentityComparison =
        SameScreenPolicy(BackAffordanceCounting.FOR_ROUTE_STEP_DESTINATION)
            .compare(expected, observed)
}

/**
 * "Is this the same screen?" with no tolerance at all — the whole element set must match.
 *
 * [countBackAffordances] replaces the ad-hoc boolean that used to pick between two string builders
 * at six call sites. Each site sets it for **the screen the comparison is about** — see
 * [BackAffordanceCounting], which owns that rule.
 */
data class SameScreenPolicy(
    val countBackAffordances: Boolean,
) : ScreenIdentityPolicy {
    override fun compare(
        expected: ScreenIdentity?,
        observed: ScreenIdentity,
    ): ScreenIdentityComparison {
        if (expected == null) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.NO_EXPECTED_FINGERPRINT,
            )
        }

        // The back-affordance flag decides MEMBERSHIP (which elements this comparison counts) but
        // never takes part in EQUALITY: it is derived from position, and identity is not. Comparing
        // `ScreenElementIdentity` sets directly would make a row whose resource id merely contains
        // "back" a different element on each side of the 300px band — round-4 finding N1.
        val expectedIdentities = expected.elementsFor(countBackAffordances)
        val observedIdentities = observed.elementsFor(countBackAffordances)
        val expectedFingerprints = expectedIdentities.mapTo(mutableSetOf()) { it.fingerprint }
        val observedFingerprints = observedIdentities.mapTo(mutableSetOf()) { it.fingerprint }
        val sameRootClass = expected.rootClassName == observed.rootClassName

        if (sameRootClass && expectedFingerprints == observedFingerprints) {
            return ScreenIdentityComparison(
                matched = true,
                reason = ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH,
                expectedCount = expectedIdentities.size,
                observedCount = observedIdentities.size,
                overlapCount = expectedIdentities.size,
                expectedCoverage = 1.0,
                observedCoverage = 1.0,
                diceSimilarity = 1.0,
            )
        }

        val overlapCount = (expectedFingerprints intersect observedFingerprints).size
        return ScreenIdentityComparison(
            matched = false,
            reason = if (sameRootClass) {
                ScreenIdentityMatchReason.ELEMENT_SET_DIFFERS
            } else {
                ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH
            },
            missing = expectedIdentities.filterNot { it.fingerprint in observedFingerprints }.toSet(),
            extra = observedIdentities.filterNot { it.fingerprint in expectedFingerprints }.toSet(),
            expectedCount = expectedIdentities.size,
            observedCount = observedIdentities.size,
            overlapCount = overlapCount,
        )
    }
}

/**
 * "Did that click actually go somewhere?"
 *
 * Composes [SameScreenPolicy] against the screen we clicked from and that screen's top state. A
 * click that lands on neither has navigated; a click that lands on either has not.
 */
data class NavigatedAwayPolicy(
    val countBackAffordances: Boolean,
) {
    private val sameScreen = SameScreenPolicy(countBackAffordances)

    fun navigatedAway(
        beforeClick: ScreenIdentity,
        top: ScreenIdentity,
        after: ScreenIdentity,
    ): Boolean {
        return !sameScreen.compare(beforeClick, after).matched &&
            !sameScreen.compare(top, after).matched
    }
}

/**
 * "Is the screen I replayed to still called what it was called?"
 *
 * Reads the name half only, and **ungated** — unlike [DedupPolicy.keyFor], which additionally asks
 * whether the screen may be *catalogued*. A weak-titled screen is still perfectly replayable, and
 * conflating those two questions is what made every weak-titled screen fail replay in round 1.
 */
object SameNamePolicy : ScreenIdentityPolicy {
    override fun compare(
        expected: ScreenIdentity?,
        observed: ScreenIdentity,
    ): ScreenIdentityComparison {
        if (expected == null) {
            return ScreenIdentityComparison(
                matched = false,
                reason = ScreenIdentityMatchReason.NO_EXPECTED_FINGERPRINT,
            )
        }
        val expectedKey = DedupPolicy.nameKey(expected)
        val matched = expectedKey != null && expectedKey == DedupPolicy.nameKey(observed)
        return ScreenIdentityComparison(
            matched = matched,
            reason = if (matched) {
                ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH
            } else {
                ScreenIdentityMatchReason.NAME_DIFFERS
            },
        )
    }
}

/**
 * "Have I catalogued this screen before?"
 *
 * Reads the name half only — as does [SameNamePolicy], which asks the ungated question. Two
 * screens are the same catalogue entry when their package, screen name and title disambiguators
 * agree — and a screen may only be catalogued at all when its name identity is STRONG.
 *
 * The lookup stays an exact-match hash key rather than a ranked search: this cycle preserves
 * dedup's rule exactly, and turning it into a search is a separate decision.
 */
object DedupPolicy {
    /**
     * The screen's name key, **ungated**: what this screen would be catalogued as.
     *
     * Deliberately distinct from [keyFor]. Whether a screen is *eligible* to be indexed is a
     * separate question from what its name says, and conflating the two would make a WEAK-titled
     * screen compare unequal to itself — the regression this split exists to prevent.
     */
    fun nameKey(identity: ScreenIdentity): String? {
        val name = identity.name ?: return null
        return ScreenIdentityCodec.encode(
            packageName = name.packageName,
            title = name.screenName,
            titleDisambiguators = name.titleDisambiguators,
        )
    }

    /** Null when this screen may not be catalogued at all, i.e. its name identity is too weak. */
    fun keyFor(identity: ScreenIdentity): String? {
        val name = identity.name ?: return null
        if (!name.canLinkToExisting) return null
        return nameKey(identity)
    }
}
