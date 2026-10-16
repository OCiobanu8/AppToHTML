package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.Lifecycle.contextOf
import com.example.apptohtml.crawler.Lifecycle.name
import com.example.apptohtml.crawler.Lifecycle.settled
import com.example.apptohtml.crawler.Lifecycle.unsettled
import com.example.apptohtml.crawler.LifecycleTrees.CART_B
import com.example.apptohtml.crawler.ScreenRecognition.Result
import com.example.apptohtml.crawler.ScreenRecognition.Via
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins for `a2h-c2b.3` scope calls C and D and the Gate 2 WEAK amendment: dedup resolves by running
 * the known identities against the screen.
 */
class ScreenRecognitionTest {

    private val cart = LifecycleTrees.cart()
    private val cartAgain = LifecycleTrees.cart(CART_B)
    private val account = LifecycleTrees.buttons("profile", "orders")

    @Test
    fun one_settled_identity_holding_links_to_it() {
        val result = recognize(
            cartAgain,
            name("Cart"),
            known = mapOf(
                "screen_001" to settled(cart, name("Cart")),
                "screen_002" to settled(account, name("Account")),
            ),
        )

        assertEquals(Result.Link("screen_001", Via.TRAITS), result)
    }

    @Test
    fun nothing_holding_is_a_new_screen() {
        val result = recognize(
            cart,
            name("Cart"),
            known = mapOf("screen_002" to settled(account, name("Account"))),
        )

        assertEquals(Result.New, result)
    }

    @Test
    fun two_settled_identities_holding_is_a_collision_naming_both_and_merging_neither() {
        val result = recognize(
            cartAgain,
            name("Cart"),
            known = mapOf(
                "screen_005" to settled(cart, name("Cart")),
                "screen_001" to settled(cart, name("Cart")),
            ),
        )

        assertEquals(Result.Collision(listOf("screen_001", "screen_005")), result)
    }

    @Test
    fun a_weak_named_screen_is_never_linked_even_when_exactly_one_identity_holds() {
        val weak = name("Navigate up", ScreenDedupConfidence.WEAK)

        val result = recognize(
            cartAgain,
            weak,
            known = mapOf("screen_001" to settled(cart, weak)),
        )

        assertEquals(Result.Collision(listOf("screen_001")), result)
    }

    @Test
    fun an_unsettled_screen_with_the_same_strong_name_is_linked_by_the_name_rule() {
        val result = recognize(
            LifecycleTrees.idless(),
            name("About phone"),
            known = mapOf(
                "screen_003" to unsettled(LifecycleTrees.idless(), name("About phone")),
                "screen_002" to settled(account, name("Account")),
            ),
        )

        assertEquals(Result.Link("screen_003", Via.UNSETTLED_NAME), result)
    }

    @Test
    fun a_settled_screen_is_never_linked_by_name_alone() {
        val renamedButSameName = LifecycleTrees.buttons("logout")

        val result = recognize(
            renamedButSameName,
            name("Account"),
            known = mapOf("screen_002" to settled(account, name("Account"))),
        )

        assertEquals(Result.New, result)
    }

    @Test
    fun a_weak_name_gets_no_name_fallback() {
        val weak = name("Navigate up", ScreenDedupConfidence.WEAK)

        val result = recognize(
            LifecycleTrees.idless(),
            weak,
            known = mapOf("screen_003" to unsettled(LifecycleTrees.idless(), weak)),
        )

        assertEquals(Result.New, result)
    }

    @Test
    fun the_name_fallback_never_links_to_a_weak_known_screen() {
        val result = recognize(
            LifecycleTrees.idless(),
            name("About phone"),
            known = mapOf(
                "screen_003" to unsettled(
                    LifecycleTrees.idless(),
                    name("About phone", ScreenDedupConfidence.WEAK),
                ),
            ),
        )

        assertEquals(Result.New, result)
    }

    @Test
    fun the_name_fallback_picks_the_lowest_id_whatever_the_order_given() {
        val known = linkedMapOf(
            "screen_009" to unsettled(LifecycleTrees.idless(), name("About phone")),
            "screen_004" to unsettled(LifecycleTrees.idless(), name("About phone")),
        )

        val forward = recognize(LifecycleTrees.idless(), name("About phone"), known)
        val backward = recognize(
            LifecycleTrees.idless(),
            name("About phone"),
            LinkedHashMap(known.entries.reversed().associate { it.key to it.value }),
        )

        assertEquals(Result.Link("screen_004", Via.UNSETTLED_NAME), forward)
        assertEquals(forward, backward)
    }

    @Test
    fun recognition_does_not_depend_on_the_order_known_screens_arrive_in() {
        val known = linkedMapOf(
            "screen_005" to settled(cart, name("Cart")),
            "screen_001" to settled(cart, name("Cart")),
            "screen_002" to settled(account, name("Account")),
        )
        val reversed = LinkedHashMap(known.entries.reversed().associate { it.key to it.value })

        assertEquals(
            recognize(cartAgain, name("Cart"), known),
            recognize(cartAgain, name("Cart"), reversed),
        )
    }

    /**
     * Round-1 D10, SCOPE "Determinism and precedence": the coordinator's two decision seams —
     * recognition, then settlement for a new screen — and the case they would open, are identical
     * whichever order the known screens arrive in, across every outcome.
     */
    @Test
    fun recognition_settlement_and_the_resulting_case_are_identical_in_any_known_screen_order() {
        val known = listOf(
            Lifecycle.known("screen_005", cart, settled(cart, name("Cart"))),
            Lifecycle.known("screen_001", cart, settled(cart, name("Cart"))),
            Lifecycle.known("screen_002", account, settled(account, name("Account"))),
            Lifecycle.known("screen_003", LifecycleTrees.idless(), unsettled(LifecycleTrees.idless(), name("About phone"))),
            Lifecycle.known("screen_004", cartAgain, unsettled(cartAgain, name("Wishlist"))),
        )
        val observations = listOf(
            cartAgain to name("Cart"),
            cartAgain to name("Wishlist"),
            LifecycleTrees.idless() to name("About phone"),
            LifecycleTrees.buttons("logout") to name("Account"),
            LifecycleTrees.cart(extraButtonIds = listOf("share")) to name("Navigate up", ScreenDedupConfidence.WEAK),
        )

        fun decideAll(order: List<KnownScreen>): List<String> = observations.map { (tree, screenName) ->
            val identity = ScreenIdentity.fromRoot(tree).withName(screenName)
            val context = contextOf(tree, screenName)
            val recognition = ScreenRecognition.recognize(
                identity,
                context,
                order.associateTo(linkedMapOf()) { it.screenId to it.identity },
            )
            val settlement = IdentitySettlement.decide(
                identity.copy(traits = TraitProposer.propose(tree)),
                context,
                order,
            )
            val case = (settlement as? IdentitySettlement.Decision.NotSettled)?.let { refused ->
                IdentityCaseStore.serialize(
                    listOf(
                        IdentityCase(
                            id = "case_000",
                            status = IdentityCaseStatus.OPEN,
                            kind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD,
                            raisedOn = "screen_new",
                            relatedScreenIds = (refused as? IdentitySettlement.Decision.AlsoHolds)?.screenIds.orEmpty(),
                            detail = refused.describe(),
                        ),
                    ),
                )
            }
            "$recognition | $settlement | $case"
        }

        assertEquals(decideAll(known), decideAll(known.reversed()))
        assertEquals(decideAll(known), decideAll(known.sortedBy { it.screenId }))
    }

    private fun recognize(
        tree: AccessibilityNodeSnapshot,
        screenName: ScreenNameIdentity,
        known: Map<String, ScreenIdentity>,
    ): Result = ScreenRecognition.recognize(
        observed = ScreenIdentity.fromRoot(tree).withName(screenName),
        observedContext = contextOf(tree, screenName),
        known = known,
    )
}
