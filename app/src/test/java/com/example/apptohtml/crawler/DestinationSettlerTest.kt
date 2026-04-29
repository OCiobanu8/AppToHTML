package com.example.apptohtml.crawler

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationSettlerTest {
    private val settler = DestinationSettler()
    private val fingerprintCoordinator = ScrollScanCoordinator()

    @Test
    fun googleLikeSparseFirstSample_selectsLaterRichRoot() = runBlocking {
        val sparseRoot = rootSnapshot(
            packageName = "com.google.android.gms",
            children = listOf(
                pressableNode(
                    label = "More options",
                    className = "android.view.View",
                    childIndex = 0,
                ),
            ),
        )
        val richRoot = rootSnapshot(
            packageName = "com.google.android.gms",
            children = listOf(
                pressableNode("All services", childIndex = 0),
                pressableNode("Give feedback", className = "android.widget.TextView", childIndex = 1),
                pressableNode("More options", className = "android.view.View", childIndex = 2),
                pressableNode("Sign in", className = "android.view.View", childIndex = 3),
                pressableNode("Sign in", className = "android.view.View", childIndex = 4),
            ),
        )

        val result = settleWithCaptures(
            captures = listOf(sparseRoot, richRoot),
            parentPackageName = "com.android.settings",
        )

        assertEquals(richRoot, result.root)
        assertEquals("com.google.android.gms", result.packageName)
        assertEquals(DestinationSettleStopReason.FIXED_DWELL_EXHAUSTED, result.stopReason)
        assertEquals(DestinationSelectionReason.BEST_RICHNESS, result.selectionReason)
        assertTrue(result.samples.size > 2)
        assertTrue(result.samples.first().eligible)
        assertTrue(
            result.samples.last().metrics!!.richnessScore >
                result.samples.first().metrics!!.richnessScore
        )
    }

    @Test
    fun wellbeingLikeEmptyFirstSample_selectsLaterRichRoot() = runBlocking {
        val emptyRoot = rootSnapshot(
            packageName = "com.google.android.apps.wellbeing",
            children = emptyList(),
        )
        val richRoot = rootSnapshot(
            packageName = "com.google.android.apps.wellbeing",
            children = listOf(
                textNode("TODAY", childIndex = 0),
                pressableNode("App timers", childIndex = 1),
                pressableNode("Bedtime mode", childIndex = 2),
                pressableNode("View app activity details", childIndex = 3),
            ),
        )

        val result = settleWithCaptures(
            captures = listOf(emptyRoot, richRoot),
            parentPackageName = "com.android.settings",
        )

        assertEquals(richRoot, result.root)
        assertEquals(DestinationSelectionReason.BEST_RICHNESS, result.selectionReason)
        assertEquals(3, result.rootMetrics().distinctPressableCount)
        assertEquals(4, result.rootMetrics().visibleTextOrContentDescriptionCount)
    }

    @Test
    fun oneControlSparseDestination_settlesSuccessfullyAfterFixedDwell() = runBlocking {
        val sparseRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Done", childIndex = 0),
            ),
        )

        val result = settleWithCaptures(
            captures = listOf(sparseRoot),
            parentPackageName = "com.example.parent",
        )

        assertEquals(sparseRoot, result.root)
        assertEquals(DestinationSettleStopReason.FIXED_DWELL_EXHAUSTED, result.stopReason)
        assertEquals(DestinationSelectionReason.FINAL_AVAILABLE_SAMPLE, result.selectionReason)
        assertEquals(1, result.rootMetrics().distinctPressableCount)
        assertEquals(10_000L, result.elapsedMillis)
    }

    @Test
    fun expectedPackageMissingUntilTimeout_returnsFailureWithNullAttemptsRecorded() = runBlocking {
        var now = 0L
        val capturedExpectedPackages = mutableListOf<String?>()

        val result = settler.settle(
            request = request(
                parentPackageName = "com.example.parent",
                expectedPackageName = "com.example.destination",
                timeProvider = { now },
                capture = { expectedPackageName ->
                    capturedExpectedPackages += expectedPackageName
                    now += 2_500L
                    null
                },
            ),
        )

        assertNull(result.root)
        assertNull(result.fingerprint)
        assertEquals(DestinationSettleStopReason.NO_ELIGIBLE_SAMPLE, result.stopReason)
        assertEquals(4, result.samples.size)
        assertTrue(result.samples.all { it.root == null })
        assertTrue(result.samples.all { it.eligibilityReason == DestinationEligibilityReason.NULL_CAPTURE })
        assertTrue(capturedExpectedPackages.all { it == "com.example.destination" })
    }

    @Test
    fun richerDifferentFingerprint_prefersRicherSampleWithoutExactEquality() = runBlocking {
        val sparseRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Terms", childIndex = 0)),
        )
        val richRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Privacy", childIndex = 0),
                pressableNode("About", childIndex = 1),
                textNode("Updated destination details", childIndex = 2),
            ),
        )

        val result = settleWithCaptures(
            captures = listOf(sparseRoot, richRoot),
            parentPackageName = "com.example.parent",
        )

        assertEquals(richRoot, result.root)
        assertEquals(DestinationSelectionReason.BEST_RICHNESS, result.selectionReason)
        assertTrue(result.samples[0].fingerprint != result.fingerprint)
        assertTrue(result.samples.all { it.fingerprint == null || it.fingerprintChangedFromBefore })
    }

    @Test
    fun routeReplayWithKnownSavedFingerprint_stopsEarlyWhenObserved() = runBlocking {
        val firstRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Loading action", childIndex = 0)),
        )
        val knownRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Saved destination", childIndex = 0),
                textNode("Ready", childIndex = 1),
            ),
        )
        val knownFingerprint = fingerprint(knownRoot)
        var now = 0L
        var captureIndex = 0

        val result = settler.settle(
            request = request(
                parentPackageName = "com.example.parent",
                expectedPackageName = "com.example.destination",
                knownDestinationFingerprint = knownFingerprint,
                mode = DestinationSettleMode.ROUTE_REPLAY,
                timeProvider = { now },
                capture = {
                    now += 1_000L
                    listOf(firstRoot, knownRoot).getOrElse(captureIndex++) { knownRoot }
                },
            ),
        )

        assertEquals(knownRoot, result.root)
        assertEquals(knownFingerprint, result.fingerprint)
        assertEquals(DestinationSettleStopReason.KNOWN_DESTINATION_FINGERPRINT_MATCHED, result.stopReason)
        assertEquals(DestinationSelectionReason.KNOWN_ROUTE_FINGERPRINT_MATCH, result.selectionReason)
        assertEquals(2, result.samples.size)
        assertTrue(result.elapsedMillis < 10_000L)
    }

    @Test
    fun discoveryWithRepeatedIdenticalEligibleFingerprints_waitsFullDwell() = runBlocking {
        val destinationRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Stable", childIndex = 0)),
        )

        val result = settleWithCaptures(
            captures = listOf(destinationRoot),
            parentPackageName = "com.example.parent",
        )

        assertEquals(destinationRoot, result.root)
        assertEquals(DestinationSettleStopReason.FIXED_DWELL_EXHAUSTED, result.stopReason)
        assertEquals(10_000L, result.elapsedMillis)
        assertEquals(10, result.samples.size)
        assertTrue(result.samples.drop(1).all { it.sameFingerprintAsPrevious })
    }

    @Test
    fun compatibility_acceptsExactFingerprintMatch() {
        val root = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Done", childIndex = 0)),
        )
        val fingerprint = fingerprint(root)
        val metrics = DestinationRichnessMetrics.from(root, fingerprint)

        val result = settler.compatibility(
            expectedRoot = root,
            expectedFingerprint = fingerprint,
            expectedMetrics = metrics,
            actualRoot = root,
            actualFingerprint = fingerprint,
            actualMetrics = metrics,
        )

        assertTrue(result.isCompatible)
        assertEquals(DestinationCompatibilityReason.EXACT_FINGERPRINT_MATCH, result.reason)
    }

    @Test
    fun compatibility_acceptsGoogleLikeSparseExpectedAndRicherActualWithOverlap() {
        val sparseRoot = rootSnapshot(
            packageName = "com.google.android.gms",
            children = listOf(
                pressableNode(
                    label = "More options",
                    className = "android.view.View",
                    childIndex = 0,
                ),
            ),
        )
        val richRoot = rootSnapshot(
            packageName = "com.google.android.gms",
            children = listOf(
                pressableNode("All services", childIndex = 0),
                pressableNode("Give feedback", className = "android.widget.TextView", childIndex = 1),
                pressableNode("More options", className = "android.view.View", childIndex = 2),
                pressableNode("Sign in", className = "android.view.View", childIndex = 3),
            ),
        )

        val result = compatibility(sparseRoot, richRoot)

        assertTrue(result.isCompatible)
        assertEquals(DestinationCompatibilityReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES, result.reason)
        assertTrue(result.actualMetrics.richnessScore > result.expectedMetrics.richnessScore)
    }

    @Test
    fun compatibility_acceptsEmptySparseExpectedAndRicherActualWithSameRootClass() {
        val emptyRoot = rootSnapshot(
            packageName = "com.google.android.apps.wellbeing",
            children = emptyList(),
        )
        val richRoot = rootSnapshot(
            packageName = "com.google.android.apps.wellbeing",
            children = listOf(
                textNode("TODAY", childIndex = 0),
                pressableNode("App timers", childIndex = 1),
                pressableNode("Bedtime mode", childIndex = 2),
                pressableNode("View app activity details", childIndex = 3),
            ),
        )

        val result = compatibility(emptyRoot, richRoot)

        assertTrue(result.isCompatible)
        assertEquals(DestinationCompatibilityReason.SPARSE_EXPECTED_ROOT_CLASS_MATCH, result.reason)
        assertTrue(result.actualMetrics.richnessScore > result.expectedMetrics.richnessScore)
    }

    @Test
    fun compatibility_rejectsSamePackageUnrelatedDestinations() {
        val expectedRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Account", childIndex = 0),
                pressableNode("Privacy", childIndex = 1),
            ),
        )
        val actualRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Cart", childIndex = 0),
                pressableNode("Checkout", childIndex = 1),
            ),
        )

        val result = compatibility(expectedRoot, actualRoot)

        assertFalse(result.isCompatible)
        assertEquals(DestinationCompatibilityReason.UNRELATED_DESTINATION_IDENTITIES, result.reason)
    }

    @Test
    fun compatibility_rejectsPackageMismatch() {
        val expectedRoot = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Done", childIndex = 0)),
        )
        val actualRoot = rootSnapshot(
            packageName = "com.example.other",
            children = listOf(pressableNode("Done", childIndex = 0)),
        )

        val result = compatibility(expectedRoot, actualRoot)

        assertFalse(result.isCompatible)
        assertEquals(DestinationCompatibilityReason.PACKAGE_MISMATCH, result.reason)
    }

    private suspend fun settleWithCaptures(
        captures: List<AccessibilityNodeSnapshot>,
        parentPackageName: String,
        maxSettleMillis: Long = 10_000L,
    ): DestinationSettleResult {
        var now = 0L
        var captureIndex = 0
        return settler.settle(
            request = request(
                parentPackageName = parentPackageName,
                timeProvider = { now },
                maxSettleMillis = maxSettleMillis,
                capture = {
                    now += 1_000L
                    captures.getOrElse(captureIndex++) { captures.last() }
                },
            ),
        )
    }

    private fun request(
        parentPackageName: String,
        expectedPackageName: String? = null,
        knownDestinationFingerprint: String? = null,
        mode: DestinationSettleMode = DestinationSettleMode.DISCOVERY,
        timeProvider: () -> Long,
        maxSettleMillis: Long = 10_000L,
        capture: suspend (String?) -> AccessibilityNodeSnapshot?,
    ): DestinationSettleRequest {
        val parentRoot = rootSnapshot(
            packageName = parentPackageName,
            children = listOf(pressableNode("Open destination", childIndex = 0)),
        )
        val topRoot = rootSnapshot(
            packageName = parentPackageName,
            children = listOf(pressableNode("Top destination", childIndex = 0)),
        )
        return DestinationSettleRequest(
            parentPackageName = parentPackageName,
            expectedPackageName = expectedPackageName,
            beforeClickFingerprint = fingerprint(parentRoot),
            topFingerprint = fingerprint(topRoot),
            knownDestinationFingerprint = knownDestinationFingerprint,
            mode = mode,
            fingerprint = ::fingerprint,
            capture = capture,
            timeProvider = timeProvider,
            maxSettleMillis = maxSettleMillis,
        )
    }

    private fun DestinationSettleResult.rootMetrics(): DestinationRichnessMetrics {
        assertNotNull(root)
        val selectedRoot = root!!
        return samples.first { it.root == selectedRoot }.metrics!!
    }

    private fun fingerprint(root: AccessibilityNodeSnapshot): String {
        return fingerprintCoordinator.logicalViewportFingerprint(root)
    }

    private fun compatibility(
        expectedRoot: AccessibilityNodeSnapshot,
        actualRoot: AccessibilityNodeSnapshot,
    ): DestinationCompatibilityResult {
        val expectedFingerprint = fingerprint(expectedRoot)
        val actualFingerprint = fingerprint(actualRoot)
        return settler.compatibility(
            expectedRoot = expectedRoot,
            expectedFingerprint = expectedFingerprint,
            expectedMetrics = DestinationRichnessMetrics.from(expectedRoot, expectedFingerprint),
            actualRoot = actualRoot,
            actualFingerprint = actualFingerprint,
            actualMetrics = DestinationRichnessMetrics.from(actualRoot, actualFingerprint),
        )
    }

    private fun rootSnapshot(
        packageName: String,
        className: String = "android.widget.FrameLayout",
        children: List<AccessibilityNodeSnapshot>,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = packageName,
            viewIdResourceName = "$packageName:id/root",
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
    }

    private fun pressableNode(
        label: String,
        className: String = "android.widget.Button",
        childIndex: Int,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = className,
            packageName = "com.example.destination",
            viewIdResourceName = "com.example.destination:id/${label.lowercase().replace(" ", "_")}",
            text = label,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,${childIndex * 100}][1080,${childIndex * 100 + 80}]",
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    private fun textNode(
        text: String,
        childIndex: Int,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.TextView",
            packageName = "com.example.destination",
            viewIdResourceName = null,
            text = text,
            contentDescription = null,
            clickable = false,
            supportsClickAction = false,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,${childIndex * 100}][1080,${childIndex * 100 + 80}]",
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }
}
