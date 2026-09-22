package com.example.apptohtml.crawler

/**
 * Does this screen's identity hold — does it match this screen, and does it match **only** this
 * screen?
 *
 * Every answer below is produced by the crawler's own code. Nothing here re-implements identity,
 * label normalization, list-item detection, back affordance, trait evaluation, uniqueness or click
 * eligibility: this composes calls and formats what comes back. A tool that decided any of those
 * for itself would certify identities the crawler disagrees with, which is worse than no tool.
 */
internal object ScreenIdentityValidator {

    /**
     * The headline. It is the **trait** answer: an identity is what it asserts about a screen.
     *
     * The element-set answer travels beside it in [Report.elementSet] and is never folded in. They
     * answer different questions — "are the things I asserted present" versus "is the element set
     * byte-identical" — and blending them would hide a failing one behind a passing one.
     */
    enum class Outcome {
        /** The traits hold here, and no other known screen's do. */
        HOLDS,

        /** They hold — but so do another known screen's. The identity is a label, not an identity. */
        NOT_UNIQUE,

        /** The traits do not hold on this screen. */
        DOES_NOT_HOLD,

        /** The identity asserts nothing present. Every fresh capture is this until traits are added. */
        UNSETTLED,
    }

    /** How re-clickable one of the target's elements is on the observed screen. */
    enum class Resolution { RESOLVED, AMBIGUOUS, UNRESOLVED }

    data class ElementResolution(
        val element: ScreenElementIdentity,
        val resolution: Resolution,
        /** Every candidate, best-ranked first. Ambiguity is reported in full, never picked between. */
        val candidates: List<String>,
    )

    /**
     * Why an identity may be hard to settle, reported for both screens and never folded into the
     * headline.
     */
    data class Diagnostics(
        /** Elements with no resource id **and** no label: nothing stable to assert about them. */
        val weakElements: List<String>,
        /** Distinct pressables that share one fingerprint, so an assertion cannot tell them apart. */
        val collisions: List<String>,
    )

    /**
     * Everything one validation found, with the answers kept apart rather than summarised.
     *
     * A caller formats these; nothing here ranks or combines them. [outcome] is the headline,
     * [elementSet] is the separate element-set answer, and the diagnostics are reported but never
     * allowed to move either.
     */
    data class Report(
        val outcome: Outcome,
        val traitVerdict: TraitVerdict,
        val knownScreenMatch: ScreenTraitMatch,
        val elementSet: ScreenIdentityComparison,
        val elements: List<ElementResolution>,
        val targetDiagnostics: Diagnostics,
        val observedDiagnostics: Diagnostics,
        /** The assertions that failed, so the operator knows which line to edit. Empty unless DOES_NOT_HOLD. */
        val failingTraits: List<Trait>,
        /** Elements to paste, in the identity block's own syntax, for everything that did not resolve. */
        val suggestedElements: List<ScreenElementIdentity>,
    )

    /**
     * Validates [target]'s identity against [observed], with [knownScreens] deciding uniqueness.
     *
     * [knownScreens] should include the target itself — uniqueness means *exactly one* known screen
     * holds here, and excluding the target would make its own match look like zero.
     */
    fun validate(
        target: LoadedCapture,
        observed: AccessibilityNodeSnapshot,
        observedName: ScreenNameIdentity?,
        knownScreens: List<LoadedCapture>,
        isRootScreen: Boolean,
    ): Report {
        // The observed screen is named as ITSELF, never as the target.
        //
        // `TraitEvaluator.matchKnownScreens` filters candidates by the context's name key, so
        // naming the observed screen after the target restricted uniqueness to screens that
        // already shared the target's name — every differently-named screen was excluded before
        // its traits were even considered, and a real collision could not be reported. Uniqueness
        // has to be asked of the screen in front of us, which is what its own capture records.
        val observedIdentity = ScreenIdentity.fromRoot(observed)
            .let { identity -> observedName?.let(identity::withName) ?: identity }
        val context = TraitEvaluationContext.of(observed, observedName)

        val traitVerdict = TraitEvaluator.verdict(target.identity, context)
        val knownScreenMatch = TraitEvaluator.matchKnownScreens(
            context = context,
            knownScreens = knownScreens.associate { it.screenId to it.identity },
        )
        val elementSet = SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen))
            .compare(target.identity, observedIdentity)

        val elements = target.identity.elements
            .sortedBy { it.encoded }
            .map { element -> resolve(element, observed) }

        // Anything the operator has to act on: an element the screen no longer has, or one that
        // cannot be clicked unambiguously. The observed screen's own elements are what they would
        // paste in its place.
        // Offered only when pasting it would change something. An AMBIGUOUS element makes the
        // unresolved list non-empty while the element set already matches exactly, and printing the
        // file's own contents back as an instruction leaves the operator following advice that
        // changes nothing and a tool that says the same thing forever.
        val unresolved = elements.filter { it.resolution != Resolution.RESOLVED }.map { it.element }
        val wouldChangeSomething = elementSet.missing.isNotEmpty() ||
            observedIdentity.elements != target.identity.elements
        val suggested = if (!wouldChangeSomething) {
            emptyList()
        } else {
            observedIdentity.elements.sortedBy { it.encoded }
        }

        return Report(
            outcome = outcomeOf(traitVerdict, knownScreenMatch, target.screenId),
            // Asked of the same predicates the verdict came from, so the report cannot name an
            // assertion the decision did not consider. The crawler's divergence message already
            // named the failing trait; the tool an operator settles with must do at least as well.
            failingTraits = if (traitVerdict == TraitVerdict.DOES_NOT_HOLD) {
                target.identity.traits.filterNot { trait -> trait.holds(context) }
            } else {
                emptyList()
            },
            traitVerdict = traitVerdict,
            knownScreenMatch = knownScreenMatch,
            elementSet = elementSet,
            elements = elements,
            targetDiagnostics = diagnose(target.firstViewport),
            observedDiagnostics = diagnose(observed),
            suggestedElements = suggested,
        )
    }

    /**
     * The headline, from the trait answer alone.
     *
     * `matchKnownScreens` decides uniqueness, including the case where the target's own traits hold
     * but some *other* screen's do too. A target that holds while another screen also holds is
     * [Outcome.NOT_UNIQUE] — the identity does not distinguish the screen, which is the whole point
     * of requiring uniqueness.
     */
    private fun outcomeOf(
        traitVerdict: TraitVerdict,
        match: ScreenTraitMatch,
        targetScreenId: String,
    ): Outcome = when (traitVerdict) {
        TraitVerdict.UNSETTLED -> Outcome.UNSETTLED
        TraitVerdict.DOES_NOT_HOLD -> Outcome.DOES_NOT_HOLD
        TraitVerdict.HOLDS -> when (match) {
            is ScreenTraitMatch.One ->
                if (match.screenId == targetScreenId) Outcome.HOLDS else Outcome.NOT_UNIQUE
            is ScreenTraitMatch.Ambiguous -> Outcome.NOT_UNIQUE
            // The traits hold, yet the target is not among the matches — so something other than
            // the traits disqualified it: its package differs from the observed screen's, the
            // observed tree carries no package at all, or its name does. That is the identity
            // failing to describe this screen, NOT two screens colliding. Calling it NOT_UNIQUE
            // reported a collision that did not exist, and contradicted the report's own
            // "no known screen holds here" line.
            is ScreenTraitMatch.None -> Outcome.DOES_NOT_HOLD
        }
    }

    /** Per-element re-clickability, through the crawler's own fallback matcher. */
    private fun resolve(
        element: ScreenElementIdentity,
        observed: AccessibilityNodeSnapshot,
    ): ElementResolution {
        val matches = ClickFallbackMatcher.selectMatches(
            candidates = snapshotActions().collectClickFallbackCandidates(observed)
                .map { it.candidate },
            target = ClickFallbackMatcher.Target(element.fingerprint),
        )
        val resolution = when {
            matches.isEmpty() -> Resolution.UNRESOLVED
            matches.size == 1 -> Resolution.RESOLVED
            else -> Resolution.AMBIGUOUS
        }
        return ElementResolution(
            element = element,
            resolution = resolution,
            candidates = matches.map { match ->
                "${match.candidate.fingerprint.label} (${match.eligibilityReason}, rank ${match.rankScore})"
            },
        )
    }

    /**
     * The crawler's own candidate collector, driven over snapshot nodes.
     *
     * `LiveNodeActions` is generic over the node type precisely so it can run without a device. The
     * action vocabulary is arbitrary here because both sides of it are supplied below — what matters
     * is that a node advertises a click action exactly when the capture recorded one.
     */
    private fun snapshotActions() = LiveNodeActions<AccessibilityNodeSnapshot>(
        childCount = { node -> node.children.size },
        childAt = { node, index -> node.children.getOrNull(index) },
        attributes = { node ->
            LiveNodeAttributes(
                className = node.className,
                viewIdResourceName = node.viewIdResourceName,
                text = node.text,
                contentDescription = node.contentDescription,
                boundsShortString = node.bounds,
                visibleToUser = node.visibleToUser,
                enabled = node.enabled,
                scrollable = node.scrollable,
                clickable = node.clickable,
                checkable = node.checkable,
                editable = node.editable,
            )
        },
        supportedActionIds = { node -> if (node.supportsClickAction) setOf(CLICK_ACTION_ID) else emptySet() },
        performAction = { _, _ -> error("the validator never acts on a screen") },
        actionIds = LiveActionIds(
            scrollForward = 1,
            scrollBackward = 2,
            click = CLICK_ACTION_ID,
            scrollDown = 4,
            scrollUp = 5,
            pageDown = 6,
            pageUp = 7,
        ),
        logger = { null },
        diagnostics = {},
    )

    /**
     * Why an identity may be hard to settle. Reported, never acted on: these counts must not move
     * the headline, or a screen would pass for being hard to describe.
     */
    private fun diagnose(root: AccessibilityNodeSnapshot?): Diagnostics {
        if (root == null) return Diagnostics(emptyList(), emptyList())
        val pressables = AccessibilityTreeSnapshotter.collectPressableElements(root)
        val weak = pressables
            .filter { it.resourceId.isNullOrBlank() && it.label.isBlank() }
            .map { "${it.className.orEmpty()} at ${it.bounds}" }
        val collisions = pressables
            .groupBy(ElementFingerprint::of)
            .filterValues { it.size > 1 }
            .map { (fingerprint, sharing) -> "${fingerprint.label} x${sharing.size}" }
        return Diagnostics(weakElements = weak, collisions = collisions.sorted())
    }

    /** Arbitrary but internally consistent; see [snapshotActions]. */
    private const val CLICK_ACTION_ID = 3
}
