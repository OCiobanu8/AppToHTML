package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry-restore tolerance rules, carried over from the deleted `EntryScreenFingerprintMatcher`
 * when its algorithm became [EntryRestorePolicy]. Same fixtures, same expected outcomes; only the
 * call shape and the expected-value construction changed.
 */
class EntryRestorePolicyTest {
    @Test
    fun match_returnsNoExpectedFingerprint_whenExpectedIsNull() {
        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = null,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps")),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.NO_EXPECTED_FINGERPRINT, result.reason)
    }

    @Test
    fun match_acceptsExactFingerprintFastPath() {
        val observed = rootWithRows("Apps", "Google")
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(observed),
        )

        assertTrue(result.matched)
        assertEquals(ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH, result.reason)
        assertDoubleEquals(1.0, result.diceSimilarity)
    }

    // RETIRED: `match_rejectsMalformedExpectedFingerprint` pinned DECODE_FAILED, which existed
    // only because the expected value was a string that had to be parsed before it could be
    // compared. The expected value is now a ScreenIdentity, so there is no parse step and no
    // malformed input to reject. The behavior is removed, not weakened.

    @Test
    fun match_rejectsDifferentRootClass() {
        val expected = replayFingerprint(rootClass = "android.widget.LinearLayout", labels = listOf("Apps"))

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps")),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.ROOT_CLASS_MISMATCH, result.reason)
    }

    @Test
    fun match_rejectsDifferentTargetPackage_evenWhenFingerprintIsExact() {
        val expected = replayFingerprint("Apps")

        // Round-6 D1: at HEAD this test passed `observedFingerprint = expected`, forcing IDENTICAL
        // content in a foreign package so that only the package check could reject it. The first
        // port dropped that, so the element sets stopped overlapping and the test passed on the
        // overlap rule instead — the package check could be deleted with the suite green. Identical
        // content, different package, restores what the test is named for.
        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = expected.copy(packageName = "com.example.other"),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES, result.reason)
    }

    @Test
    fun match_rejectsEmptyIdentitySetUnlessExactFastPathAlreadyMatched() {
        val expected = replayFingerprint(labels = emptyList())

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps")),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.EMPTY_IDENTITY_SET, result.reason)
        assertEquals(0, result.expectedCount)
        assertEquals(1, result.observedCount)
    }

    @Test
    fun match_acceptsObservedEntryThatAddsOneExpectedCompatibleRow() {
        val expected = replayFingerprint(
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

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows(
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
            )),
        )

        assertTrue(result.matched)
        assertEquals(
            ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.reason,
        )
        assertEquals(9, result.expectedCount)
        assertEquals(10, result.observedCount)
        assertEquals(9, result.overlapCount)
        assertDoubleEquals(1.0, result.expectedCoverage)
        assertDoubleEquals(0.9, result.observedCoverage)
        assertDoubleEquals(18.0 / 19.0, result.diceSimilarity)
    }

    @Test
    fun match_acceptsObservedEntryWhenExtraRowDisplacesOneExpectedRow() {
        val expected = replayFingerprint("Apps", "Connected devices", "Display")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps", "Connected devices", "Storage")),
        )

        assertTrue(result.matched)
        assertEquals(
            ScreenIdentityMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
            result.reason,
        )
        assertEquals(3, result.expectedCount)
        assertEquals(3, result.observedCount)
        assertEquals(2, result.overlapCount)
        assertDoubleEquals(2.0 / 3.0, result.expectedCoverage)
        assertDoubleEquals(2.0 / 3.0, result.observedCoverage)
        assertDoubleEquals(2.0 / 3.0, result.diceSimilarity)
    }

    @Test
    fun match_acceptsSparseEntryWithOneExtraRow() {
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps", "Google", "Storage")),
        )

        assertTrue(result.matched)
        assertEquals(
            ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.reason,
        )
        assertEquals(2, result.expectedCount)
        assertEquals(3, result.observedCount)
        assertEquals(2, result.overlapCount)
        assertDoubleEquals(0.8, result.diceSimilarity)
    }

    @Test
    fun match_rejectsBelowOverlapThreshold() {
        val expected = replayFingerprint("Apps", "Connected devices", "Display", "Google")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps", "Storage", "Battery", "Security")),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.IDENTITY_OVERLAP_TOO_LOW, result.reason)
        assertEquals(1, result.overlapCount)
        assertDoubleEquals(0.25, result.expectedCoverage)
        assertDoubleEquals(0.25, result.observedCoverage)
        assertDoubleEquals(0.25, result.diceSimilarity)
    }

    @Test
    fun match_rejectsUnrelatedEntryIdentities() {
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows("Storage", "Battery")),
        )

        assertFalse(result.matched)
        assertEquals(ScreenIdentityMatchReason.UNRELATED_ENTRY_IDENTITIES, result.reason)
        assertEquals(0, result.overlapCount)
    }

    @Test
    fun match_excludesTopBackAffordanceFromObservedIdentities() {
        val expected = replayFingerprint("Apps")

        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = expected,
            observed = ScreenIdentity.fromRoot(rootWithRows(
                "Apps",
                nodes = listOf(backAffordanceNode(childIndex = 1)),
            )),
        )

        assertTrue(result.matched)
        // Reason sharpened, verdict unchanged. Pre-refactor this test passed
        // `observedFingerprint = "force-compatibility-path"`, an override that deliberately
        // defeated the byte-equality fast path so the set comparison ran and reported
        // ACTUAL_ENRICHES_EXPECTED_IDENTITIES. The policy has no such override — it compares sets
        // directly — so two back-filtered screens that are equal report EXACT. The count
        // assertions are dropped because the exact path carried no counts at HEAD either.
        assertEquals(ScreenIdentityMatchReason.EXACT_FINGERPRINT_MATCH, result.reason)
    }

    /**
     * Round-7 E30. Back affordances are excluded from the EXPECTED side too, not only the observed
     * one. HEAD pinned this symmetrically; the first port only put the back button on the observed
     * side, so the expected side could start counting it with the suite green.
     *
     * Expected {up, Apps} vs observed {Apps, Storage}: excluding `up` gives ENRICHES. Counting it
     * gives Dice 0.5 and a failed restore.
     */
    @Test
    fun match_excludesBackAffordanceFromTheExpectedSideToo() {
        val result = EntryRestorePolicy(TARGET_PACKAGE).compare(
            expected = ScreenIdentity.fromRoot(
                rootWithRows("Apps", nodes = listOf(backAffordanceNode(childIndex = 1))),
            ),
            observed = ScreenIdentity.fromRoot(rootWithRows("Apps", "Storage")),
        )

        assertTrue(result.matched)
        assertEquals(ScreenIdentityMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES, result.reason)
    }

    private fun rootWithRows(
        vararg labels: String,
        packageName: String = TARGET_PACKAGE,
        nodes: List<AccessibilityNodeSnapshot> = emptyList(),
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = ROOT_CLASS,
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
            children = labels.mapIndexed { index, label ->
                pressableNode(
                    label = label,
                    packageName = packageName,
                    childIndex = index,
                )
            } + nodes,
            childIndexPath = emptyList(),
        )
    }

    private fun pressableNode(
        label: String,
        packageName: String = TARGET_PACKAGE,
        childIndex: Int,
    ): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.Button",
            packageName = packageName,
            viewIdResourceName = "$packageName:id/${label.resourceSuffix()}",
            text = label,
            contentDescription = null,
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            checkable = false,
            checked = false,
            editable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,${200 + childIndex * 100}][100,${260 + childIndex * 100}]",
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    private fun backAffordanceNode(childIndex: Int): AccessibilityNodeSnapshot {
        return AccessibilityNodeSnapshot(
            className = "android.widget.ImageButton",
            packageName = TARGET_PACKAGE,
            viewIdResourceName = "$TARGET_PACKAGE:id/back_button",
            text = null,
            contentDescription = "Navigate up",
            clickable = true,
            supportsClickAction = true,
            scrollable = false,
            enabled = true,
            visibleToUser = true,
            bounds = "[0,0][120,120]",
            children = emptyList(),
            childIndexPath = listOf(childIndex),
        )
    }

    private fun replayFingerprint(
        vararg labels: String,
        rootClass: String = ROOT_CLASS,
    ): ScreenIdentity = replayFingerprint(rootClass = rootClass, labels = labels.toList())

    private fun replayFingerprint(
        rootClass: String = ROOT_CLASS,
        labels: List<String>,
    ): ScreenIdentity {
        return ScreenIdentity(
            packageName = TARGET_PACKAGE,
            rootClassName = rootClass,
            elements = labels.map { label ->
                ScreenElementIdentity(
                    fingerprint = ElementFingerprint.ofFields(
                        label = label,
                        resourceId = "$TARGET_PACKAGE:id/${label.resourceSuffix()}",
                        className = "android.widget.Button",
                        isListItem = false,
                        checkable = false,
                        editable = false,
                    ),
                    isBackAffordance = false,
                )
            }.toSet(),
        )
    }

    private fun String.resourceSuffix(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun assertDoubleEquals(expected: Double, actual: Double) {
        assertEquals(expected, actual, 0.0001)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        const val ROOT_CLASS = "android.widget.FrameLayout"
    }
}
