package com.example.apptohtml.crawler

/**
 * "Have I arrived at the screen this route was recorded for?"
 *
 * A seam so the arrival rule can be exercised without a device; the replay loop that calls it cannot
 * be reached from a unit test.
 */
internal object ReplayArrivalCheck {

    /** What the arrival check concluded. Divergences carry why, because the operator has to act on it. */
    sealed interface Verdict {
        /** The replay landed where it was supposed to. */
        object Arrived : Verdict

        /** The screen is no longer called what it was called. */
        data class NameDiverged(val expected: String?, val observed: String?) : Verdict

        /**
         * The screen is called the right thing but does not satisfy what the identity asserts.
         *
         * Carries the traits that failed, not just the fact that one did: an operator has to know
         * which assertion to look at. A message that only said the replay diverged would repeat
         * `a2h-c2b.6`, where the divergence read "Expected X but found X".
         */
        data class TraitsDoNotHold(val failing: List<Trait>) : Verdict {
            /** The failing assertions, for the divergence message. */
            fun describe(): String = describeFailing(failing)
        }
    }

    /**
     * Compares [expected] against the screen the replay landed on.
     *
     * The name is asked first and on its own terms: a screen's content legitimately churns between
     * visits — a row appears, a badge updates — but what it is called does not. That is why the
     * **element set is deliberately not compared here**; requiring byte equality of it is the
     * brittleness `a2h-c2b` exists to remove.
     *
     * A settled identity is then asked to hold. Traits survive churn by construction — "a list with
     * three or more rows carrying these ids" stays true as the rows change — so they add the one
     * signal that is both stronger than the name and stable across visits.
     *
     * **An unsettled identity is not consulted at all.** A screen settles only when its proposal held
     * on its own capture ([IdentitySettlement]), and a screen left unsettled — a case was opened, or a
     * crawl was saved before machine proposals — reaches exactly the verdict the name alone gives.
     * Traits that do not hold here are a divergence for an operator, which the caller raises as a case.
     */
    fun check(
        expected: ScreenIdentity,
        observed: ScreenIdentity,
        observedRoot: AccessibilityNodeSnapshot,
    ): Verdict {
        val nameComparison = SameNamePolicy.compare(expected, observed)
        if (!nameComparison.matched) {
            return Verdict.NameDiverged(
                expected = DedupPolicy.nameKey(expected),
                observed = DedupPolicy.nameKey(observed),
            )
        }

        // An identity with no traits cannot be settled, so the answer is Arrived whatever the
        // screen looks like. Returning before building the context avoids two full tree walks on
        // the path every existing crawl takes. Purely an optimisation: TraitEvaluator still owns
        // what "unsettled" means, and a negations-only identity falls through to it below.
        if (expected.traits.isEmpty()) return Verdict.Arrived

        val context = TraitEvaluationContext.of(observedRoot, observed.name)
        return when (TraitEvaluator.verdict(expected, context)) {
            // The opt-in guard. TraitEvaluator owns what "asserts nothing present" means; this only
            // acts on its answer.
            TraitVerdict.UNSETTLED -> Verdict.Arrived
            TraitVerdict.HOLDS -> Verdict.Arrived
            // Which traits failed is asked of the same predicates the verdict came from, so the
            // message cannot name something the decision did not consider.
            TraitVerdict.DOES_NOT_HOLD -> Verdict.TraitsDoNotHold(
                failing = expected.traits.filterNot { trait -> trait.holds(context) },
            )
        }
    }
}
