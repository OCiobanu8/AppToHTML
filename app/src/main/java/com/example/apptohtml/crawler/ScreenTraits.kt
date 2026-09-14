package com.example.apptohtml.crawler

import java.util.Collections

/**
 * One assertion a [ScreenIdentity] makes about the screen in front of the crawler.
 *
 * An identity holds its traits as a single list, evaluated as a conjunction by [TraitEvaluator].
 * Traits compare *types*, not instances: "a list whose rows carry a title, a price and a quantity"
 * stays true while every row's values change, which an exact element set cannot express — on a list
 * screen the rows are the data.
 *
 * A trait is checked against a [TraitEvaluationContext] built once from the observed tree — never
 * against another identity or a regenerated string — and never sees a node itself.
 */
sealed interface Trait {
    /** Whether this assertion is true of the screen [context] was built from. */
    fun holds(context: TraitEvaluationContext): Boolean
}

/**
 * A repeating group: some container whose resource id is [containerResourceId] has at least
 * [minRows] rows, each carrying every id in [rowChildResourceIds] somewhere beneath it.
 *
 * The row child ids are the schema. An app's row layout has stable ids (title, price, quantity)
 * while the values in them change every visit, so no semantic typing of fields is needed.
 *
 * - Ids are compared exactly, as captured (`package:id/name`) — no suffix matching, which would let
 *   one package's `id/title` satisfy another's.
 * - Rows are the container's direct children in the tree this trait is evaluated against. On a
 *   scroll-merged capture that tree is exactly what [SyntheticAccessibilityTreeBuilder] produced,
 *   which can differ from the rows on screen: the merge may collapse rows that look identical, and a
 *   list inside a scrolled wrapper may appear as one or more copies of the container. [minRows] is
 *   checked against the merged shape; the merge, not this class, owns what that shape is.
 * - A row's ids are those of its descendants; the row's own id is not part of the schema.
 * - A single container must qualify on its own. Rows are never pooled across two containers that
 *   share an id.
 *
 * Refuses, at construction, the shapes that would be vacuously true: with `minRows < 1` any
 * container carrying that id and at least one child qualifies whatever its rows carry, and an empty
 * schema counts any child as a row. A weak assertion fails when it is made, not three hundred screens
 * later.
 *
 * Keeps its own read-only copy of [rowChildResourceIds], so neither a caller that later empties the
 * set it passed in, nor one that casts the getter's result to a mutable set, can make the trait
 * vacuous after the check. That is why this is a plain class with written-out equality rather than
 * a data class, which would keep the caller's set as given.
 */
class HasList(
    val containerResourceId: String,
    val minRows: Int,
    rowChildResourceIds: Set<String>,
) : Trait {
    val rowChildResourceIds: Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(rowChildResourceIds))

    init {
        require(containerResourceId.isNotBlank()) { "HasList needs a container resource id." }
        require(minRows >= 1) { "HasList.minRows must be at least 1, was $minRows." }
        require(this.rowChildResourceIds.isNotEmpty()) {
            "HasList needs at least one row child resource id."
        }
        require(this.rowChildResourceIds.none(String::isBlank)) {
            "HasList row child resource ids must not be blank."
        }
    }

    override fun holds(context: TraitEvaluationContext): Boolean {
        return context.rowsOfContainersWithId(containerResourceId).any { rows ->
            rows.count { rowIds -> rowIds.containsAll(rowChildResourceIds) } >= minRows
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is HasList &&
            containerResourceId == other.containerResourceId &&
            minRows == other.minRows &&
            rowChildResourceIds == other.rowChildResourceIds
    }

    override fun hashCode(): Int {
        var result = containerResourceId.hashCode()
        result = 31 * result + minRows
        result = 31 * result + rowChildResourceIds.hashCode()
        return result
    }

    override fun toString(): String {
        return "HasList(containerResourceId=$containerResourceId, minRows=$minRows, " +
            "rowChildResourceIds=$rowChildResourceIds)"
    }
}

/**
 * A specific stable control is present.
 *
 * Compared on [ElementFingerprint] only, against the pressables [ScreenIdentity.fromRoot] collects —
 * the same builder every identity uses. [ScreenElementIdentity.isBackAffordance] takes no part: it is
 * derived from position, and a control that moved is the same control.
 */
data class HasControl(val element: ScreenElementIdentity) : Trait {
    override fun holds(context: TraitEvaluationContext): Boolean =
        element.fingerprint in context.pressableFingerprints
}

/**
 * A specific control is absent — the negation of [HasControl], under the same comparison.
 *
 * Supported so the conjunction is not assumed positive-only; nothing produces one yet. A negation
 * says what a screen is *not*, so it can only narrow an identity that also asserts something
 * present: an identity made of negations alone is unsettled, because it would hold on a blank
 * screen.
 */
data class LacksControl(val element: ScreenElementIdentity) : Trait {
    override fun holds(context: TraitEvaluationContext): Boolean =
        element.fingerprint !in context.pressableFingerprints
}

/**
 * Everything a [Trait] may ask about one observed tree, gathered in a single build so that
 * evaluating any number of candidate screens is set lookups rather than tree walks.
 *
 * [packageName] and [nameKey] let [TraitEvaluator] check the rest of the identity alongside the
 * traits. [nameKey] is null for an unnamed probe — a live root that has not been through the
 * naming pass.
 */
class TraitEvaluationContext private constructor(
    val packageName: String?,
    val nameKey: String?,
    internal val pressableFingerprints: Set<ElementFingerprint>,
    private val containerRowsById: Map<String, List<List<Set<String>>>>,
) {
    /**
     * The rows of the containers carrying [resourceId], each row as the set of resource ids beneath
     * it. Promises only what [HasList.holds] observes: every non-blank id beneath each row of every
     * such container that has children. Whether childless containers, blank ids or rows without ids
     * appear is unspecified — none of them can satisfy a non-empty, blank-free schema.
     */
    internal fun rowsOfContainersWithId(resourceId: String): List<List<Set<String>>> =
        containerRowsById[resourceId].orEmpty()

    companion object {
        /**
         * Builds the context for [root], optionally with the [name] the naming pass gave it.
         *
         * The name key is the **ungated** [DedupPolicy.nameKey]: whether a screen may be
         * catalogued is a separate question from what it is called, and a weak-titled screen must
         * still equal itself.
         */
        fun of(
            root: AccessibilityNodeSnapshot,
            name: ScreenNameIdentity? = null,
        ): TraitEvaluationContext {
            val observed = ScreenIdentity.fromRoot(root).let { identity ->
                if (name == null) identity else identity.withName(name)
            }
            val containerRowsById = linkedMapOf<String, MutableList<List<Set<String>>>>()
            indexContainers(root, containerRowsById)
            return TraitEvaluationContext(
                packageName = observed.packageName,
                nameKey = DedupPolicy.nameKey(observed),
                pressableFingerprints = observed.elements.mapTo(hashSetOf()) { it.fingerprint },
                containerRowsById = containerRowsById,
            )
        }

        /**
         * One post-order walk that builds the index behind [rowsOfContainersWithId]; what that
         * index promises is stated there, and only there.
         */
        private fun indexContainers(
            node: AccessibilityNodeSnapshot,
            containerRowsById: MutableMap<String, MutableList<List<Set<String>>>>,
        ): SubtreeIds {
            val children = node.children.map { child -> indexContainers(child, containerRowsById) }
            val ownId = node.viewIdResourceName?.takeIf(String::isNotBlank)
            if (ownId != null && children.isNotEmpty()) {
                containerRowsById.getOrPut(ownId) { mutableListOf() } +=
                    children.map(SubtreeIds::beneath)
            }
            val beneath = children.flatMapTo(hashSetOf(), SubtreeIds::includingSelf)
            return SubtreeIds(
                beneath = beneath,
                includingSelf = if (ownId == null) beneath else beneath + ownId,
            )
        }
    }

    private class SubtreeIds(
        val beneath: Set<String>,
        val includingSelf: Set<String>,
    )
}

/** The trait predicate's answer for one identity. Tri-state so *unsettled* is never read as either. */
enum class TraitVerdict {
    HOLDS,
    DOES_NOT_HOLD,

    /**
     * The identity asserts nothing present on the screen — it has no traits yet, or only
     * negations. Never a match, and never a non-match.
     */
    UNSETTLED,
}

/**
 * Which known screens' identities hold against one observed tree.
 *
 * Every id list is sorted, so the result does not depend on the order the known screens were
 * presented in.
 */
sealed interface ScreenTraitMatch {
    /** Known screens whose identity is not settled, so which are not candidates. */
    val unsettledScreenIds: List<String>

    /** No known screen holds here — a new screen. */
    data class None(override val unsettledScreenIds: List<String>) : ScreenTraitMatch

    /** Exactly one known screen holds here — a known screen. */
    data class One(
        val screenId: String,
        override val unsettledScreenIds: List<String>,
    ) : ScreenTraitMatch

    /**
     * More than one known screen holds here: a uniqueness violation, and a case for an operator.
     *
     * Deliberately offers no way to pick a winner. Resolving it is the operator's decision.
     */
    data class Ambiguous(
        val screenIds: List<String>,
        override val unsettledScreenIds: List<String>,
    ) : ScreenTraitMatch {
        init {
            require(screenIds.size >= 2) { "Ambiguous needs at least two screens, was $screenIds." }
        }
    }
}

/** Evaluates identities' traits against an observed tree. */
object TraitEvaluator {
    /**
     * The trait predicate alone: [TraitVerdict.UNSETTLED] when [identity] asserts nothing present,
     * otherwise whether every trait holds.
     *
     * The unsettled guard lives here and only here. In Kotlin `emptyList().all {}` is true, so
     * without it a screen with no traits would hold against every tree and match everything — and a
     * screen of negations alone would hold against every blank one.
     */
    fun verdict(identity: ScreenIdentity, context: TraitEvaluationContext): TraitVerdict {
        if (identity.traits.none(::assertsPresence)) return TraitVerdict.UNSETTLED
        return if (identity.traits.all { trait -> trait.holds(context) }) {
            TraitVerdict.HOLDS
        } else {
            TraitVerdict.DOES_NOT_HOLD
        }
    }

    /**
     * Runs every known screen's identity against [context] and reports zero, one, or more than one.
     *
     * Evaluating against **every** known screen, not only an expected one, is what makes a
     * uniqueness violation detectable. A known screen is a candidate when its traits hold, its
     * package equals the observed package — an observed tree with no package matches nothing — and,
     * only when both sides have one, its name key equals the observed name key. Uniqueness is
     * expected from the whole identity, so two screens with the same traits but different names are
     * not ambiguous once the observed screen is named.
     */
    fun matchKnownScreens(
        context: TraitEvaluationContext,
        knownScreens: Map<String, ScreenIdentity>,
    ): ScreenTraitMatch {
        val unsettled = mutableListOf<String>()
        val candidates = mutableListOf<String>()
        knownScreens.forEach { (screenId, identity) ->
            when (verdict(identity, context)) {
                TraitVerdict.UNSETTLED -> unsettled += screenId
                TraitVerdict.DOES_NOT_HOLD -> Unit
                TraitVerdict.HOLDS -> if (matchesPackageAndName(identity, context)) {
                    candidates += screenId
                }
            }
        }
        unsettled.sort()
        candidates.sort()
        return when (candidates.size) {
            0 -> ScreenTraitMatch.None(unsettled)
            1 -> ScreenTraitMatch.One(candidates.single(), unsettled)
            else -> ScreenTraitMatch.Ambiguous(candidates, unsettled)
        }
    }

    /** Exhaustive, so a new kind of trait has to say whether it asserts something present. */
    private fun assertsPresence(trait: Trait): Boolean = when (trait) {
        is HasList, is HasControl -> true
        is LacksControl -> false
    }

    private fun matchesPackageAndName(
        identity: ScreenIdentity,
        context: TraitEvaluationContext,
    ): Boolean {
        val observedPackage = context.packageName ?: return false
        if (identity.packageName != observedPackage) return false
        val knownNameKey = DedupPolicy.nameKey(identity) ?: return true
        val observedNameKey = context.nameKey ?: return true
        return knownNameKey == observedNameKey
    }
}
