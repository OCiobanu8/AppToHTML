package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ScrollScanCoordinatorTest {
    private val coordinator = ScrollScanCoordinator()

    @Test
    fun screenIdentity_ignores_root_and_element_bounds_shifts() {
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
            ScreenIdentity.fromRoot(originalRoot),
            ScreenIdentity.fromRoot(shiftedRoot),
        )
        assertTrue(
            coordinator.geometrySensitiveViewportFingerprint(originalRoot) !=
                coordinator.geometrySensitiveViewportFingerprint(shiftedRoot),
        )
    }

    @Test
    fun screenIdentity_changes_when_semantic_content_changes() {
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
            ScreenIdentity.fromRoot(originalRoot) !=
                ScreenIdentity.fromRoot(changedRoot),
        )
    }

    @Test
    fun entryRestore_ignores_a_back_affordance_that_only_one_capture_shows() {
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

        // RETIRED AND REPLACED. This pinned the old two-builder asymmetry: the entry builder
        // dropped the back affordance so the two captures produced one identical string. There is
        // now one builder that keeps it, flagged, so the identities differ by that element — and
        // the policy, not the builder, is what ignores it.
        assertNotEquals(
            ScreenIdentity.fromRoot(entryWithoutBack),
            ScreenIdentity.fromRoot(entryWithBackAndShiftedBounds),
        )
        assertTrue(
            EntryRestorePolicy("com.example.target").compare(
                expected = ScreenIdentity.fromRoot(entryWithoutBack),
                observed = ScreenIdentity.fromRoot(entryWithBackAndShiftedBounds),
            ).matched,
        )
    }

    @Test
    fun rewindToEntryScreen_rejects_unrelated_no_back_entry_when_expected_supplied() = runBlocking {
        val homeRoot = settingsRoot("Home Search")
        val otherEntryRoot = settingsRoot("Network")
        val expectedFingerprint = ScreenIdentity.fromRoot(otherEntryRoot)
        var backAttempts = 0

        val result = coordinator.rewindToEntryScreen(
            initialRoot = homeRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { null },
        )

        assertEquals(0, backAttempts)
        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertEquals(ScreenIdentity.fromRoot(homeRoot), result.observedIdentity)
        assertEquals(expectedFingerprint, result.expectedIdentity)
        assertFalse(result.matchedExpectedLogical)
        assertFalse(result.verifiedForReplay)
        assertEquals(ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES, result.entryFingerprintMatchReason)
        assertEquals(1, result.entryFingerprintExpectedCount)
        assertEquals(1, result.entryFingerprintObservedCount)
        assertEquals(0, result.entryFingerprintOverlapCount)
    }

    @Test
    fun rewindToEntryScreen_accepts_expected_entry_when_observed_adds_pressable() = runBlocking {
        val expectedRoot = settingsRoot(
            "Apps",
            "Connected devices",
            "Display and touch",
            "Google",
            "Modes",
            "Network and internet",
            "Notifications",
            "Sound and vibration",
            "Wallpaper and style",
        )
        val observedRoot = settingsRoot(
            "Apps",
            "Connected devices",
            "Display and touch",
            "Google",
            "Modes",
            "Network and internet",
            "Notifications",
            "Sound and vibration",
            "Storage",
            "Wallpaper and style",
        )
        val expectedFingerprint = ScreenIdentity.fromRoot(expectedRoot)
        var backAttempts = 0

        val result = coordinator.rewindToEntryScreen(
            initialRoot = observedRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { null },
        )

        assertEquals(0, backAttempts)
        assertEquals(EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL, result.outcome)
        assertFalse(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
        assertEquals(
            ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.entryFingerprintMatchReason,
        )
        assertEquals(9, result.entryFingerprintExpectedCount)
        assertEquals(10, result.entryFingerprintObservedCount)
        assertEquals(9, result.entryFingerprintOverlapCount)
        assertDoubleEquals(18.0 / 19.0, result.entryFingerprintDiceSimilarity)
    }

    @Test
    fun rewindToEntryScreen_accepts_expected_entry_when_extra_pressable_displaces_one_expected_pressable() =
        runBlocking {
            val expectedRoot = settingsRoot(
                "Apps",
                "Connected devices",
                "Display and touch",
                "Google",
                "Modes",
                "Network and internet",
                "Notifications",
                "Sound and vibration",
                "Wallpaper and style",
            )
            val observedRoot = settingsRoot(
                "Apps",
                "Connected devices",
                "Display and touch",
                "Google",
                "Modes",
                "Network and internet",
                "Notifications",
                "Sound and vibration",
                "Storage",
            )
            val expectedFingerprint = ScreenIdentity.fromRoot(expectedRoot)

            val result = coordinator.rewindToEntryScreen(
                initialRoot = observedRoot,
                targetPackageName = "com.example.target",
                expectedEntryIdentity = expectedFingerprint,
                tryBack = { true },
                captureCurrentRoot = { null },
            )

            assertEquals(EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL, result.outcome)
            assertFalse(result.matchedExpectedLogical)
            assertTrue(result.verifiedForReplay)
            assertEquals(
                ScreenIdentityMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
                result.entryFingerprintMatchReason,
            )
            assertEquals(9, result.entryFingerprintExpectedCount)
            assertEquals(9, result.entryFingerprintObservedCount)
            assertEquals(8, result.entryFingerprintOverlapCount)
            assertDoubleEquals(8.0 / 9.0, result.entryFingerprintDiceSimilarity)
        }

    @Test
    fun rewindToEntryScreen_accepts_sparse_entry_when_percentage_similarity_is_high() = runBlocking {
        val expectedRoot = settingsRoot("Apps", "Google")
        val observedRoot = settingsRoot("Apps", "Google", "Storage")
        val expectedFingerprint = ScreenIdentity.fromRoot(expectedRoot)

        val result = coordinator.rewindToEntryScreen(
            initialRoot = observedRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = { true },
            captureCurrentRoot = { null },
        )

        assertEquals(EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL, result.outcome)
        assertFalse(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
        assertEquals(
            ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.entryFingerprintMatchReason,
        )
        assertEquals(2, result.entryFingerprintExpectedCount)
        assertEquals(3, result.entryFingerprintObservedCount)
        assertEquals(2, result.entryFingerprintOverlapCount)
        assertDoubleEquals(0.8, result.entryFingerprintDiceSimilarity)
    }

    @Test
    fun rewindToEntryScreen_rejects_below_percentage_threshold() = runBlocking {
        val expectedRoot = settingsRoot("Apps", "Connected devices", "Display", "Google")
        val observedRoot = settingsRoot("Apps", "Storage", "Battery", "Security")
        val expectedFingerprint = ScreenIdentity.fromRoot(expectedRoot)

        val result = coordinator.rewindToEntryScreen(
            initialRoot = observedRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = { true },
            captureCurrentRoot = { null },
        )

        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertFalse(result.matchedExpectedLogical)
        assertFalse(result.verifiedForReplay)
        assertEquals(ScreenIdentityMatchReason.IDENTITY_OVERLAP_TOO_LOW, result.entryFingerprintMatchReason)
        assertEquals(4, result.entryFingerprintExpectedCount)
        assertEquals(4, result.entryFingerprintObservedCount)
        assertEquals(1, result.entryFingerprintOverlapCount)
        assertDoubleEquals(0.25, result.entryFingerprintDiceSimilarity)
    }

    @Test
    fun rewindToEntryScreen_rejects_single_generic_overlap_when_similarity_is_low() = runBlocking {
        val expectedRoot = settingsRoot("Done", "Cancel", "Share", "More")
        val observedRoot = settingsRoot("Done", "Storage", "Battery", "Security")
        val expectedFingerprint = ScreenIdentity.fromRoot(expectedRoot)

        val result = coordinator.rewindToEntryScreen(
            initialRoot = observedRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = { true },
            captureCurrentRoot = { null },
        )

        assertEquals(EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND, result.outcome)
        assertFalse(result.verifiedForReplay)
        assertEquals(ScreenIdentityMatchReason.IDENTITY_OVERLAP_TOO_LOW, result.entryFingerprintMatchReason)
        assertEquals(1, result.entryFingerprintOverlapCount)
        assertDoubleEquals(0.25, result.entryFingerprintDiceSimilarity)
    }

    @Test
    fun entryRestore_exact_match_still_fast_path() = runBlocking {
        val homeRoot = settingsRoot("Apps", "Google", "Notifications")
        val expectedFingerprint = ScreenIdentity.fromRoot(homeRoot)

        val result = coordinator.rewindToEntryScreen(
            initialRoot = homeRoot,
            targetPackageName = "com.example.target",
            expectedEntryIdentity = expectedFingerprint,
            tryBack = { true },
            captureCurrentRoot = { null },
        )

        assertEquals(EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL, result.outcome)
        assertEquals(expectedFingerprint, result.observedIdentity)
        assertTrue(result.matchedExpectedLogical)
        assertTrue(result.verifiedForReplay)
        assertEquals(ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH, result.entryFingerprintMatchReason)
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
        assertEquals(ScreenIdentity.fromRoot(homeRoot), result.observedIdentity)
        assertEquals(null, result.expectedIdentity)
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
        val expectedFingerprint = ScreenIdentity.fromRoot(homeRoot)
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
            expectedEntryIdentity = expectedFingerprint,
            tryBack = {
                backAttempts += 1
                true
            },
            captureCurrentRoot = { captures.removeAt(0) },
        )

        assertEquals(2, backAttempts)
        assertEquals(EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL, result.outcome)
        assertEquals(expectedFingerprint, result.observedIdentity)
        assertEquals(expectedFingerprint, result.expectedIdentity)
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

    private fun settingsRoot(vararg labels: String): AccessibilityNodeSnapshot {
        return rootSnapshot(
            elements = labels.mapIndexed { index, label ->
                pressableNode(
                    label = label,
                    resourceId = "com.example.target:id/${label.lowercase().replace(' ', '_')}",
                    bounds = "[0,${index * 100}][600,${index * 100 + 80}]",
                    childIndex = index,
                )
            },
        )
    }

    private fun assertDoubleEquals(
        expected: Double,
        actual: Double,
    ) {
        assertEquals(expected, actual, 0.0001)
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
