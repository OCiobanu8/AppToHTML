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
        assertEquals(3_000L, result.elapsedMillis)
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
        assertNull(result.identity)
        assertEquals(DestinationSettleStopReason.NO_ELIGIBLE_SAMPLE, result.stopReason)
        assertEquals(2, result.samples.size)
        assertTrue(result.samples.all { it.root == null })
        assertTrue(result.samples.all { it.eligibilityReason == DestinationEligibilityReason.NULL_CAPTURE })
        assertTrue(capturedExpectedPackages.all { it == "com.example.destination" })
    }

    /**
     * Pins what the settler MEASURES, not just what the codec can produce.
     *
     * `logicalFingerprintLength` feeds `richnessScore`, the comparator that selects the captured
     * sample, so it must measure the pre-structure logical encoding. Measuring the flagged
     * `encodeContent` instead lengthens every element by the back flag and moves that decision.
     */
    @Test
    fun settle_measures_richness_from_the_logical_encoding() = runBlocking {
        var now = 0L
        val destinationRoot = rootSnapshot(
            packageName = "com.example.target",
            children = listOf(pressableNode("Detailed option", childIndex = 0)),
        )

        val result = DestinationSettler().settle(
            request(
                parentPackageName = "com.example.target",
                mode = DestinationSettleMode.DISCOVERY,
                timeProvider = { now },
                capture = {
                    now += 4_000L
                    destinationRoot
                },
            ),
        )

        val metrics = result.rootMetrics()
        assertEquals(
            ScreenIdentityCodec.encodeLogical(identity(destinationRoot), countBackAffordances = true).length,
            metrics.logicalFingerprintLength,
        )
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
        assertTrue(result.samples[0].identity != result.identity)
        assertTrue(result.samples.all { it.identity == null || it.identityChangedFromBefore })
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
        val knownFingerprint = identity(knownRoot)
        var now = 0L
        var captureIndex = 0

        val result = settler.settle(
            request = request(
                parentPackageName = "com.example.parent",
                expectedPackageName = "com.example.destination",
                knownDestinationIdentity = knownFingerprint,
                mode = DestinationSettleMode.ROUTE_REPLAY,
                timeProvider = { now },
                capture = {
                    now += 1_000L
                    listOf(firstRoot, knownRoot).getOrElse(captureIndex++) { knownRoot }
                },
            ),
        )

        assertEquals(knownRoot, result.root)
        assertEquals(knownFingerprint, result.identity)
        assertEquals(DestinationSettleStopReason.KNOWN_DESTINATION_FINGERPRINT_MATCHED, result.stopReason)
        assertEquals(DestinationSelectionReason.KNOWN_ROUTE_FINGERPRINT_MATCH, result.selectionReason)
        assertEquals(2, result.samples.size)
        assertTrue(result.elapsedMillis < 3_000L)
    }

    /**
     * S5 — ROUTE_REPLAY's known-destination match must resolve through the CALLER's policy.
     *
     * Round-5 D2: replacing that policy call with a raw `==` on identities kept the whole suite
     * green, because no test gave the known and live destinations a difference the policy is meant
     * to tolerate. These two do.
     *
     * Here the caller's policy ignores back affordances (its parent is the crawl root). The recorded
     * destination had none; the live one has grown one. It is still the known destination.
     */
    @Test
    fun routeReplay_knownDestination_ignores_a_back_affordance_when_the_policy_does() = runBlocking {
        var now = 0L
        val recorded = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(pressableNode("Known destination", childIndex = 0)),
        )
        val live = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Known destination", childIndex = 0),
                pressableNode("Navigate up", childIndex = 1).copy(bounds = "[0,0][120,120]"),
            ),
        )

        val result = DestinationSettler().settle(
            request(
                parentPackageName = "com.example.parent",
                expectedPackageName = "com.example.destination",
                knownDestinationIdentity = identity(recorded),
                mode = DestinationSettleMode.ROUTE_REPLAY,
                sameScreenPolicy = SameScreenPolicy(countBackAffordances = false),
                timeProvider = { now },
                capture = {
                    now += 1_000L
                    live
                },
            ),
        )

        assertEquals(DestinationSettleStopReason.KNOWN_DESTINATION_FINGERPRINT_MATCHED, result.stopReason)
    }

    /**
     * S5 again, with a policy that COUNTS back affordances: a row whose resource id merely contains
     * "back" drifts across the 300px band. Position never decided identity, so the live screen is
     * still the known destination (round-4 N1, at the settler rather than at S4).
     */
    @Test
    fun routeReplay_knownDestination_survives_a_back_signal_row_crossing_the_band() = runBlocking {
        var now = 0L
        fun destinationWithFeedbackAt(top: Int) = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Known destination", childIndex = 0),
                pressableNode("Send feedback", childIndex = 1).copy(bounds = "[0,$top][600,${top + 60}]"),
            ),
        )

        val result = DestinationSettler().settle(
            request(
                parentPackageName = "com.example.parent",
                expectedPackageName = "com.example.destination",
                knownDestinationIdentity = identity(destinationWithFeedbackAt(top = 280)),
                mode = DestinationSettleMode.ROUTE_REPLAY,
                sameScreenPolicy = SameScreenPolicy(countBackAffordances = true),
                timeProvider = { now },
                capture = {
                    now += 1_000L
                    destinationWithFeedbackAt(top = 320)
                },
            ),
        )

        assertEquals(DestinationSettleStopReason.KNOWN_DESTINATION_FINGERPRINT_MATCHED, result.stopReason)
    }

    /**
     * Round-6 D5 (S6): "did anything change since the click?" resolves through the caller's policy.
     * The sample differs from the before-click screen only by a back affordance, and the policy
     * ignores back affordances, so nothing changed. A raw `!=` would call it changed.
     */
    @Test
    fun changedFromBefore_ignores_a_back_affordance_when_the_policy_does() = runBlocking {
        var now = 0L
        val beforeClickPlusBack = rootSnapshot(
            packageName = "com.example.parent",
            children = listOf(
                pressableNode("Open destination", childIndex = 0),
                pressableNode("Navigate up", childIndex = 1).copy(bounds = "[0,0][120,120]"),
            ),
        )

        val result = DestinationSettler().settle(
            request(
                parentPackageName = "com.example.parent",
                mode = DestinationSettleMode.DISCOVERY,
                sameScreenPolicy = SameScreenPolicy(countBackAffordances = false),
                timeProvider = { now },
                capture = {
                    now += 4_000L
                    beforeClickPlusBack
                },
            ),
        )

        assertFalse(result.samples.first().identityChangedFromBefore)
    }

    /**
     * Round-6 D5 (richness input): the settler measures the logical encoding narrowed by the
     * caller's OWN policy. With a policy that ignores back affordances, the back element must not
     * be measured — forcing the flag to `true` lengthens the string and can move sample selection.
     */
    @Test
    fun settle_measures_richness_through_the_callers_back_affordance_setting() = runBlocking {
        var now = 0L
        val destination = rootSnapshot(
            packageName = "com.example.destination",
            children = listOf(
                pressableNode("Detailed option", childIndex = 0),
                pressableNode("Navigate up", childIndex = 1).copy(bounds = "[0,0][120,120]"),
            ),
        )

        val result = DestinationSettler().settle(
            request(
                parentPackageName = "com.example.parent",
                mode = DestinationSettleMode.DISCOVERY,
                sameScreenPolicy = SameScreenPolicy(countBackAffordances = false),
                timeProvider = { now },
                capture = {
                    now += 4_000L
                    destination
                },
            ),
        )

        val measured = result.samples.first().metrics!!.logicalFingerprintLength
        assertEquals(
            ScreenIdentityCodec.encodeLogical(identity(destination), countBackAffordances = false).length,
            measured,
        )
        assertTrue(
            ScreenIdentityCodec.encodeLogical(identity(destination), countBackAffordances = true).length > measured,
        )
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
        assertEquals(3_000L, result.elapsedMillis)
        assertEquals(3, result.samples.size)
        assertTrue(result.samples.drop(1).all { it.sameIdentityAsPrevious })
    }

    private suspend fun settleWithCaptures(
        captures: List<AccessibilityNodeSnapshot>,
        parentPackageName: String,
        maxSettleMillis: Long = 3_000L,
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
        knownDestinationIdentity: ScreenIdentity? = null,
        mode: DestinationSettleMode = DestinationSettleMode.DISCOVERY,
        sameScreenPolicy: SameScreenPolicy = SameScreenPolicy(countBackAffordances = true),
        timeProvider: () -> Long,
        maxSettleMillis: Long = 3_000L,
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
            beforeClickIdentity = identity(parentRoot),
            topIdentity = identity(topRoot),
            knownDestinationIdentity = knownDestinationIdentity,
            mode = mode,
            sameScreenPolicy = sameScreenPolicy,
            identity = ::identity,
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

    private fun identity(root: AccessibilityNodeSnapshot): ScreenIdentity {
        return ScreenIdentity.fromRoot(root)
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
