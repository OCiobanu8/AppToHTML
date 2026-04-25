package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollScanCoordinatorTest {
    private val coordinator = ScrollScanCoordinator()

    @Test
    fun logicalViewportFingerprint_ignores_root_and_element_bounds_shifts() {
        val originalRoot = rootSnapshot(
            rootBounds = "[0,0][1080,2400]",
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[24,220][820,340]",
                    childIndex = 0,
                ),
                pressableNode(
                    label = "Notifications",
                    resourceId = "com.example.target:id/notifications",
                    bounds = "[24,360][820,480]",
                    childIndex = 1,
                    checkable = true,
                    checked = true,
                ),
            ),
        )
        val shiftedRoot = rootSnapshot(
            rootBounds = "[0,12][1080,2412]",
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[36,236][832,356]",
                    childIndex = 0,
                ),
                pressableNode(
                    label = "Notifications",
                    resourceId = "com.example.target:id/notifications",
                    bounds = "[36,376][832,496]",
                    childIndex = 1,
                    checkable = true,
                    checked = true,
                ),
            ),
        )

        assertEquals(
            coordinator.logicalViewportFingerprint(originalRoot),
            coordinator.logicalViewportFingerprint(shiftedRoot),
        )
        assertTrue(
            coordinator.geometrySensitiveViewportFingerprint(originalRoot) !=
                coordinator.geometrySensitiveViewportFingerprint(shiftedRoot),
        )
    }

    @Test
    fun logicalViewportFingerprint_changes_when_semantic_content_changes() {
        val originalRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[24,220][820,340]",
                    childIndex = 0,
                ),
                pressableNode(
                    label = "Notifications",
                    resourceId = "com.example.target:id/notifications",
                    bounds = "[24,360][820,480]",
                    childIndex = 1,
                    checkable = true,
                ),
            ),
        )
        val changedRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[24,220][820,340]",
                    childIndex = 0,
                ),
                pressableNode(
                    label = "Notifications",
                    resourceId = "com.example.target:id/notifications",
                    bounds = "[24,360][820,480]",
                    childIndex = 1,
                    checkable = false,
                ),
            ),
        )

        assertTrue(
            coordinator.logicalViewportFingerprint(originalRoot) !=
                coordinator.logicalViewportFingerprint(changedRoot),
        )
    }

    @Test
    fun logicalEntryViewportFingerprint_matches_with_and_without_visible_back_affordance() {
        val entryWithoutBack = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[24,220][820,340]",
                    childIndex = 0,
                ),
            ),
        )
        val entryWithBackAndShiftedBounds = rootSnapshot(
            rootBounds = "[0,8][1080,2408]",
            elements = listOf(
                pressableNode(
                    label = "Google services",
                    resourceId = "com.example.target:id/google_services",
                    bounds = "[36,236][832,356]",
                    childIndex = 0,
                ),
                backAffordanceNode(childIndex = 1, bounds = "[0,8][144,152]"),
            ),
        )

        assertEquals(
            coordinator.logicalEntryViewportFingerprint(entryWithoutBack),
            coordinator.logicalEntryViewportFingerprint(entryWithBackAndShiftedBounds),
        )
    }

    private fun rootSnapshot(
        rootBounds: String = "[0,0][1080,2400]",
        elements: List<AccessibilityNodeSnapshot>,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.FrameLayout",
            packageName = "com.example.target",
            viewIdResourceName = "com.example.target:id/root",
            text = null,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = rootBounds,
            children = elements,
        )
    }

    private fun pressableNode(
        label: String,
        resourceId: String,
        bounds: String,
        childIndex: Int,
        checkable: Boolean = false,
        checked: Boolean = false,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.Button",
            packageName = "com.example.target",
            viewIdResourceName = resourceId,
            text = label,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            checkable = checkable,
            checked = checked,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    private fun backAffordanceNode(
        childIndex: Int,
        bounds: String,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.ImageButton",
            packageName = "com.example.target",
            viewIdResourceName = "com.example.target:id/back_button",
            text = null,
            contentDescription = "Navigate up",
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }
}
