package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A snapshot's stored identity describes the screen the crawler would arrive at.
 *
 * That means the **first viewport**, not the scroll-merged tree. Taking it from the merged tree
 * made a capture fail validation against itself: the stored set carried below-the-fold controls the
 * first viewport does not, so the tool reported them missing and offered to paste back a set with
 * them deleted — advising the operator to corrupt a correct identity.
 *
 * Every existing test of this builder passed `root = null`, so the line that builds the element set
 * was never executed by any of them.
 */
class SnapshotIdentityTest {

    @Test
    fun `the stored element set is the first viewport, not the merged tree`() {
        val state = SnapshotCrawlState.build(
            snapshot = snapshotWithScroll(),
            root = mergedTree(),
            sessionId = "session-1",
            startedAt = 1L,
            finishedAt = 2L,
        )

        val labels = state.screenIdentity.elements.map { it.fingerprint.label }.toSet()
        assertEquals(
            "Only what the first viewport shows; the merged tree's extra control is not part of " +
                "the identity the crawler would build on arrival.",
            setOf("visible row"),
            labels,
        )
    }

    @Test
    fun `a snapshot identity validates against its own first viewport`() {
        val state = SnapshotCrawlState.build(
            snapshot = snapshotWithScroll(),
            root = mergedTree(),
            sessionId = "session-1",
            startedAt = 1L,
            finishedAt = 2L,
        )

        val comparison = SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen = true))
            .compare(state.screenIdentity, ScreenIdentity.fromRoot(firstViewport()))

        assertTrue(
            "A capture compared against itself must match. It did not while the stored set came " +
                "from the merged tree.",
            comparison.matched,
        )
    }

    @Test
    fun `a snapshot with no scroll steps falls back to the root it was given`() {
        val state = SnapshotCrawlState.build(
            snapshot = ScreenSnapshot(
                screenName = "Cart",
                packageName = PACKAGE,
                elements = emptyList(),
                xmlDump = "",
            ),
            root = firstViewport(),
            sessionId = "session-1",
            startedAt = 1L,
            finishedAt = 2L,
        )

        assertEquals(
            setOf("visible row"),
            state.screenIdentity.elements.map { it.fingerprint.label }.toSet(),
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun snapshotWithScroll() = ScreenSnapshot(
        screenName = "Cart",
        packageName = PACKAGE,
        elements = emptyList(),
        xmlDump = "",
        scrollStepCount = 2,
        stepSnapshots = listOf(
            ScrollCaptureStep(stepIndex = 0, root = firstViewport(), newElementCount = 0),
            ScrollCaptureStep(stepIndex = 1, root = mergedTree(), newElementCount = 1),
        ),
    )

    /** What is on screen before any scrolling. */
    private fun firstViewport() = root(children = listOf(control("visible row", "visible_row")))

    /** The same screen after scrolling: one more control the first viewport never showed. */
    private fun mergedTree() = root(
        children = listOf(
            control("visible row", "visible_row"),
            control("below fold", "below_fold"),
        )
    )

    private fun root(children: List<AccessibilityNodeSnapshot>) = AccessibilityNodeSnapshot(
        className = "android.widget.FrameLayout",
        packageName = PACKAGE,
        viewIdResourceName = "$PACKAGE:id/root",
        text = null,
        contentDescription = null,
        clickable = false,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,0][1080,2400]",
        children = children,
    )

    private fun control(label: String, id: String) = AccessibilityNodeSnapshot(
        className = "android.widget.Button",
        packageName = PACKAGE,
        viewIdResourceName = "$PACKAGE:id/$id",
        text = label,
        contentDescription = null,
        clickable = true,
        supportsClickAction = false,
        scrollable = false,
        enabled = true,
        visibleToUser = true,
        bounds = "[0,100][1080,200]",
        children = emptyList(),
    )

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
