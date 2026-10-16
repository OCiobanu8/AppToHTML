package com.example.apptohtml.crawler

/**
 * A screen the crawl already knows, as the settle and recognition decisions see it: its identity and
 * the first-viewport tree it was captured from. [tree] is null when that capture could not be read
 * back.
 */
data class KnownScreen(
    val screenId: String,
    val identity: ScreenIdentity,
    val tree: AccessibilityNodeSnapshot?,
)

/**
 * "Does this proposal hold?" — the settle decision for a brand-new screen.
 *
 * A proposal holds when it matches its own screen **and only its own screen**:
 * 1. it asserts something present;
 * 2. it holds on the screen's own tree;
 * 3. no settled known identity holds on this tree — the caller's recognition step, which answered
 *    "new screen" before asking; and
 * 4. it does not hold on any other known screen's tree.
 *
 * Every judgement is [TraitEvaluator]'s. Check 4 is [TraitEvaluator.matchKnownScreens] with the roles
 * reversed — each known screen's tree observed, the proposal as the only candidate — so package and
 * name take part exactly as they do when recognising a screen, and two screens are only ever told
 * apart by the same rule in both directions.
 */
object IdentitySettlement {

    /** The decision, with the reason when the proposal does not hold. */
    sealed interface Decision {
        /** The proposal holds; the screen is settled with it. */
        object Settled : Decision

        /** The proposal does not hold, so it opens a case. */
        sealed interface NotSettled : Decision {
            /** One line naming why, for the case and the log. */
            fun describe(): String
        }

        /** The proposal asserts nothing present — an id-less screen with no repeating list. */
        object AssertsNothing : NotSettled {
            override fun describe(): String =
                "the proposal asserts nothing: no list repeats a shape and no control has a resource id"
        }

        /** The proposal does not hold on the very screen it was proposed for. */
        data class FailsOnOwnScreen(val failing: List<Trait>) : NotSettled {
            override fun describe(): String =
                "the proposal does not hold on its own screen: ${describeFailing(failing)}"
        }

        /** The proposal also holds on these known screens, so it would not identify this one. */
        data class AlsoHolds(val screenIds: List<String>) : NotSettled {
            override fun describe(): String =
                "the proposal also holds on ${screenIds.joinToString(", ")}"
        }

        /**
         * These known screens' captures could not be read back, so uniqueness against them cannot be
         * checked. Not settled: an unchecked screen is never counted as a non-collision.
         */
        data class CannotCheckAgainst(val screenIds: List<String>) : NotSettled {
            override fun describe(): String =
                "uniqueness could not be checked against ${screenIds.joinToString(", ")}: capture unreadable"
        }
    }

    /**
     * Decides whether [proposal] — the new screen's identity, name included, carrying the proposed
     * traits — holds, given [ownContext] built from the screen's own first-viewport tree and name, and
     * [others], every other screen the crawl knows.
     *
     * Deterministic: every id list is sorted, so the order [others] arrives in does not matter.
     */
    fun decide(
        proposal: ScreenIdentity,
        ownContext: TraitEvaluationContext,
        others: Collection<KnownScreen>,
    ): Decision {
        when (TraitEvaluator.verdict(proposal, ownContext)) {
            TraitVerdict.UNSETTLED -> return Decision.AssertsNothing
            TraitVerdict.DOES_NOT_HOLD -> return Decision.FailsOnOwnScreen(
                failing = proposal.traits.filterNot { trait -> trait.holds(ownContext) },
            )
            TraitVerdict.HOLDS -> Unit
        }

        val unreadable = others.filter { it.tree == null }.map(KnownScreen::screenId).sorted()
        val collisions = others
            .filter { it.tree != null }
            .filter { other ->
                val otherContext = TraitEvaluationContext.of(requireNotNull(other.tree), other.identity.name)
                TraitEvaluator.matchKnownScreens(otherContext, mapOf(CANDIDATE_ID to proposal)) is
                    ScreenTraitMatch.One
            }
            .map(KnownScreen::screenId)
            .sorted()

        // A collision outranks an unreadable capture: both refuse the proposal, but only the
        // collision names a screen the operator must look at, so it is the one reported.
        return when {
            collisions.isNotEmpty() -> Decision.AlsoHolds(collisions)
            unreadable.isNotEmpty() -> Decision.CannotCheckAgainst(unreadable)
            else -> Decision.Settled
        }
    }

    /**
     * The kind of case a refused proposal opens for a screen named [name].
     *
     * As the lifecycle in `a2h-c2b.3`'s SCOPE draws it, every refusal — asserts nothing, fails on its
     * own screen, also holds on another, cannot be checked — is a proposal that does not hold. The one
     * exception is the Gate 2 amendment: under a WEAK name, a proposal that also holds on an earlier
     * screen is a **uniqueness** case, because nothing can tell that screen from this one.
     */
    fun caseKindFor(refusal: Decision.NotSettled, name: ScreenNameIdentity?): IdentityCaseKind {
        val weakName = name?.canLinkToExisting != true
        return if (refusal is Decision.AlsoHolds && weakName) {
            IdentityCaseKind.UNIQUENESS_COLLISION
        } else {
            IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD
        }
    }

    /** The proposal's id in the reversed check; never a real screen id, never persisted. */
    private const val CANDIDATE_ID = "<proposal>"
}
