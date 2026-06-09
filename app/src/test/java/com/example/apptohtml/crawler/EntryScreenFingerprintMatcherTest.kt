package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryScreenFingerprintMatcherTest {
    @Test
    fun match_returnsNoExpectedFingerprint_whenExpectedIsNull() {
        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = null,
            observedRoot = rootWithRows("Apps"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.NO_EXPECTED_FINGERPRINT, result.reason)
    }

    @Test
    fun match_acceptsExactFingerprintFastPath() {
        val observed = rootWithRows("Apps", "Google")
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = observed,
            targetPackageName = TARGET_PACKAGE,
        )

        assertTrue(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.EXACT_FINGERPRINT_MATCH, result.reason)
        assertDoubleEquals(1.0, result.diceSimilarity)
    }

    @Test
    fun match_rejectsMalformedExpectedFingerprint() {
        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = "not-a-replay-fingerprint",
            observedRoot = rootWithRows("Apps"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.DECODE_FAILED, result.reason)
    }

    @Test
    fun match_rejectsDifferentRootClass() {
        val expected = replayFingerprint(rootClass = "android.widget.LinearLayout", labels = listOf("Apps"))

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.ROOT_CLASS_MISMATCH, result.reason)
    }

    @Test
    fun match_rejectsDifferentTargetPackage_evenWhenFingerprintIsExact() {
        val expected = replayFingerprint("Apps")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps", packageName = "com.example.other"),
            targetPackageName = TARGET_PACKAGE,
            observedFingerprint = expected,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.UNRELATED_ENTRY_IDENTITIES, result.reason)
    }

    @Test
    fun match_rejectsEmptyIdentitySetUnlessExactFastPathAlreadyMatched() {
        val expected = replayFingerprint(labels = emptyList())

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.EMPTY_IDENTITY_SET, result.reason)
        assertEquals(0, result.expectedIdentityCount)
        assertEquals(1, result.observedIdentityCount)
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

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows(
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
            ),
            targetPackageName = TARGET_PACKAGE,
        )

        assertTrue(result.compatible)
        assertEquals(
            EntryScreenFingerprintMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.reason,
        )
        assertEquals(9, result.expectedIdentityCount)
        assertEquals(10, result.observedIdentityCount)
        assertEquals(9, result.overlapCount)
        assertDoubleEquals(1.0, result.expectedCoverage)
        assertDoubleEquals(0.9, result.observedCoverage)
        assertDoubleEquals(18.0 / 19.0, result.diceSimilarity)
    }

    @Test
    fun match_acceptsObservedEntryWhenExtraRowDisplacesOneExpectedRow() {
        val expected = replayFingerprint("Apps", "Connected devices", "Display")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps", "Connected devices", "Storage"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertTrue(result.compatible)
        assertEquals(
            EntryScreenFingerprintMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
            result.reason,
        )
        assertEquals(3, result.expectedIdentityCount)
        assertEquals(3, result.observedIdentityCount)
        assertEquals(2, result.overlapCount)
        assertDoubleEquals(2.0 / 3.0, result.expectedCoverage)
        assertDoubleEquals(2.0 / 3.0, result.observedCoverage)
        assertDoubleEquals(2.0 / 3.0, result.diceSimilarity)
    }

    @Test
    fun match_acceptsSparseEntryWithOneExtraRow() {
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps", "Google", "Storage"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertTrue(result.compatible)
        assertEquals(
            EntryScreenFingerprintMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.reason,
        )
        assertEquals(2, result.expectedIdentityCount)
        assertEquals(3, result.observedIdentityCount)
        assertEquals(2, result.overlapCount)
        assertDoubleEquals(0.8, result.diceSimilarity)
    }

    @Test
    fun match_rejectsBelowOverlapThreshold() {
        val expected = replayFingerprint("Apps", "Connected devices", "Display", "Google")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Apps", "Storage", "Battery", "Security"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.IDENTITY_OVERLAP_TOO_LOW, result.reason)
        assertEquals(1, result.overlapCount)
        assertDoubleEquals(0.25, result.expectedCoverage)
        assertDoubleEquals(0.25, result.observedCoverage)
        assertDoubleEquals(0.25, result.diceSimilarity)
    }

    @Test
    fun match_rejectsUnrelatedEntryIdentities() {
        val expected = replayFingerprint("Apps", "Google")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows("Storage", "Battery"),
            targetPackageName = TARGET_PACKAGE,
        )

        assertFalse(result.compatible)
        assertEquals(EntryScreenFingerprintMatchReason.UNRELATED_ENTRY_IDENTITIES, result.reason)
        assertEquals(0, result.overlapCount)
    }

    @Test
    fun match_excludesTopBackAffordanceFromObservedIdentities() {
        val expected = replayFingerprint("Apps")

        val result = EntryScreenFingerprintMatcher.match(
            expectedFingerprint = expected,
            observedRoot = rootWithRows(
                "Apps",
                nodes = listOf(backAffordanceNode(childIndex = 1)),
            ),
            targetPackageName = TARGET_PACKAGE,
            observedFingerprint = "force-compatibility-path",
        )

        assertTrue(result.compatible)
        assertEquals(
            EntryScreenFingerprintMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
            result.reason,
        )
        assertEquals(1, result.expectedIdentityCount)
        assertEquals(1, result.observedIdentityCount)
        assertEquals(1, result.overlapCount)
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
    ): String = replayFingerprint(rootClass = rootClass, labels = labels.toList())

    private fun replayFingerprint(
        rootClass: String = ROOT_CLASS,
        labels: List<String>,
    ): String {
        return ReplayFingerprintCodec.encode(
            rootClass = rootClass,
            elements = labels.map { label ->
                val fields = ElementFingerprint.ofFields(
                    label = label,
                    resourceId = "$TARGET_PACKAGE:id/${label.resourceSuffix()}",
                    className = "android.widget.Button",
                    isListItem = false,
                    checkable = false,
                    editable = false,
                ).encoded.split('|')
                ReplayFingerprintCodec.ElementFields(
                    resourceId = fields[0],
                    label = fields[1],
                    className = fields[2],
                    isListItem = fields[3],
                    checkable = fields[4],
                    editable = fields[5],
                )
            }.sortedBy { it.label },
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
