package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins for `a2h-c2b.4`: a screen identity's traits are assertions checked against the live tree,
 * evaluated against every known screen, and reported as zero, one, or more than one.
 *
 * Every tree here is a synthetic [AccessibilityNodeSnapshot]; nothing mocks the framework.
 */
class ScreenTraitsTest {

    // ---- the hierarchy -------------------------------------------------------------------

    @Test
    fun trait_is_one_sealed_hierarchy_of_exactly_three_kinds() {
        val checkout = controlIn(cartRoot(CART_A), "checkout")
        val traits: List<Trait> = listOf(
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = ROW_SCHEMA),
            HasControl(checkout),
            LacksControl(checkout),
        )

        // Compiles only while this `when` is exhaustive without `else` — that is, while Trait is
        // sealed and these three are its only kinds.
        val kinds = traits.map { trait ->
            when (trait) {
                is HasList -> "list"
                is HasControl -> "has"
                is LacksControl -> "lacks"
            }
        }

        assertEquals(listOf("list", "has", "lacks"), kinds)
    }

    @Test
    fun vacuous_constructions_are_refused_when_made() {
        assertRejected("minRows = 0") {
            HasList(CART_ITEMS, minRows = 0, rowChildResourceIds = ROW_SCHEMA)
        }
        assertRejected("negative minRows") {
            HasList(CART_ITEMS, minRows = -1, rowChildResourceIds = ROW_SCHEMA)
        }
        assertRejected("empty row schema") {
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = emptySet())
        }
        assertRejected("empty container id") {
            HasList("", minRows = 1, rowChildResourceIds = ROW_SCHEMA)
        }
        assertRejected("whitespace-only container id") {
            HasList(" ", minRows = 1, rowChildResourceIds = ROW_SCHEMA)
        }
        assertRejected("empty row child id") {
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE, ""))
        }
        assertRejected("whitespace-only row child id, last") {
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = linkedSetOf(TITLE, " "))
        }
        assertRejected("whitespace-only row child id, first") {
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = linkedSetOf(" ", TITLE))
        }
        assertRejected("Ambiguous of one screen") {
            ScreenTraitMatch.Ambiguous(screenIds = listOf("screen_000"), unsettledScreenIds = emptyList())
        }
        assertRejected("Ambiguous of no screens") {
            ScreenTraitMatch.Ambiguous(screenIds = emptyList(), unsettledScreenIds = emptyList())
        }
    }

    @Test
    fun identities_carry_no_traits_until_one_is_settled() {
        val root = cartRoot(CART_A)
        val probe = ScreenIdentity.fromRoot(root)

        assertEquals(emptyList<Trait>(), probe.traits)
        assertEquals(
            emptyList<Trait>(),
            ScreenIdentity(packageName = PKG, rootClassName = "android.widget.FrameLayout", elements = emptySet())
                .traits,
        )
        assertEquals(
            TraitVerdict.UNSETTLED,
            TraitEvaluator.verdict(probe.withName(named("Cart")), contextOf(root)),
        )
    }

    // ---- HasList -------------------------------------------------------------------------

    @Test
    fun cart_traits_hold_across_entirely_different_rows_and_a_different_row_count() {
        val captured = cartRoot(CART_A)
        val revisited = cartRoot(CART_B)
        val cart = known("Cart", cartTraits(captured))

        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(cart, contextOf(captured)))
        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(cart, contextOf(revisited)))

        // The contrast that motivates traits: the exact element set cannot recognise the revisit.
        assertFalse(
            SameScreenPolicy(countBackAffordances = true)
                .compare(ScreenIdentity.fromRoot(captured), ScreenIdentity.fromRoot(revisited))
                .matched
        )
    }

    @Test
    fun hasList_does_not_hold_when_a_row_lacks_a_listed_child_id() {
        val root = cartRoot(listOf(Item("Descaling kit", "9.99", quantity = null)))

        assertFalse(cartList(minRows = 1).holds(contextOf(root)))
        assertTrue(
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE, PRICE))
                .holds(contextOf(root))
        )
    }

    @Test
    fun hasList_counts_only_the_rows_that_carry_the_whole_schema() {
        val root = cartRoot(
            listOf(
                Item("Espresso beans", "12.50", "2"),
                Item("Gift card", "25.00", quantity = null),
                Item("Paper filters", "3.20", "5"),
            )
        )

        assertFalse(cartList(minRows = 3).holds(contextOf(root)))
        assertTrue(cartList(minRows = 2).holds(contextOf(root)))
    }

    @Test
    fun hasList_does_not_hold_when_fewer_than_minRows_rows_exist() {
        val root = cartRoot(CART_A.take(2))

        assertFalse(cartList(minRows = 3).holds(contextOf(root)))
        assertTrue(cartList(minRows = 2).holds(contextOf(root)))
    }

    @Test
    fun hasList_does_not_hold_when_the_container_id_is_absent() {
        val root = cartRoot(CART_A, containerId = "$PKG:id/wishlist_items")

        assertFalse(cartList(minRows = 1).holds(contextOf(root)))
    }

    @Test
    fun hasList_matches_the_container_id_exactly_not_by_suffix() {
        val root = cartRoot(CART_A, containerId = "$OTHER_PKG:id/cart_items")

        assertFalse(cartList(minRows = 1).holds(contextOf(root)))
    }

    @Test
    fun hasList_matches_row_child_ids_exactly_not_by_suffix() {
        val root = rootOf(
            listNode(CART_ITEMS, CART_A, containerPath = listOf(0), top = 200, fieldPackage = OTHER_PKG),
        )

        assertFalse(cartList(minRows = 1).holds(contextOf(root)))
    }

    @Test
    fun hasList_holds_when_the_root_itself_is_the_list() {
        assertTrue(cartList(minRows = 3).holds(contextOf(rootList(CART_A))))

        val merged = mergedCapture(rootList(SIX_ITEMS.take(3)), rootList(SIX_ITEMS.drop(3)))
        assertEquals(CART_ITEMS, merged.viewIdResourceName)
        assertTrue(cartList(minRows = 6).holds(contextOf(merged)))
    }

    @Test
    fun hasList_does_not_pool_rows_across_two_containers_sharing_an_id() {
        val root = rootOf(
            listNode(CART_ITEMS, CART_A.take(1), containerPath = listOf(0), top = 200),
            listNode(CART_ITEMS, CART_A.drop(1).take(1), containerPath = listOf(1), top = 1200),
        )

        assertTrue(cartList(minRows = 1).holds(contextOf(root)))
        assertFalse(cartList(minRows = 2).holds(contextOf(root)))
    }

    @Test
    fun hasList_holds_when_one_container_qualifies_even_if_an_earlier_one_does_not() {
        val root = rootOf(
            listNode(
                CART_ITEMS,
                listOf(Item("Gift card", "25.00", quantity = null)),
                containerPath = listOf(0),
                top = 200,
            ),
            listNode(CART_ITEMS, CART_A.take(2), containerPath = listOf(1), top = 1200),
        )

        assertTrue(cartList(minRows = 2).holds(contextOf(root)))
    }

    @Test
    fun hasList_holds_when_the_first_container_qualifies_even_if_a_later_one_does_not() {
        val root = rootOf(
            listNode(CART_ITEMS, CART_A.take(2), containerPath = listOf(0), top = 200),
            listNode(
                CART_ITEMS,
                listOf(Item("Gift card", "25.00", quantity = null)),
                containerPath = listOf(1),
                top = 1200,
            ),
        )

        assertTrue(cartList(minRows = 2).holds(contextOf(root)))
    }

    @Test
    fun hasList_reads_ids_at_any_depth_beneath_a_row_but_not_the_rows_own_id() {
        val wrapped = rootOf(
            node(
                className = RECYCLER,
                resourceId = CART_ITEMS,
                scrollable = true,
                bounds = "[0,200][1080,2000]",
                childIndexPath = listOf(0),
                children = listOf(
                    node(
                        className = LINEAR,
                        resourceId = ROW_ID,
                        clickable = true,
                        bounds = "[0,200][1080,350]",
                        childIndexPath = listOf(0, 0),
                        children = listOf(
                            node(
                                className = LINEAR,
                                resourceId = null,
                                bounds = "[0,200][1080,350]",
                                childIndexPath = listOf(0, 0, 0),
                                children = listOf(
                                    text(TITLE, "Espresso beans", listOf(0, 0, 0, 0)),
                                    text(PRICE, "12.50", listOf(0, 0, 0, 1)),
                                    text(QUANTITY, "2", listOf(0, 0, 0, 2)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(cartList(minRows = 1).holds(contextOf(wrapped)))
        assertFalse(
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(ROW_ID))
                .holds(contextOf(wrapped))
        )
    }

    @Test
    fun hasList_counts_ids_on_non_leaf_nodes_beneath_a_row() {
        val root = rootOf(
            node(
                className = RECYCLER,
                resourceId = CART_ITEMS,
                scrollable = true,
                bounds = "[0,200][1080,2000]",
                childIndexPath = listOf(0),
                children = listOf(
                    node(
                        className = LINEAR,
                        resourceId = ROW_ID,
                        clickable = true,
                        bounds = "[0,200][1080,350]",
                        childIndexPath = listOf(0, 0),
                        children = listOf(
                            text(TITLE, "Espresso beans", listOf(0, 0, 0)),
                            node(
                                className = LINEAR,
                                resourceId = PRICE_BLOCK,
                                bounds = "[540,200][1080,350]",
                                childIndexPath = listOf(0, 0, 1),
                                children = listOf(text(PRICE, "12.50", listOf(0, 0, 1, 0))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(PRICE_BLOCK))
                .holds(contextOf(root))
        )
        assertTrue(
            HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE, PRICE_BLOCK, PRICE))
                .holds(contextOf(root))
        )
    }

    @Test
    fun hasList_ignores_visibility_and_enabled_state() {
        val hiddenRows = rootOf(
            listNode(CART_ITEMS, CART_A, containerPath = listOf(0), top = 200, rowsVisible = false),
        )
        val disabledRows = rootOf(
            listNode(CART_ITEMS, CART_A, containerPath = listOf(0), top = 200, rowsEnabled = false),
        )

        assertTrue(cartList(minRows = 3).holds(contextOf(hiddenRows)))
        assertTrue(cartList(minRows = 3).holds(contextOf(disabledRows)))
    }

    @Test
    fun hasList_keeps_its_own_copy_of_the_row_schema() {
        val schema = mutableSetOf(TITLE)
        val trait = HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = schema)
        schema.clear()

        assertEquals(setOf(TITLE), trait.rowChildResourceIds)
        assertFalse(trait.holds(contextOf(rootWithIdlessRow())))
        val sameValue = HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE))
        assertEquals(sameValue, trait)
        assertEquals(sameValue.hashCode(), trait.hashCode())
    }

    @Test
    fun hasList_schema_cannot_be_emptied_through_its_getter() {
        val trait = HasList(CART_ITEMS, minRows = 1, rowChildResourceIds = setOf(TITLE, PRICE))
        val hashBefore = trait.hashCode()

        try {
            @Suppress("UNCHECKED_CAST")
            (trait.rowChildResourceIds as MutableSet<String>).clear()
            fail("The stored schema must not be mutable through its getter.")
        } catch (expected: UnsupportedOperationException) {
            // Refused, as required.
        }

        assertEquals(setOf(TITLE, PRICE), trait.rowChildResourceIds)
        assertEquals(hashBefore, trait.hashCode())
        assertFalse(trait.holds(contextOf(rootWithIdlessRow())))
    }

    @Test
    fun hasList_is_a_value_equal_only_when_all_three_fields_are() {
        val base = HasList(CART_ITEMS, minRows = 2, rowChildResourceIds = ROW_SCHEMA)
        val otherContainer = HasList("$PKG:id/wishlist_items", minRows = 2, rowChildResourceIds = ROW_SCHEMA)
        val moreRows = HasList(CART_ITEMS, minRows = 3, rowChildResourceIds = ROW_SCHEMA)
        val smallerSchema = HasList(CART_ITEMS, minRows = 2, rowChildResourceIds = setOf(TITLE, PRICE))

        assertEquals(base, base)
        assertEquals(
            base,
            HasList(CART_ITEMS, minRows = 2, rowChildResourceIds = sortedSetOf(QUANTITY, TITLE, PRICE)),
        )
        assertEquals(
            base.hashCode(),
            HasList(CART_ITEMS, minRows = 2, rowChildResourceIds = sortedSetOf(QUANTITY, TITLE, PRICE))
                .hashCode(),
        )
        // Unequal in both directions: a one-way comparison (containment, `>=`) must not pass.
        for (other in listOf(otherContainer, moreRows, smallerSchema)) {
            assertNotEquals(base, other)
            assertNotEquals(other, base)
        }
        val baseAsTrait: Trait = base
        val otherKind: Trait = HasControl(controlIn(cartRoot(CART_A), "checkout"))
        assertNotEquals(baseAsTrait, otherKind)
        assertNotEquals(known("Cart", listOf(base)), known("Cart", listOf(otherContainer)))
    }

    @Test
    fun hasList_holds_on_a_merged_capture_when_the_list_is_the_scrolled_node() {
        val firstViewport = cartRoot(SIX_ITEMS.take(3))
        val secondViewport = cartRoot(SIX_ITEMS.drop(3))
        val merged = mergedCapture(firstViewport, secondViewport)

        assertEquals(1, countNodesWithId(merged, CART_ITEMS))
        assertTrue(cartList(minRows = 6).holds(contextOf(merged)))
        assertFalse(cartList(minRows = 6).holds(contextOf(firstViewport)))
        assertFalse(cartList(minRows = 6).holds(contextOf(secondViewport)))
    }

    /**
     * Characterisation, not a rule of this class: a list that is itself the scrolled node, captured
     * over two viewports with one overlapping row and one genuinely repeated row. The merge collapses
     * both, so the merged capture carries 3 rows where the screen showed 4, and `minRows` is checked
     * against those 3.
     */
    @Test
    fun hasList_on_a_merged_scrolling_list_is_checked_against_the_merged_rows() {
        val espresso = Item("Espresso beans", "12.50", "2")
        val filters = Item("Paper filters", "3.20", "5")
        val tamper = Item("Tamper", "19.00", "1")
        val merged = mergedCapture(
            cartRoot(listOf(espresso, espresso, filters)),
            cartRoot(listOf(filters, tamper)),
        )
        val tallTree = cartRoot(listOf(espresso, espresso, filters, tamper))

        assertTrue(cartList(minRows = 4).holds(contextOf(tallTree)))
        assertTrue(cartList(minRows = 3).holds(contextOf(merged)))
        assertFalse(cartList(minRows = 4).holds(contextOf(merged)))
    }

    /**
     * Characterisation, not a rule of this class: a list inside a scrolled wrapper whose two
     * viewports look different. The merge keeps one copy of the container per viewport, each with its
     * rows as captured — a repeated row included — and rows are not pooled across the copies.
     */
    @Test
    fun hasList_on_a_wrapped_list_whose_viewports_differ_sees_each_copy_as_captured() {
        val espresso = Item("Espresso beans", "12.50", "2")
        val filters = Item("Paper filters", "3.20", "5")
        val tamper = Item("Tamper", "19.00", "1")
        val merged = mergedCapture(
            rootOf(scrollWrapperAround(listOf(espresso, espresso, filters))),
            rootOf(scrollWrapperAround(listOf(filters, tamper))),
        )

        assertEquals(2, countNodesWithId(merged, CART_ITEMS))
        assertTrue(cartList(minRows = 3).holds(contextOf(merged)))
        assertFalse(cartList(minRows = 4).holds(contextOf(merged)))
    }

    /**
     * Characterisation, not a rule of this class: a short list inside a scrolled wrapper that stays
     * fully visible while the page scrolls, so both viewports capture it identically. The merge keeps
     * one copy, and the repeated row within it collapses too: 2 rows where each viewport showed 3.
     */
    @Test
    fun hasList_on_a_wrapped_list_visible_in_both_viewports_sees_one_collapsed_copy() {
        val espresso = Item("Espresso beans", "12.50", "2")
        val filters = Item("Paper filters", "3.20", "5")
        val viewport = rootOf(scrollWrapperAround(listOf(espresso, espresso, filters)))
        val merged = mergedCapture(viewport, viewport)

        assertTrue(cartList(minRows = 3).holds(contextOf(viewport)))
        assertEquals(1, countNodesWithId(merged, CART_ITEMS))
        assertTrue(cartList(minRows = 2).holds(contextOf(merged)))
        assertFalse(cartList(minRows = 3).holds(contextOf(merged)))
    }

    /**
     * Characterisation, not a rule of this class: a list inside a scrolled wrapper, captured over two
     * viewports whose rows all differ. The merge keeps one copy of the container per viewport, and
     * because rows are not pooled, `minRows` is met per copy rather than across the whole capture.
     */
    @Test
    fun hasList_on_a_list_nested_in_a_scrolled_wrapper_sees_one_container_per_viewport() {
        val firstViewport = rootOf(scrollWrapperAround(SIX_ITEMS.take(3)))
        val secondViewport = rootOf(scrollWrapperAround(SIX_ITEMS.drop(3)))
        val merged = mergedCapture(firstViewport, secondViewport)
        val singleTreeOfAllSix = rootOf(scrollWrapperAround(SIX_ITEMS))

        assertEquals(2, countNodesWithId(merged, CART_ITEMS))
        assertTrue(cartList(minRows = 6).holds(contextOf(singleTreeOfAllSix)))
        assertTrue(cartList(minRows = 3).holds(contextOf(merged)))
        assertFalse(cartList(minRows = 6).holds(contextOf(merged)))
    }

    // ---- HasControl / LacksControl -------------------------------------------------------

    @Test
    fun hasControl_ignores_the_back_flag_and_geometry() {
        val inTopBand = rootOf(button("Backup", top = 80, index = 0))
        val lowerDown = rootOf(button("Backup", top = 1500, index = 0))
        val flagged = controlIn(inTopBand, "backup")
        val unflagged = controlIn(lowerDown, "backup")
        assertTrue(flagged.isBackAffordance)
        assertFalse(unflagged.isBackAffordance)

        assertTrue(HasControl(flagged).holds(contextOf(lowerDown)))
        assertTrue(HasControl(unflagged).holds(contextOf(inTopBand)))
        assertFalse(LacksControl(flagged).holds(contextOf(lowerDown)))
    }

    @Test
    fun hasControl_compares_the_whole_fingerprint_not_just_the_label() {
        val checkout = controlIn(cartRoot(CART_A), "checkout")
        val lookalikeWithOtherId = rootOf(
            node(
                className = "android.widget.Button",
                resourceId = "$PKG:id/checkout_link",
                text = "Checkout",
                clickable = true,
                bounds = "[0,2100][1080,2180]",
                childIndexPath = listOf(0),
            ),
        )
        val lookalikeWithOtherClass = rootOf(
            node(
                className = "android.widget.TextView",
                resourceId = "$PKG:id/checkout",
                text = "Checkout",
                clickable = true,
                bounds = "[0,2100][1080,2180]",
                childIndexPath = listOf(0),
            ),
        )

        assertFalse(HasControl(checkout).holds(contextOf(lookalikeWithOtherId)))
        assertFalse(HasControl(checkout).holds(contextOf(lookalikeWithOtherClass)))
        assertTrue(LacksControl(checkout).holds(contextOf(lookalikeWithOtherId)))
        assertTrue(LacksControl(checkout).holds(contextOf(lookalikeWithOtherClass)))
    }

    @Test
    fun hasControl_and_lacksControl_compare_the_type_flags_of_the_fingerprint_too() {
        val checkout = controlIn(cartRoot(CART_A), "checkout")
        val variants = listOf(
            checkoutOnlyRoot(checkable = true),
            checkoutOnlyRoot(editable = true),
            checkoutOnlyRoot(insideList = true),
        )

        assertTrue(HasControl(checkout).holds(contextOf(checkoutOnlyRoot())))
        assertFalse(LacksControl(checkout).holds(contextOf(checkoutOnlyRoot())))
        for (variant in variants) {
            assertFalse(HasControl(checkout).holds(contextOf(variant)))
            assertTrue(LacksControl(checkout).holds(contextOf(variant)))
        }
    }

    @Test
    fun a_control_without_a_resource_id_is_matched_exactly_not_as_a_wildcard() {
        val idless = rootOf(iconButton(resourceId = null))
        val withId = rootOf(iconButton(resourceId = "$PKG:id/share"))
        val share = controlIn(idless, "share")
        assertEquals(null, share.fingerprint.resourceId)

        assertTrue(HasControl(share).holds(contextOf(idless)))
        assertFalse(LacksControl(share).holds(contextOf(idless)))
        assertFalse(HasControl(share).holds(contextOf(withId)))
        assertTrue(LacksControl(share).holds(contextOf(withId)))
    }

    @Test
    fun hasControl_sees_the_controls_inside_list_rows() {
        val root = cartRoot(CART_A)
        val row = controlIn(root, "espresso beans")
        assertTrue(row.fingerprint.isListItem)

        assertTrue(HasControl(row).holds(contextOf(root)))
        assertFalse(LacksControl(row).holds(contextOf(root)))
    }

    @Test
    fun lacksControl_negates_inside_the_conjunction() {
        val withPromo = cartRoot(CART_A, extraButtons = listOf("Apply promo"))
        val withoutPromo = cartRoot(CART_B)
        val cart = known(
            "Cart",
            cartTraits(withPromo) + LacksControl(controlIn(withPromo, "apply promo")),
        )

        assertEquals(TraitVerdict.DOES_NOT_HOLD, TraitEvaluator.verdict(cart, contextOf(withPromo)))
        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(cart, contextOf(withoutPromo)))
    }

    @Test
    fun a_hasControl_in_the_conjunction_must_hold() {
        val withPromo = cartRoot(CART_A, extraButtons = listOf("Apply promo"))
        val withoutPromo = cartRoot(CART_A)
        val cart = known(
            "Cart",
            listOf(cartList(minRows = 1), HasControl(controlIn(withPromo, "apply promo"))),
        )

        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(cart, contextOf(withPromo)))
        assertEquals(TraitVerdict.DOES_NOT_HOLD, TraitEvaluator.verdict(cart, contextOf(withoutPromo)))
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(withoutPromo), mapOf("screen_000" to cart)),
        )
    }

    @Test
    fun the_conjunction_checks_every_trait_not_one_per_kind() {
        val withPromo = cartRoot(CART_A, extraButtons = listOf("Apply promo"))
        val withoutPromo = cartRoot(CART_A)
        val twoControls = known(
            "Cart",
            listOf(
                HasControl(controlIn(withPromo, "checkout")),
                HasControl(controlIn(withPromo, "apply promo")),
            ),
        )
        val twoLists = known(
            "Cart",
            listOf(
                cartList(minRows = 1),
                HasList("$PKG:id/wishlist_items", minRows = 1, rowChildResourceIds = ROW_SCHEMA),
            ),
        )

        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(twoControls, contextOf(withPromo)))
        assertEquals(TraitVerdict.DOES_NOT_HOLD, TraitEvaluator.verdict(twoControls, contextOf(withoutPromo)))
        assertEquals(TraitVerdict.DOES_NOT_HOLD, TraitEvaluator.verdict(twoLists, contextOf(withoutPromo)))
    }

    // ---- the unsettled guard -------------------------------------------------------------

    @Test
    fun an_identity_with_no_traits_is_unsettled_and_never_a_candidate() {
        val root = cartRoot(CART_A)
        val unsettledCart = known("Cart", traits = emptyList())

        assertEquals(TraitVerdict.UNSETTLED, TraitEvaluator.verdict(unsettledCart, contextOf(root)))
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = listOf("screen_000")),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart")),
                mapOf("screen_000" to unsettledCart),
            ),
        )
        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_001", unsettledScreenIds = listOf("screen_000")),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart")),
                mapOf(
                    "screen_000" to unsettledCart,
                    "screen_001" to known("Cart", cartTraits(root)),
                ),
            ),
        )
    }

    @Test
    fun an_identity_of_negations_alone_is_unsettled_not_a_match_for_every_bare_screen() {
        val promo = controlIn(cartRoot(CART_A, extraButtons = listOf("Apply promo")), "apply promo")
        val onlyNegations = known("Cart", listOf(LacksControl(promo)))
        val bare = rootOf()

        assertEquals(TraitVerdict.UNSETTLED, TraitEvaluator.verdict(onlyNegations, contextOf(bare)))
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = listOf("screen_000")),
            TraitEvaluator.matchKnownScreens(contextOf(bare), mapOf("screen_000" to onlyNegations)),
        )
    }

    @Test
    fun an_identity_of_controls_alone_is_settled_and_can_match() {
        val root = cartRoot(CART_A)
        val controlsOnly = known("Cart", listOf(HasControl(controlIn(root, "checkout"))))

        assertEquals(TraitVerdict.HOLDS, TraitEvaluator.verdict(controlsOnly, contextOf(root)))
        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(root), mapOf("screen_000" to controlsOnly)),
        )
    }

    @Test
    fun every_unsettled_screen_is_listed_whatever_the_result_and_whatever_its_package() {
        val root = cartRoot(CART_A)
        val unsettled = mapOf(
            "screen_003" to known("Drafts", emptyList()),
            "screen_004" to known("Saved", emptyList(), packageName = OTHER_PKG),
        )
        val wishlist = known(
            "Wishlist",
            listOf(HasList("$PKG:id/wishlist_items", minRows = 1, rowChildResourceIds = ROW_SCHEMA)),
        )

        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = listOf("screen_003", "screen_004")),
            TraitEvaluator.matchKnownScreens(contextOf(root), unsettled + ("screen_001" to wishlist)),
        )
        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = listOf("screen_003", "screen_004")),
            TraitEvaluator.matchKnownScreens(
                contextOf(root),
                unsettled + ("screen_000" to known("Cart", cartTraits(root))),
            ),
        )
    }

    // ---- zero / one / more than one ------------------------------------------------------

    @Test
    fun no_known_screen_holding_is_none() {
        val cart = known("Cart", cartTraits(cartRoot(CART_A)))
        val wishlistScreen = cartRoot(CART_A, containerId = "$PKG:id/wishlist_items")

        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(wishlistScreen), mapOf("screen_000" to cart)),
        )
    }

    @Test
    fun exactly_one_known_screen_holding_is_one() {
        val root = cartRoot(CART_B)
        val knownScreens = mapOf(
            "screen_000" to known("Cart", cartTraits(cartRoot(CART_A))),
            "screen_001" to known(
                "Wishlist",
                listOf(HasList("$PKG:id/wishlist_items", minRows = 1, rowChildResourceIds = ROW_SCHEMA)),
            ),
        )

        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(root), knownScreens),
        )
    }

    @Test
    fun more_than_one_known_screen_holding_is_ambiguous_and_lists_every_one() {
        val root = cartRoot(CART_A)
        val traits = cartTraits(root)
        val knownScreens = mapOf(
            "screen_000" to known("Cart", traits),
            "screen_001" to known("Basket", traits),
            "screen_002" to known("Bag", traits),
        )

        assertEquals(
            ScreenTraitMatch.Ambiguous(
                screenIds = listOf("screen_000", "screen_001", "screen_002"),
                unsettledScreenIds = emptyList(),
            ),
            TraitEvaluator.matchKnownScreens(contextOf(root), knownScreens),
        )
    }

    @Test
    fun two_known_screens_with_equal_identities_are_both_listed() {
        val root = cartRoot(CART_A)
        val identity = known("Cart", cartTraits(root))

        assertEquals(
            ScreenTraitMatch.Ambiguous(
                screenIds = listOf("screen_000", "screen_001"),
                unsettledScreenIds = emptyList(),
            ),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart")),
                linkedMapOf("screen_001" to identity, "screen_000" to identity.copy()),
            ),
        )
    }

    @Test
    fun uniqueness_is_over_the_whole_identity_name_and_package_included() {
        val root = cartRoot(CART_A)
        val traits = cartTraits(root)
        val knownScreens = mapOf(
            "screen_000" to known("Cart", traits),
            "screen_001" to known("Basket", traits),
            "screen_002" to known("Cart", traits, packageName = OTHER_PKG),
        )

        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(root, named("Cart")), knownScreens),
        )
        assertEquals(
            ScreenTraitMatch.Ambiguous(
                screenIds = listOf("screen_000", "screen_001"),
                unsettledScreenIds = emptyList(),
            ),
            TraitEvaluator.matchKnownScreens(contextOf(root), knownScreens),
        )
    }

    @Test
    fun a_package_that_merely_shares_a_prefix_is_another_app() {
        val traits = cartTraits(cartRoot(CART_A))
        val debugBuildRoot = cartRoot(CART_A).copy(packageName = "$PKG.debug")

        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(contextOf(debugBuildRoot), mapOf("screen_000" to known("Cart", traits))),
        )
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(cartRoot(CART_A)),
                mapOf("screen_000" to known("Cart", traits, packageName = "$PKG.debug")),
            ),
        )
    }

    @Test
    fun title_disambiguators_take_part_in_the_name() {
        val root = cartRoot(CART_A)
        val traits = cartTraits(root)
        val knownScreens = mapOf(
            "screen_000" to known("Cart", traits, titleDisambiguators = listOf("Alpha")),
            "screen_001" to known("Cart", traits, titleDisambiguators = listOf("Beta")),
        )

        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart", titleDisambiguators = listOf("Alpha"))),
                knownScreens,
            ),
        )
    }

    @Test
    fun a_weak_name_still_equals_itself_so_weak_screens_are_told_apart_by_name() {
        val root = cartRoot(CART_A)
        val traits = cartTraits(root)
        val knownScreens = mapOf(
            "screen_000" to known("Cart", traits, confidence = ScreenDedupConfidence.WEAK),
            "screen_001" to known("Basket", traits, confidence = ScreenDedupConfidence.WEAK),
        )

        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart", confidence = ScreenDedupConfidence.WEAK)),
                knownScreens,
            ),
        )
    }

    @Test
    fun the_name_takes_part_only_when_both_sides_have_one() {
        val root = cartRoot(CART_A)
        val unnamedKnown = known("Cart", cartTraits(root)).copy(name = null)

        assertEquals(
            ScreenTraitMatch.One(screenId = "screen_000", unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(root, named("Cart")),
                mapOf("screen_000" to unnamedKnown),
            ),
        )
    }

    @Test
    fun a_missing_package_on_either_side_matches_nothing() {
        val shopRoot = cartRoot(CART_A)
        val packagelessRoot = shopRoot.copy(packageName = null)
        val traits = cartTraits(shopRoot)
        val packagelessKnown = known("Cart", traits).copy(packageName = null)

        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(packagelessRoot),
                mapOf("screen_000" to packagelessKnown),
            ),
        )
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(packagelessRoot),
                mapOf("screen_000" to known("Cart", traits)),
            ),
        )
        assertEquals(
            ScreenTraitMatch.None(unsettledScreenIds = emptyList()),
            TraitEvaluator.matchKnownScreens(
                contextOf(shopRoot),
                mapOf("screen_000" to packagelessKnown),
            ),
        )
    }

    @Test
    fun the_result_does_not_depend_on_the_order_known_screens_are_presented_in() {
        val root = cartRoot(CART_A)
        val traits = cartTraits(root)
        val screens = listOf(
            "screen_002" to known("Bag", traits),
            "screen_004" to known("Drafts", emptyList()),
            "screen_000" to known("Cart", traits),
            "screen_003" to known("Saved", emptyList()),
            "screen_001" to known("Basket", traits),
        )

        val forward = TraitEvaluator.matchKnownScreens(contextOf(root), linkedMapOf(*screens.toTypedArray()))
        val reversed = TraitEvaluator.matchKnownScreens(
            contextOf(root),
            linkedMapOf(*screens.reversed().toTypedArray()),
        )

        assertEquals(forward, reversed)
        assertEquals(
            ScreenTraitMatch.Ambiguous(
                screenIds = listOf("screen_000", "screen_001", "screen_002"),
                unsettledScreenIds = listOf("screen_003", "screen_004"),
            ),
            forward,
        )
    }

    // ---- identifying elements ------------------------------------------------------------

    @Test
    fun identifying_elements_are_derived_from_hasControl_traits_only() {
        val root = cartRoot(CART_A, extraButtons = listOf("Apply promo", "Continue shopping"))
        val checkout = controlIn(root, "checkout")
        val keepShopping = controlIn(root, "continue shopping")
        val promo = controlIn(root, "apply promo")
        val identity = known(
            "Cart",
            listOf(
                cartList(minRows = 1),
                HasControl(checkout),
                LacksControl(promo),
                HasControl(keepShopping),
            ),
        )

        assertEquals(setOf(checkout, keepShopping), identity.identifyingElements)
        assertEquals(emptySet<ScreenElementIdentity>(), known("Cart", emptyList()).identifyingElements)
    }

    @Test
    fun identifying_elements_are_the_asserted_elements_back_flag_included() {
        val backup = controlIn(rootOf(button("Backup", top = 80, index = 0)), "backup")
        assertTrue(backup.isBackAffordance)

        val identifying = known("Settings", listOf(HasControl(backup))).identifyingElements

        assertEquals(setOf(backup), identifying)
        assertTrue(identifying.single().isBackAffordance)
    }

    // ---- fixtures ------------------------------------------------------------------------

    private data class Item(val title: String, val price: String, val quantity: String?)

    private fun cartList(minRows: Int): HasList =
        HasList(CART_ITEMS, minRows = minRows, rowChildResourceIds = ROW_SCHEMA)

    private fun cartTraits(capturedFrom: AccessibilityNodeSnapshot): List<Trait> = listOf(
        cartList(minRows = 1),
        HasControl(controlIn(capturedFrom, "checkout")),
    )

    private fun known(
        screenName: String,
        traits: List<Trait>,
        packageName: String = PKG,
        titleDisambiguators: List<String> = emptyList(),
        confidence: ScreenDedupConfidence = ScreenDedupConfidence.STRONG,
    ): ScreenIdentity =
        testIdentity(
            screenName,
            packageName = packageName,
            titleDisambiguators = titleDisambiguators,
            confidence = confidence,
        ).copy(traits = traits)

    private fun named(
        screenName: String,
        titleDisambiguators: List<String> = emptyList(),
        confidence: ScreenDedupConfidence = ScreenDedupConfidence.STRONG,
    ): ScreenNameIdentity =
        requireNotNull(
            testIdentity(
                screenName,
                packageName = PKG,
                titleDisambiguators = titleDisambiguators,
                confidence = confidence,
            ).name
        )

    private fun contextOf(
        root: AccessibilityNodeSnapshot,
        name: ScreenNameIdentity? = null,
    ): TraitEvaluationContext = TraitEvaluationContext.of(root, name)

    private fun controlIn(root: AccessibilityNodeSnapshot, label: String): ScreenElementIdentity =
        ScreenIdentity.fromRoot(root).elements.single { it.fingerprint.label == label }

    private fun countNodesWithId(root: AccessibilityNodeSnapshot, resourceId: String): Int =
        (if (root.viewIdResourceName == resourceId) 1 else 0) +
            root.children.sumOf { child -> countNodesWithId(child, resourceId) }

    private fun mergedCapture(
        firstViewport: AccessibilityNodeSnapshot,
        secondViewport: AccessibilityNodeSnapshot,
    ): AccessibilityNodeSnapshot = requireNotNull(
        SyntheticAccessibilityTreeBuilder.build(
            ScreenSnapshot(
                screenName = "Cart",
                packageName = PKG,
                elements = emptyList(),
                xmlDump = "",
                stepSnapshots = listOf(
                    ScrollCaptureStep(stepIndex = 0, root = firstViewport, newElementCount = 3),
                    ScrollCaptureStep(stepIndex = 1, root = secondViewport, newElementCount = 3),
                ),
                scrollStepCount = 2,
            )
        )
    )

    private fun cartRoot(
        items: List<Item>,
        containerId: String = CART_ITEMS,
        extraButtons: List<String> = emptyList(),
    ): AccessibilityNodeSnapshot {
        val buttons = (listOf("Checkout") + extraButtons).mapIndexed { offset, label ->
            button(label, top = 2100 + offset * 100, index = offset + 1)
        }
        return rootOf(
            listNode(containerId, items, containerPath = listOf(0), top = 200),
            *buttons.toTypedArray(),
        )
    }

    /** A screen whose root node is itself the scrolling cart list. */
    private fun rootList(items: List<Item>): AccessibilityNodeSnapshot =
        listNode(CART_ITEMS, items, containerPath = emptyList(), top = 0)

    /**
     * A screen holding only a Checkout button identical to [cartRoot]'s except for the given type
     * flags, or placed inside a list container (which makes it a list item).
     */
    private fun checkoutOnlyRoot(
        checkable: Boolean = false,
        editable: Boolean = false,
        insideList: Boolean = false,
    ): AccessibilityNodeSnapshot {
        val checkout = node(
            className = "android.widget.Button",
            resourceId = "$PKG:id/checkout",
            text = "Checkout",
            clickable = true,
            checkable = checkable,
            editable = editable,
            bounds = "[0,2100][1080,2180]",
            childIndexPath = if (insideList) listOf(0, 0) else listOf(0),
        )
        return if (insideList) {
            rootOf(
                node(
                    className = RECYCLER,
                    resourceId = "$PKG:id/actions",
                    scrollable = true,
                    bounds = "[0,2000][1080,2400]",
                    childIndexPath = listOf(0),
                    children = listOf(checkout),
                ),
            )
        } else {
            rootOf(checkout)
        }
    }

    /** An icon button labelled "Share", with or without a resource id. */
    private fun iconButton(resourceId: String?): AccessibilityNodeSnapshot = node(
        className = "android.widget.ImageButton",
        resourceId = resourceId,
        text = "Share",
        clickable = true,
        bounds = "[900,1200][1080,1320]",
        childIndexPath = listOf(0),
    )

    private fun rootOf(vararg children: AccessibilityNodeSnapshot): AccessibilityNodeSnapshot = node(
        className = "android.widget.FrameLayout",
        resourceId = "$PKG:id/root",
        bounds = "[0,0][1080,2400]",
        children = children.toList(),
    )

    /** A list container whose rows have not got a single resource id beneath them. */
    private fun rootWithIdlessRow(): AccessibilityNodeSnapshot = rootOf(
        node(
            className = RECYCLER,
            resourceId = CART_ITEMS,
            scrollable = true,
            bounds = "[0,200][1080,2000]",
            childIndexPath = listOf(0),
            children = listOf(
                node(
                    className = LINEAR,
                    resourceId = null,
                    clickable = true,
                    bounds = "[0,200][1080,350]",
                    childIndexPath = listOf(0, 0),
                    children = listOf(
                        node(
                            className = "android.widget.TextView",
                            resourceId = null,
                            text = "Gift card",
                            bounds = "[0,200][540,250]",
                            childIndexPath = listOf(0, 0, 0),
                        ),
                    ),
                ),
            ),
        ),
    )

    /** A scrolled wrapper around a non-scrolling cart list — the list is not the scroll node. */
    private fun scrollWrapperAround(items: List<Item>): AccessibilityNodeSnapshot = node(
        className = "androidx.core.widget.NestedScrollView",
        resourceId = "$PKG:id/scroll",
        scrollable = true,
        bounds = "[0,200][1080,2000]",
        childIndexPath = listOf(0),
        children = listOf(
            listNode(
                CART_ITEMS,
                items,
                containerPath = listOf(0, 0),
                top = 200,
                className = LINEAR,
                scrollable = false,
            ),
        ),
    )

    /**
     * A list container at [containerPath]; [fieldPackage] is the package prefix of each row's
     * field ids, and [rowsVisible] / [rowsEnabled] apply to every row and its fields.
     */
    private fun listNode(
        containerId: String,
        items: List<Item>,
        containerPath: List<Int>,
        top: Int,
        fieldPackage: String = PKG,
        className: String = RECYCLER,
        scrollable: Boolean = true,
        rowsVisible: Boolean = true,
        rowsEnabled: Boolean = true,
    ): AccessibilityNodeSnapshot = node(
        className = className,
        resourceId = containerId,
        scrollable = scrollable,
        bounds = "[0,$top][1080,${top + 1800}]",
        childIndexPath = containerPath,
        children = items.mapIndexed { rowIndex, item ->
            val rowTop = top + rowIndex * ROW_HEIGHT
            val rowPath = containerPath + rowIndex
            node(
                className = LINEAR,
                resourceId = ROW_ID,
                clickable = true,
                visible = rowsVisible,
                enabled = rowsEnabled,
                bounds = "[0,$rowTop][1080,${rowTop + ROW_HEIGHT}]",
                childIndexPath = rowPath,
                children = listOfNotNull(
                    text("$fieldPackage:id/title", item.title, rowPath + 0, rowsVisible, rowsEnabled),
                    text("$fieldPackage:id/price", item.price, rowPath + 1, rowsVisible, rowsEnabled),
                    item.quantity?.let { quantity ->
                        text("$fieldPackage:id/quantity", quantity, rowPath + 2, rowsVisible, rowsEnabled)
                    },
                ),
            )
        },
    )

    private fun text(
        resourceId: String,
        value: String,
        path: List<Int>,
        visible: Boolean = true,
        enabled: Boolean = true,
    ): AccessibilityNodeSnapshot = node(
        className = "android.widget.TextView",
        resourceId = resourceId,
        text = value,
        visible = visible,
        enabled = enabled,
        bounds = "[0,0][540,50]",
        childIndexPath = path,
    )

    private fun button(label: String, top: Int, index: Int): AccessibilityNodeSnapshot = node(
        className = "android.widget.Button",
        resourceId = "$PKG:id/${label.lowercase().replace(' ', '_')}",
        text = label,
        clickable = true,
        bounds = "[0,$top][1080,${top + 80}]",
        childIndexPath = listOf(index),
    )

    private fun node(
        className: String,
        resourceId: String?,
        bounds: String,
        text: String? = null,
        clickable: Boolean = false,
        scrollable: Boolean = false,
        checkable: Boolean = false,
        editable: Boolean = false,
        visible: Boolean = true,
        enabled: Boolean = true,
        childIndexPath: List<Int> = emptyList(),
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ): AccessibilityNodeSnapshot = AccessibilityNodeSnapshot(
        className = className,
        packageName = PKG,
        viewIdResourceName = resourceId,
        text = text,
        contentDescription = null,
        clickable = clickable,
        supportsClickAction = clickable,
        scrollable = scrollable,
        checkable = checkable,
        editable = editable,
        enabled = enabled,
        visibleToUser = visible,
        bounds = bounds,
        children = children,
        childIndexPath = childIndexPath,
    )

    private fun assertRejected(what: String, construct: () -> Any) {
        try {
            construct()
            fail("Expected $what to be refused at construction.")
        } catch (expected: IllegalArgumentException) {
            // Refused, as required.
        }
    }

    private companion object {
        const val PKG = "com.example.shop"
        const val OTHER_PKG = "com.example.other"
        const val CART_ITEMS = "$PKG:id/cart_items"
        const val ROW_ID = "$PKG:id/cart_item"
        const val TITLE = "$PKG:id/title"
        const val PRICE = "$PKG:id/price"
        const val PRICE_BLOCK = "$PKG:id/price_block"
        const val QUANTITY = "$PKG:id/quantity"
        const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        const val LINEAR = "android.widget.LinearLayout"
        const val ROW_HEIGHT = 150
        val ROW_SCHEMA = setOf(TITLE, PRICE, QUANTITY)

        val CART_A = listOf(
            Item("Espresso beans", "12.50", "2"),
            Item("Milk frother", "34.00", "1"),
            Item("Paper filters", "3.20", "5"),
        )
        val CART_B = listOf(Item("Descaling kit", "9.99", "1"))
        val SIX_ITEMS = listOf(
            Item("Espresso beans", "12.50", "2"),
            Item("Milk frother", "34.00", "1"),
            Item("Paper filters", "3.20", "5"),
            Item("Descaling kit", "9.99", "1"),
            Item("Tamper", "19.00", "1"),
            Item("Knock box", "15.75", "1"),
        )
    }
}
