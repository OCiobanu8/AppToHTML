package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.LifecycleTrees.CART_A
import com.example.apptohtml.crawler.LifecycleTrees.CART_B
import com.example.apptohtml.crawler.LifecycleTrees.CART_ITEMS
import com.example.apptohtml.crawler.LifecycleTrees.CHECKOUT
import com.example.apptohtml.crawler.LifecycleTrees.LINEAR
import com.example.apptohtml.crawler.LifecycleTrees.NodeSpec
import com.example.apptohtml.crawler.LifecycleTrees.PRICE
import com.example.apptohtml.crawler.LifecycleTrees.QUANTITY
import com.example.apptohtml.crawler.LifecycleTrees.RECYCLER
import com.example.apptohtml.crawler.LifecycleTrees.TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins for `a2h-c2b.3` scope call A: what the machine proposes for a new screen. */
class TraitProposerTest {

    @Test
    fun cart_is_proposed_as_its_list_and_its_id_bearing_control() {
        val traits = TraitProposer.propose(LifecycleTrees.cart())

        assertEquals(
            listOf(
                HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(PRICE, QUANTITY, TITLE)),
                HasControl(controlWithId(LifecycleTrees.cart(), CHECKOUT)),
            ),
            traits,
        )
    }

    @Test
    fun cart_proposal_names_no_idless_control_no_back_affordance_and_no_row() {
        val controls = TraitProposer.propose(LifecycleTrees.cart()).filterIsInstance<HasControl>()
        val ids = controls.map { it.element.fingerprint.resourceId }
        val labels = controls.map { it.element.fingerprint.label }

        assertEquals(listOf(CHECKOUT), ids)
        assertTrue(
            "the fixture's back affordance must carry an id, or this pin cannot see the back rule",
            ScreenIdentity.fromRoot(LifecycleTrees.cart()).elements
                .any { it.isBackAffordance && !it.fingerprint.resourceId.isNullOrBlank() },
        )
        assertTrue("id-less Help button proposed", "help" !in labels)
        assertTrue("back affordance proposed", "navigate up" !in labels)
        assertTrue(
            "a list row proposed as a control",
            controls.none { it.element.fingerprint.resourceId == LifecycleTrees.ROW_ID },
        )
    }

    @Test
    fun the_proposed_list_survives_rows_changing_and_shrinking_to_one() {
        val proposal = ScreenIdentity.fromRoot(LifecycleTrees.cart())
            .copy(traits = TraitProposer.propose(LifecycleTrees.cart()))
        val oneRow = LifecycleTrees.cart(items = CART_B.take(1))

        assertEquals(
            TraitVerdict.HOLDS,
            TraitEvaluator.verdict(proposal, TraitEvaluationContext.of(LifecycleTrees.cart(CART_B))),
        )
        assertEquals(
            TraitVerdict.HOLDS,
            TraitEvaluator.verdict(proposal, TraitEvaluationContext.of(oneRow)),
        )
    }

    @Test
    fun a_screen_of_idless_controls_and_no_list_proposes_nothing() {
        assertEquals(emptyList<Trait>(), TraitProposer.propose(LifecycleTrees.idless()))
    }

    @Test
    fun child_order_does_not_change_the_proposal() {
        val tree = LifecycleTrees.cart(extraButtonIds = listOf("share", "apply_coupon"))

        assertEquals(
            TraitProposer.propose(tree),
            TraitProposer.propose(LifecycleTrees.reversed(tree)),
        )
        assertEquals(
            serialized(TraitProposer.propose(tree)),
            serialized(TraitProposer.propose(LifecycleTrees.reversed(tree))),
        )
    }

    @Test
    fun controls_are_ordered_by_their_encoding() {
        val traits = TraitProposer.propose(LifecycleTrees.buttons("zeta", "alpha", "mid"))
        val encodings = traits.filterIsInstance<HasControl>().map { it.element.encoded }

        assertEquals(encodings.sorted(), encodings)
        assertEquals(3, encodings.size)
    }

    /** Round-4 D7: the same control shown twice (same fingerprint, other place) is asserted once. */
    @Test
    fun a_control_shown_twice_is_asserted_once() {
        val traits = TraitProposer.propose(LifecycleTrees.buttons("share", "share"))

        assertEquals(1, traits.filterIsInstance<HasControl>().size)
    }

    @Test
    fun a_container_with_one_id_bearing_row_is_not_a_list() {
        val traits = TraitProposer.propose(listWithRows(listOf(setOf(TITLE))))

        assertTrue(traits.filterIsInstance<HasList>().isEmpty())
    }

    @Test
    fun rows_that_share_no_id_are_not_a_list() {
        val traits = TraitProposer.propose(listWithRows(listOf(setOf(TITLE), setOf(PRICE))))

        assertTrue(traits.filterIsInstance<HasList>().isEmpty())
    }

    @Test
    fun the_schema_is_what_every_id_bearing_row_carries_and_idless_rows_do_not_vote() {
        val traits = TraitProposer.propose(
            listWithRows(listOf(setOf(TITLE, PRICE), setOf(TITLE), emptySet(), setOf(TITLE, QUANTITY))),
        )

        assertEquals(
            listOf(HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE))),
            traits.filterIsInstance<HasList>(),
        )
    }

    @Test
    fun two_containers_sharing_an_id_are_proposed_once_over_what_both_carry() {
        val tree = LifecycleTrees.build(
            NodeSpec(
                className = "android.widget.FrameLayout",
                resourceId = null,
                children = listOf(
                    listSpec(listOf(setOf(TITLE, PRICE), setOf(TITLE, PRICE))),
                    listSpec(listOf(setOf(TITLE, QUANTITY), setOf(TITLE, QUANTITY))),
                ),
            ),
        )

        assertEquals(
            listOf(HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE))),
            TraitProposer.propose(tree).filterIsInstance<HasList>(),
        )
    }

    @Test
    fun every_proposal_holds_on_the_tree_it_came_from() {
        listOf(
            LifecycleTrees.cart(),
            LifecycleTrees.cart(CART_B, extraButtonIds = listOf("share")),
            LifecycleTrees.buttons("a", "b"),
        ).forEach { tree ->
            val proposal = ScreenIdentity.fromRoot(tree).copy(traits = TraitProposer.propose(tree))
            assertEquals(
                TraitVerdict.HOLDS,
                TraitEvaluator.verdict(proposal, TraitEvaluationContext.of(tree)),
            )
        }
    }

    private fun controlWithId(root: AccessibilityNodeSnapshot, resourceId: String): ScreenElementIdentity =
        ScreenIdentity.fromRoot(root).elements.single { it.fingerprint.resourceId == resourceId }

    private fun serialized(traits: List<Trait>): String =
        StringBuilder().also { ScreenIdentityXml.appendTraits(it, traits, depth = 0) }.toString()

    private fun listWithRows(rows: List<Set<String>>): AccessibilityNodeSnapshot =
        LifecycleTrees.build(
            NodeSpec(
                className = "android.widget.FrameLayout",
                resourceId = null,
                children = listOf(listSpec(rows)),
            ),
        )

    private fun listSpec(rows: List<Set<String>>): NodeSpec = NodeSpec(
        className = RECYCLER,
        resourceId = CART_ITEMS,
        scrollable = true,
        children = rows.map { ids ->
            NodeSpec(
                className = LINEAR,
                resourceId = null,
                children = ids.sorted().map { id ->
                    NodeSpec("android.widget.TextView", id, text = id.substringAfterLast('/'))
                }.ifEmpty { listOf(NodeSpec("android.view.View", null)) },
            )
        },
    )
}
