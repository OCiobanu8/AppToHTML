package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins for `a2h-c2b.10`, folded into `a2h-c2b.3`: a screen identity's traits are an order-free
 * conjunction, so two identities asserting the same things are equal however the list is ordered or
 * repeated.
 */
class ScreenIdentityTraitEqualityTest {

    @Test
    fun reordered_traits_are_equal_and_hash_equal() {
        val forward = identity(listOf(LIST_TRAIT, CONTROL_TRAIT))
        val backward = identity(listOf(CONTROL_TRAIT, LIST_TRAIT))

        assertEquals(forward, backward)
        assertEquals(forward.hashCode(), backward.hashCode())
    }

    @Test
    fun duplicated_traits_are_equal_and_hash_equal() {
        val once = identity(listOf(LIST_TRAIT))
        val twice = identity(listOf(LIST_TRAIT, LIST_TRAIT))

        assertEquals(once, twice)
        assertEquals(once.hashCode(), twice.hashCode())
    }

    @Test
    fun different_traits_are_still_different() {
        assertNotEquals(identity(listOf(LIST_TRAIT)), identity(listOf(CONTROL_TRAIT)))
        assertNotEquals(identity(listOf(LIST_TRAIT)), identity(emptyList()))
    }

    @Test
    fun every_other_field_still_takes_part() {
        val base = identity(listOf(LIST_TRAIT))

        assertNotEquals(base, base.copy(packageName = "com.example.other"))
        assertNotEquals(base, base.copy(rootClassName = "android.widget.LinearLayout"))
        assertNotEquals(base, base.copy(elements = emptySet()))
        assertNotEquals(base, base.copy(name = null))
    }

    @Test
    fun the_written_order_is_kept_even_though_equality_ignores_it() {
        val backward = identity(listOf(CONTROL_TRAIT, LIST_TRAIT))

        assertEquals(listOf(CONTROL_TRAIT, LIST_TRAIT), backward.traits)
    }

    private fun identity(traits: List<Trait>): ScreenIdentity =
        testIdentity("Cart", elements = setOf(CHECKOUT)).copy(traits = traits)

    private companion object {
        val CHECKOUT = ScreenElementIdentity(
            fingerprint = ElementFingerprint(
                label = "checkout",
                resourceId = "com.example.app:id/checkout",
                className = "android.widget.Button",
                isListItem = false,
                checkable = false,
                editable = false,
            ),
            isBackAffordance = false,
        )
        val LIST_TRAIT = HasList(
            containerResourceId = "com.example.app:id/cart_items",
            minRows = 1,
            rowChildResourceIds = setOf("com.example.app:id/title"),
        )
        val CONTROL_TRAIT = HasControl(CHECKOUT)
    }
}
