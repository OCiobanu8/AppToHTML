package com.example.apptohtml.crawler

/**
 * Proposes the traits a new screen's identity asserts, from its first-viewport tree.
 *
 * A proposal, not a verdict: whether it *holds* is decided by [IdentitySettlement], through
 * [TraitEvaluator]. This class only decides what to assert, and it asserts only **structure and
 * resource ids** — the material that, across two crawls of the same app, never churned — and never an
 * id-less instance, which is where all the churn was (`.spear/a2h-u68/evidence/12-evidence-for-c2b3`).
 *
 * - **Lists.** A container whose rows repeat a shape is asserted as a [HasList] over the ids those
 *   rows share. `minRows` is always 1, so a list that shrinks to one item still holds; the observed
 *   row count is volatile and is never copied in.
 * - **Controls.** Every pressable with a resource id becomes a [HasControl], except a back
 *   affordance (it identifies nothing) and anything inside a proposed list (its rows are data).
 *
 * Deterministic: the same tree, whatever its child order, proposes the same traits in the same order
 * — lists by container id, then controls by [ScreenElementIdentity.encoded].
 */
object TraitProposer {

    /**
     * Fewest rows that must share a shape before a container is proposed as a list. One row is not
     * yet a repetition, so it says nothing about the list's schema.
     */
    const val MIN_PROPOSED_ROWS = 2

    /** The traits [root]'s identity would assert. Empty when the tree offers nothing stable. */
    fun propose(root: AccessibilityNodeSnapshot): List<Trait> {
        val context = TraitEvaluationContext.of(root)
        val lists = proposeLists(context)
        val listNodes = context.containers
            .filter { container -> lists.any { it.containerResourceId == container.resourceId } }
            .map { it.node }
        return lists + proposeControls(root, listNodes)
    }

    private fun proposeLists(context: TraitEvaluationContext): List<HasList> {
        val schemasById = sortedMapOf<String, MutableList<Set<String>>>()
        context.containers.forEach { container ->
            val schema = sharedRowSchema(container.rows) ?: return@forEach
            schemasById.getOrPut(container.resourceId) { mutableListOf() } += schema
        }
        // Two qualifying containers can share an id. [HasList] holds when any one of them qualifies,
        // so the proposal asserts only what every one of them carries.
        return schemasById.mapNotNull { (resourceId, schemas) ->
            val shared = schemas.reduce { acc, schema -> acc intersect schema }
            if (shared.isEmpty()) {
                null
            } else {
                HasList(
                    containerResourceId = resourceId,
                    minRows = 1,
                    rowChildResourceIds = shared.toSortedSet(),
                )
            }
        }
    }

    /**
     * The ids every id-bearing row carries, when at least [MIN_PROPOSED_ROWS] rows carry any; null
     * when the container does not repeat a shape. Rows with no ids at all are dividers or headers and
     * do not vote.
     */
    private fun sharedRowSchema(rows: List<Set<String>>): Set<String>? {
        val idRows: List<Set<String>> = rows
            .map { row -> row.filterTo(sortedSetOf(), String::isNotBlank) }
            .filter(Set<String>::isNotEmpty)
        if (idRows.size < MIN_PROPOSED_ROWS) return null
        return idRows.reduce { acc, row -> acc intersect row }.takeIf(Set<String>::isNotEmpty)
    }

    private fun proposeControls(
        root: AccessibilityNodeSnapshot,
        listNodes: List<AccessibilityNodeSnapshot>,
    ): List<HasControl> {
        // Pressables inside a proposed list are found by running the production collector over the
        // list node itself, so "inside" is read off the same nodes and never re-derived from paths
        // the caller supplied.
        val insideLists = listNodes
            .flatMap(AccessibilityTreeSnapshotter::collectPressableElements)
            .mapTo(hashSetOf()) { it.childIndexPath to it.bounds }
        return AccessibilityTreeSnapshotter.collectPressableElements(root)
            .asSequence()
            .filterNot { element -> (element.childIndexPath to element.bounds) in insideLists }
            .distinctBy(ElementFingerprint::of)
            .filterNot { element -> element.resourceId.isNullOrBlank() }
            .filterNot(BackAffordanceTokens::looksLikeEntryBackAffordance)
            .map { element ->
                ScreenElementIdentity(
                    fingerprint = ElementFingerprint.of(element),
                    isBackAffordance = false,
                )
            }
            .sortedBy(ScreenElementIdentity::encoded)
            .map(::HasControl)
            .toList()
    }
}
