package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the back-affordance rule and — the point of this file — its one deliberate exception.
 *
 * The exception lives at a call site inside the replay loop that no unit test can reach, so it is
 * pinned through [RouteStepDestinationCheck]. These assertions are behavioural: they compare
 * identities that differ *only* by a back affordance, so a comparison that stopped counting them
 * would report a match and turn the pin red. Asserting the constant's value instead would pass
 * whatever the call site actually did.
 */
class BackAffordanceCountingTest {

    @Test
    fun `ordinary screens count back affordances unless they are the crawl root`() {
        assertTrue(
            "A non-root screen's stored identity carries its back affordance, so it counts.",
            BackAffordanceCounting.forScreen(isRootScreen = false),
        )
        assertFalse(
            "The crawl root was captured before the crawler had navigated away, so it has none.",
            BackAffordanceCounting.forScreen(isRootScreen = true),
        )
    }

    @Test
    fun `route-step destination counts a back affordance that the expectation lacks`() {
        val expected = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = false)
        val observed = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = true)

        val comparison = RouteStepDestinationCheck.compare(expected, observed)

        assertFalse(
            "The two identities differ only by a back affordance. A destination comparison that " +
                "ignored back affordances would call this a match — which is the depth-1 " +
                "tolerance regression this pin exists to prevent.",
            comparison.matched,
        )
        assertEquals(
            ScreenIdentityMatchReason.ELEMENT_SET_DIFFERS,
            comparison.reason,
        )
        assertEquals(
            "The back affordance is the extra element.",
            setOf("Navigate up"),
            comparison.extra.map { it.fingerprint.label }.toSet(),
        )
    }

    @Test
    fun `route-step destination counts a back affordance that the observation lacks`() {
        val expected = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = true)
        val observed = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = false)

        val comparison = RouteStepDestinationCheck.compare(expected, observed)

        assertFalse(comparison.matched)
        assertEquals(
            "The back affordance is the missing element.",
            setOf("Navigate up"),
            comparison.missing.map { it.fingerprint.label }.toSet(),
        )
    }

    @Test
    fun `route-step destination still matches identities that agree in full`() {
        val expected = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = true)
        val observed = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = true)

        val comparison = RouteStepDestinationCheck.compare(expected, observed)

        assertTrue(
            "Counting back affordances must not make a genuinely identical screen fail.",
            comparison.matched,
        )
        assertEquals(ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH, comparison.reason)
    }

    /**
     * The rule the destination check must NOT adopt.
     *
     * Spelled out so the difference is visible: a parent-derived rule drops the back affordance for
     * a depth-1 child, which is exactly how the loosening happened.
     */
    @Test
    fun `a parent-derived rule would ignore the back affordance for a depth-1 child`() {
        val expected = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = false)
        val observed = identity(labels = listOf("Inbox", "Drafts"), withBackAffordance = true)

        val parentDerived = SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen = true))

        assertTrue(
            "This is the looser answer the destination check must never give.",
            parentDerived.compare(expected, observed).matched,
        )
        assertFalse(
            "The destination check disagrees with it — which is the whole exception.",
            RouteStepDestinationCheck.compare(expected, observed).matched,
        )
    }

    private fun identity(
        labels: List<String>,
        withBackAffordance: Boolean,
        rootClassName: String = "android.widget.FrameLayout",
    ): ScreenIdentity {
        val elements = buildSet {
            labels.forEach { label ->
                add(
                    ScreenElementIdentity(
                        fingerprint = ElementFingerprint(
                            resourceId = "$PACKAGE:id/${label.lowercase()}",
                            label = label,
                            className = "android.widget.TextView",
                            isListItem = false,
                            checkable = false,
                            editable = false,
                        ),
                        isBackAffordance = false,
                    )
                )
            }
            if (withBackAffordance) {
                add(
                    ScreenElementIdentity(
                        fingerprint = ElementFingerprint(
                            resourceId = "$PACKAGE:id/back_button",
                            label = "Navigate up",
                            className = "android.widget.ImageButton",
                            isListItem = false,
                            checkable = false,
                            editable = false,
                        ),
                        isBackAffordance = true,
                    )
                )
            }
        }
        return ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = rootClassName,
            elements = elements,
        )
    }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
