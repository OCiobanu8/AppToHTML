package com.example.apptohtml.crawler

import com.example.apptohtml.crawler.Lifecycle.contextOf
import com.example.apptohtml.crawler.Lifecycle.identityOf
import com.example.apptohtml.crawler.Lifecycle.known
import com.example.apptohtml.crawler.Lifecycle.name
import com.example.apptohtml.crawler.Lifecycle.settled
import com.example.apptohtml.crawler.Lifecycle.unsettled
import com.example.apptohtml.crawler.LifecycleTrees.CART_B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins for `a2h-c2b.3` scope call 2: a proposal holds when it matches its own screen, and only it. */
class IdentitySettlementTest {

    @Test
    fun a_proposal_that_holds_on_its_own_screen_and_nowhere_else_is_settled() {
        val tree = LifecycleTrees.cart()
        val other = LifecycleTrees.buttons("profile", "orders")

        val decision = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_001", other, settled(other, name("Account")))),
        )

        assertEquals(IdentitySettlement.Decision.Settled, decision)
    }

    @Test
    fun a_proposal_that_asserts_nothing_is_not_settled() {
        val tree = LifecycleTrees.idless()

        val decision = decide(tree, name("About phone"), others = emptyList())

        assertEquals(IdentitySettlement.Decision.AssertsNothing, decision)
    }

    @Test
    fun a_proposal_that_fails_on_its_own_screen_names_the_failing_traits() {
        val tree = LifecycleTrees.cart()
        val missing = HasControl(
            ScreenIdentity.fromRoot(LifecycleTrees.buttons("gift_wrap")).elements.single(),
        )
        val proposal = identityOf(tree, name("Cart"), TraitProposer.propose(tree) + missing)

        val decision = IdentitySettlement.decide(proposal, contextOf(tree, name("Cart")), emptyList())

        assertEquals(IdentitySettlement.Decision.FailsOnOwnScreen(listOf(missing)), decision)
        val described = (decision as IdentitySettlement.Decision.FailsOnOwnScreen).describe()
        assertTrue(described, described.contains("missing control ${missing.element.fingerprint.label}"))
    }

    @Test
    fun a_proposal_that_also_holds_on_a_same_named_known_screen_names_it() {
        val tree = LifecycleTrees.cart()
        val lookalike = LifecycleTrees.cart(CART_B)

        val decision = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_004", lookalike, unsettled(lookalike, name("Cart")))),
        )

        assertEquals(IdentitySettlement.Decision.AlsoHolds(listOf("screen_004")), decision)
    }

    @Test
    fun the_reversed_check_lets_the_name_tell_two_lookalikes_apart() {
        val tree = LifecycleTrees.cart()
        val lookalike = LifecycleTrees.cart(CART_B)

        val differentName = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_004", lookalike, unsettled(lookalike, name("Wishlist")))),
        )
        val sameName = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_004", lookalike, unsettled(lookalike, name("Cart")))),
        )

        assertEquals(IdentitySettlement.Decision.Settled, differentName)
        assertEquals(IdentitySettlement.Decision.AlsoHolds(listOf("screen_004")), sameName)
    }

    @Test
    fun the_reversed_check_ignores_another_package() {
        val tree = LifecycleTrees.cart()
        val foreign = LifecycleTrees.cart(CART_B, packageName = "com.example.other")

        val decision = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_009", foreign, unsettled(foreign, name("Cart")))),
        )

        assertEquals(IdentitySettlement.Decision.Settled, decision)
    }

    @Test
    fun a_weak_named_lookalike_is_a_collision_too() {
        val weak = name("Navigate up", ScreenDedupConfidence.WEAK)
        val tree = LifecycleTrees.cart()
        val lookalike = LifecycleTrees.cart(CART_B)

        val decision = decide(
            tree,
            weak,
            others = listOf(known("screen_002", lookalike, settled(lookalike, weak))),
        )

        assertEquals(IdentitySettlement.Decision.AlsoHolds(listOf("screen_002")), decision)
    }

    @Test
    fun an_unreadable_known_capture_blocks_settling_rather_than_counting_as_unique() {
        val tree = LifecycleTrees.cart()

        val decision = decide(
            tree,
            name("Cart"),
            others = listOf(known("screen_003", null, unsettled(tree, name("Other")))),
        )

        assertEquals(IdentitySettlement.Decision.CannotCheckAgainst(listOf("screen_003")), decision)
    }

    /** Round-3 D4: a collision outranks an unreadable capture, so the colliding screen is named. */
    @Test
    fun a_collision_is_reported_over_an_unreadable_capture() {
        val tree = LifecycleTrees.cart()
        val lookalike = LifecycleTrees.cart(CART_B)

        val decision = decide(
            tree,
            name("Cart"),
            others = listOf(
                known("screen_003", null, unsettled(tree, name("Other"))),
                known("screen_004", lookalike, unsettled(lookalike, name("Cart"))),
            ),
        )

        assertEquals(IdentitySettlement.Decision.AlsoHolds(listOf("screen_004")), decision)
    }

    @Test
    fun every_collision_is_named_sorted_whatever_the_order_given() {
        val tree = LifecycleTrees.cart()
        val others = listOf("screen_007", "screen_002", "screen_005").map { id ->
            val lookalike = LifecycleTrees.cart(CART_B)
            known(id, lookalike, unsettled(lookalike, name("Cart")))
        }

        val forward = decide(tree, name("Cart"), others)
        val backward = decide(tree, name("Cart"), others.reversed())

        assertEquals(
            IdentitySettlement.Decision.AlsoHolds(listOf("screen_002", "screen_005", "screen_007")),
            forward,
        )
        assertEquals(forward, backward)
    }

    @Test
    fun every_reason_describes_itself_by_naming_what_failed() {
        assertTrue(
            IdentitySettlement.Decision.AlsoHolds(listOf("screen_002", "screen_005")).describe()
                .contains("screen_002, screen_005"),
        )
        assertTrue(
            IdentitySettlement.Decision.CannotCheckAgainst(listOf("screen_003")).describe()
                .contains("screen_003"),
        )
        assertTrue(IdentitySettlement.Decision.AssertsNothing.describe().isNotBlank())
    }

    @Test
    fun a_refusal_is_a_proposal_case_except_also_holding_under_a_weak_name() {
        val strong = name("Cart")
        val weak = name("Navigate up", ScreenDedupConfidence.WEAK)
        val alsoHolds = IdentitySettlement.Decision.AlsoHolds(listOf("screen_001"))
        val proposalKind = IdentityCaseKind.PROPOSAL_DOES_NOT_HOLD

        assertEquals(IdentityCaseKind.UNIQUENESS_COLLISION, IdentitySettlement.caseKindFor(alsoHolds, weak))
        assertEquals(IdentityCaseKind.UNIQUENESS_COLLISION, IdentitySettlement.caseKindFor(alsoHolds, null))
        assertEquals(proposalKind, IdentitySettlement.caseKindFor(alsoHolds, strong))
        listOf(strong, weak).forEach { screenName ->
            assertEquals(proposalKind, IdentitySettlement.caseKindFor(IdentitySettlement.Decision.AssertsNothing, screenName))
            assertEquals(
                proposalKind,
                IdentitySettlement.caseKindFor(IdentitySettlement.Decision.FailsOnOwnScreen(emptyList()), screenName),
            )
            assertEquals(
                proposalKind,
                IdentitySettlement.caseKindFor(IdentitySettlement.Decision.CannotCheckAgainst(listOf("s")), screenName),
            )
        }
    }

    private fun decide(
        tree: AccessibilityNodeSnapshot,
        screenName: ScreenNameIdentity,
        others: List<KnownScreen>,
    ): IdentitySettlement.Decision = IdentitySettlement.decide(
        proposal = settled(tree, screenName),
        ownContext = contextOf(tree, screenName),
        others = others,
    )
}
