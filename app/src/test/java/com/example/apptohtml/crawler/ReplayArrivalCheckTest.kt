package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crawler consults a screen's settled identity when a replay arrives.
 *
 * Two things are pinned here, and the second matters more than the first.
 *
 * 1. A settled identity decides: if its traits do not hold on the screen the replay landed on, the
 *    replay has not arrived. Without this the tool certifies identities no crawl ever consults.
 * 2. **An unsettled identity changes nothing.** Every identity a capture proposes carries no traits
 *    and is therefore unsettled, so every existing crawl must behave exactly as it did before. That
 *    is the entire safety argument for adding a condition here, and
 *    `an unsettled identity is not consulted at all` is the pin that holds it.
 */
class ReplayArrivalCheckTest {

    // --- 1. a settled identity decides ----------------------------------------------------------

    @Test
    fun `a settled identity whose traits hold has arrived`() {
        val expected = settled(minRows = 2)
        val root = listScreen(rowCount = 3)

        assertEquals(
            ReplayArrivalCheck.Verdict.Arrived,
            ReplayArrivalCheck.check(expected, identityOf(root), root),
        )
    }

    @Test
    fun `a settled identity whose traits do not hold has not arrived`() {
        val expected = settled(minRows = 5)
        val root = listScreen(rowCount = 2)

        val verdict = ReplayArrivalCheck.check(expected, identityOf(root), root)

        assertTrue(
            "The name still matches, so only the traits can catch this. Without the trait " +
                "condition the crawler would accept the wrong screen.",
            verdict is ReplayArrivalCheck.Verdict.TraitsDoNotHold,
        )
    }

    @Test
    fun `the divergence names the trait that failed`() {
        val expected = settled(minRows = 5)
        val root = listScreen(rowCount = 2)

        val verdict = ReplayArrivalCheck.check(expected, identityOf(root), root)
            as ReplayArrivalCheck.Verdict.TraitsDoNotHold

        assertEquals("Exactly the failing trait is reported.", 1, verdict.failing.size)
        assertTrue(verdict.failing.single() is HasList)
        assertTrue(
            "An operator has to know WHICH assertion failed. Reporting only that something " +
                "diverged reproduces a2h-c2b.6, where the message read 'Expected X but found X'.",
            verdict.describe().contains("$PACKAGE:id/list"),
        )
    }

    @Test
    fun `a missing control is reported as the failing trait`() {
        val expected = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = emptySet(),
            traits = listOf(HasControl(controlIdentity("Checkout", "$PACKAGE:id/checkout"))),
        ).withName(name())
        val root = listScreen(rowCount = 2)

        val verdict = ReplayArrivalCheck.check(expected, identityOf(root), root)
            as ReplayArrivalCheck.Verdict.TraitsDoNotHold

        assertTrue(verdict.failing.single() is HasControl)
        assertTrue(verdict.describe().contains("Checkout"))
    }

    // --- 2. an unsettled identity changes nothing -----------------------------------------------

    @Test
    fun `an unsettled identity is not consulted at all`() {
        // Every machine-proposed identity looks like this: elements, no traits. Across a table of
        // screens that a trait check would have judged differently, the outcome must be exactly the
        // pre-change one — arrival decided by the name alone.
        val unsettled = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = setOf(controlIdentity("Open", "$PACKAGE:id/open")),
        ).withName(name())

        listOf(
            listScreen(rowCount = 0),
            listScreen(rowCount = 1),
            listScreen(rowCount = 3),
            listScreen(rowCount = 9),
        ).forEach { root ->
            assertEquals(
                "An identity asserting nothing must never make a replay fail. Deleting the " +
                    "unsettled guard turns this red — which is the point of it.",
                ReplayArrivalCheck.Verdict.Arrived,
                ReplayArrivalCheck.check(unsettled, identityOf(root), root),
            )
        }
    }

    @Test
    fun `an identity of negations alone is unsettled and is not consulted`() {
        // The negated control is PRESENT on the screen, so this LacksControl does NOT hold. If
        // negations counted as settling an identity, the verdict would be TraitsDoNotHold. It is
        // Arrived only because an identity asserting nothing present is unsettled.
        //
        // An earlier version of this fixture negated a control that was absent, so the trait held
        // either way and the test could not tell the two rules apart — it passed even with
        // `assertsPresence(LacksControl)` flipped to true.
        val present = controlIdentity(PRESENT_CONTROL_LABEL, PRESENT_CONTROL_ID)
        val negationsOnly = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = emptySet(),
            traits = listOf(LacksControl(present)),
        ).withName(name())
        val root = listScreen(rowCount = 2)

        assertEquals(
            "A negation that is false must still leave the identity unsettled, not failing.",
            ReplayArrivalCheck.Verdict.Arrived,
            ReplayArrivalCheck.check(negationsOnly, identityOf(root), root),
        )
    }

    @Test
    fun `a trait-free identity is not consulted even when its traits could not be evaluated`() {
        // The fast path returns before a context is built. It must agree with TraitEvaluator, which
        // is the authority on what "asserts nothing" means.
        val traitFree = ScreenIdentity(
            packageName = PACKAGE,
            rootClassName = ROOT_CLASS,
            elements = setOf(controlIdentity("Open", "$PACKAGE:id/open")),
        ).withName(name())
        val root = listScreen(rowCount = 2)

        assertEquals(
            TraitVerdict.UNSETTLED,
            TraitEvaluator.verdict(traitFree, TraitEvaluationContext.of(root, name())),
        )
        assertEquals(
            ReplayArrivalCheck.Verdict.Arrived,
            ReplayArrivalCheck.check(traitFree, identityOf(root), root),
        )
    }

    // --- 3. the name rule is untouched ----------------------------------------------------------

    @Test
    fun `a name divergence is still reported on the name, settled or not`() {
        val root = listScreen(rowCount = 3)
        val elsewhere = settled(minRows = 2).withName(
            ScreenNameIdentity(
                packageName = "com_example_target",
                screenName = "somewhere_else",
                titleDisambiguators = emptyList(),
                confidence = ScreenDedupConfidence.STRONG,
            )
        )

        val verdict = ReplayArrivalCheck.check(elsewhere, identityOf(root), root)

        assertTrue(
            "Traits must not take over the name's job, nor mask a name divergence.",
            verdict is ReplayArrivalCheck.Verdict.NameDiverged,
        )
    }

    @Test
    fun `traits are not consulted when the name already diverged`() {
        val root = listScreen(rowCount = 9)
        val elsewhere = settled(minRows = 5).withName(
            ScreenNameIdentity(
                packageName = "com_example_target",
                screenName = "somewhere_else",
                titleDisambiguators = emptyList(),
                confidence = ScreenDedupConfidence.STRONG,
            )
        )

        assertTrue(
            "The name is the cheaper and more fundamental answer; it is reported first.",
            ReplayArrivalCheck.check(elsewhere, identityOf(root), root)
                is ReplayArrivalCheck.Verdict.NameDiverged,
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun settled(minRows: Int): ScreenIdentity = ScreenIdentity(
        packageName = PACKAGE,
        rootClassName = ROOT_CLASS,
        elements = emptySet(),
        traits = listOf(
            HasList(
                containerResourceId = "$PACKAGE:id/list",
                minRows = minRows,
                rowChildResourceIds = setOf("$PACKAGE:id/title"),
            ),
        ),
    ).withName(name())

    private fun name() = ScreenNameIdentity(
        packageName = "com_example_target",
        screenName = "cart",
        titleDisambiguators = emptyList(),
        confidence = ScreenDedupConfidence.STRONG,
    )

    /** The observed identity the crawler builds on arrival, named as the replay expects. */
    private fun identityOf(root: AccessibilityNodeSnapshot): ScreenIdentity =
        ScreenIdentity.fromRoot(root).withName(name())

    private fun controlIdentity(label: String, resourceId: String) = ScreenElementIdentity(
        fingerprint = ElementFingerprint(
            resourceId = resourceId,
            label = label,
            className = "android.widget.Button",
            isListItem = false,
            checkable = false,
            editable = false,
        ),
        isBackAffordance = false,
    )

    /**
     * A screen whose list container holds [rowCount] rows, each carrying a title id, plus one
     * genuinely pressable control so that a negation about it can be false.
     */
    private fun listScreen(rowCount: Int): AccessibilityNodeSnapshot {
        val rows = (0 until rowCount).map { index ->
            node(
                className = "android.widget.LinearLayout",
                resourceId = "$PACKAGE:id/row",
                bounds = "[0,${200 + index * 100}][1080,${200 + index * 100 + 90}]",
                children = listOf(
                    node(
                        className = "android.widget.TextView",
                        resourceId = "$PACKAGE:id/title",
                        text = "Item $index",
                        bounds = "[0,${200 + index * 100}][540,${200 + index * 100 + 90}]",
                    )
                ),
            )
        }
        return node(
            className = ROOT_CLASS,
            resourceId = "$PACKAGE:id/root",
            bounds = "[0,0][1080,2400]",
            children = listOf(
                node(
                    className = "android.widget.Button",
                    resourceId = PRESENT_CONTROL_ID,
                    text = PRESENT_CONTROL_LABEL,
                    bounds = "[0,100][1080,180]",
                    clickable = true,
                ),
                node(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    resourceId = "$PACKAGE:id/list",
                    bounds = "[0,200][1080,2000]",
                    children = rows,
                ),
            ),
        )
    }

    private fun node(
        className: String,
        resourceId: String,
        bounds: String,
        text: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNodeSnapshot> = emptyList(),
    ) = AccessibilityNodeSnapshot(
        className = className,
        packageName = PACKAGE,
        viewIdResourceName = resourceId,
        text = text,
        contentDescription = null,
        clickable = clickable,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = bounds,
        children = children,
    )

    private companion object {
        const val PACKAGE = "com.example.target"

        /** A control the fixture really shows, so `LacksControl` about it is false. */
        const val PRESENT_CONTROL_ID = "com.example.target:id/present"
        const val PRESENT_CONTROL_LABEL = "present"
        const val ROOT_CLASS = "android.widget.FrameLayout"
    }
}
