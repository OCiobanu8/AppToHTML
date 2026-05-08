package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

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

    @Test
    fun rewindToEntryScreen_requires_expected_logical_match_when_expected_is_supplied() = runBlocking {
        val homeRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Home Search",
                    resourceId = "com.example.target:id/home_search",
                    bounds = "[0,0][100,50]",
                    childIndex = 0,
                ),
            ),
        )
        val otherEntryRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Network",
                    resourceId = "com.example.target:id/network",
                    bounds = "[0,0][100,50]",
                    childIndex = 0,
                ),
            ),
        )
        val expectedFingerprint = coordinator.logicalEntryViewportFingerprint(otherEntryRoot)
        var backAttempts = 0

        val result = coordinator.rewindToEntryScreen(
            initialRoot = homeRoot,
            targetPackageName = "com.example.target",
            expectedEntryLogicalFingerprint = expectedFingerprint,
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { null },
        )

        assertEquals(0, backAttempts)
        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertEquals(coordinator.logicalEntryViewportFingerprint(homeRoot), result.observedLogicalFingerprint)
        assertEquals(expectedFingerprint, result.expectedLogicalFingerprint)
        assertFalse(result.matchedExpectedLogical)
        assertFalse(result.verifiedForReplay)
    }

    @Test
    fun rewindToEntryScreen_allows_no_back_assumed_entry_without_expected_fingerprint() = runBlocking {
        val homeRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Home Search",
                    resourceId = "com.example.target:id/home_search",
                    bounds = "[0,0][100,50]",
                    childIndex = 0,
                ),
            ),
        )
        var backAttempts = 0

        val result = coordinator.rewindToEntryScreen(
            initialRoot = homeRoot,
            targetPackageName = "com.example.target",
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { null },
        )

        assertEquals(0, backAttempts)
        assertEquals(EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY, result.outcome)
        assertEquals(coordinator.logicalEntryViewportFingerprint(homeRoot), result.observedLogicalFingerprint)
        assertEquals(null, result.expectedLogicalFingerprint)
        assertFalse(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
    }

    @Test
    fun rewindToEntryScreen_matches_expected_entry_after_backing() = runBlocking {
        val detailRoot = rootSnapshot(
            elements = listOf(
                backAffordanceNode(childIndex = 0, bounds = "[0,0][120,120]"),
                pressableNode(
                    label = "Detail Action",
                    resourceId = "com.example.target:id/detail_action",
                    bounds = "[0,240][100,290]",
                    childIndex = 1,
                ),
            ),
        )
        val middleRoot = rootSnapshot(
            elements = listOf(
                backAffordanceNode(childIndex = 0, bounds = "[0,0][120,120]"),
                pressableNode(
                    label = "Middle Action",
                    resourceId = "com.example.target:id/middle_action",
                    bounds = "[0,240][100,290]",
                    childIndex = 1,
                ),
            ),
        )
        val homeRoot = rootSnapshot(
            elements = listOf(
                pressableNode(
                    label = "Home Search",
                    resourceId = "com.example.target:id/home_search",
                    bounds = "[0,0][100,50]",
                    childIndex = 0,
                ),
            ),
        )
        val expectedFingerprint = coordinator.logicalEntryViewportFingerprint(homeRoot)
        val captures = mutableListOf<AccessibilityNodeSnapshot?>(
            middleRoot,
            middleRoot,
            homeRoot,
            homeRoot,
        )
        var backAttempts = 0

        val result = coordinator.rewindToEntryScreen(
            initialRoot = detailRoot,
            targetPackageName = "com.example.target",
            expectedEntryLogicalFingerprint = expectedFingerprint,
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { captures.removeAt(0) },
        )

        assertEquals(2, backAttempts)
        assertEquals(EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL, result.outcome)
        assertEquals(expectedFingerprint, result.observedLogicalFingerprint)
        assertEquals(expectedFingerprint, result.expectedLogicalFingerprint)
        assertTrue(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
    }

    @Test
    fun entryScreenBackAffordanceDetector_detects_top_compose_parent_with_nested_navigate_up_label() {
        val root = rootSnapshot(
            elements = listOf(
                composeNavigateUpParent(
                    bounds = "[196,147][322,273]",
                    childIndexPath = listOf(0),
                ),
            ),
        )

        assertTrue(EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(root))
    }

    @Test
    fun entryScreenBackAffordanceDetector_ignores_lower_row_with_nested_back_label() {
        val root = rootSnapshot(
            elements = listOf(
                composeNavigateUpParent(
                    bounds = "[196,900][322,1026]",
                    childIndexPath = listOf(0),
                ),
            ),
        )

        assertFalse(EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(root))
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

    private fun composeNavigateUpParent(
        bounds: String,
        childIndexPath: List<Int>,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.view.View",
            packageName = "com.example.target",
            viewIdResourceName = null,
            text = null,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = bounds,
            children = listOf(
                AccessibilityNodeSnapshot(
                    className = "android.view.View",
                    packageName = "com.example.target",
                    viewIdResourceName = null,
                    text = null,
                    contentDescription = "Navigate up",
                    clickable = false,
                    supportsClickAction = false,
                    scrollable = false,
                    enabled = true,
                    visibleToUser = true,
                    bounds = bounds,
                    childIndexPath = childIndexPath + 0,
                    children = emptyList(),
                ),
            ),
            childIndexPath = childIndexPath,
        )
    }
}
