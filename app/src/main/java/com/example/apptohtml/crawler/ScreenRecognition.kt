package com.example.apptohtml.crawler

/**
 * "Have I seen this screen before?" — dedup, resolved through the identities themselves.
 *
 * The known screens' identities are run against the observed screen ([TraitEvaluator.matchKnownScreens]):
 * exactly one holding is a known screen, none is a new one, and more than one is a uniqueness
 * violation — a case, never a merge.
 *
 * Two rules sit on top, each closing a hole the traits alone leave open:
 *
 * - **A WEAK-named screen is never linked.** Traits describe a screen's structure, not which instance
 *   it is, and a weak name cannot tell two lookalikes apart either. So what would have been a link is a
 *   collision for an operator to look at (`a2h-c2b.3`, Gate 2 amendment).
 * - **Unsettled screens keep the old name rule, and only they do.** An unsettled screen asserts
 *   nothing, so it is never a trait candidate; without this, every visit to one would re-propose,
 *   re-fail and re-capture it. A settled screen is never linked by name alone.
 */
object ScreenRecognition {

    /** What recognition concluded. */
    sealed interface Result {
        /** A known screen; link the edge to it. */
        data class Link(val screenId: String, val via: Via) : Result

        /** Nothing known matches; propose and settle a new screen. */
        object New : Result

        /**
         * A uniqueness violation: [screenIds] (sorted, never narrowed to one) all claim this screen,
         * or one does and the observed name is too weak to trust the link.
         */
        data class Collision(val screenIds: List<String>) : Result {
            init {
                require(screenIds.isNotEmpty()) { "A collision names at least one screen." }
            }
        }
    }

    /** How a link was made — logged, so the name-rule fallback is always visible. */
    enum class Via {
        /** Exactly one settled identity holds here. */
        TRAITS,

        /** No settled identity holds, and an unsettled screen has this STRONG name. */
        UNSETTLED_NAME,
    }

    /**
     * Recognises [observed] — the screen's identity with its name — whose [observedContext] was built
     * from its first-viewport tree and that same name, against [known] screens' identities.
     */
    fun recognize(
        observed: ScreenIdentity,
        observedContext: TraitEvaluationContext,
        known: Map<String, ScreenIdentity>,
    ): Result {
        val strongName = observed.name?.canLinkToExisting == true
        return when (val match = TraitEvaluator.matchKnownScreens(observedContext, known)) {
            is ScreenTraitMatch.Ambiguous -> Result.Collision(match.screenIds)
            is ScreenTraitMatch.One ->
                if (strongName) {
                    Result.Link(match.screenId, Via.TRAITS)
                } else {
                    Result.Collision(listOf(match.screenId))
                }
            is ScreenTraitMatch.None -> {
                val observedKey = DedupPolicy.keyFor(observed)
                val byName = observedKey?.let { key ->
                    match.unsettledScreenIds.firstOrNull { id ->
                        DedupPolicy.keyFor(known.getValue(id)) == key
                    }
                }
                if (byName != null) Result.Link(byName, Via.UNSETTLED_NAME) else Result.New
            }
        }
    }
}
