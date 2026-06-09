package com.example.apptohtml.crawler

internal data class EntryScreenFingerprintMatch(
    val compatible: Boolean,
    val reason: EntryScreenFingerprintMatchReason,
    val expectedIdentityCount: Int,
    val observedIdentityCount: Int,
    val overlapCount: Int,
    val expectedCoverage: Double,
    val observedCoverage: Double,
    val diceSimilarity: Double,
)

internal enum class EntryScreenFingerprintMatchReason {
    NO_EXPECTED_FINGERPRINT,
    EXACT_FINGERPRINT_MATCH,
    DECODE_FAILED,
    ROOT_CLASS_MISMATCH,
    EMPTY_IDENTITY_SET,
    ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
    IDENTITY_OVERLAP_THRESHOLD_MET,
    IDENTITY_OVERLAP_TOO_LOW,
    UNRELATED_ENTRY_IDENTITIES,
}

internal object EntryScreenFingerprintMatcher {
    private const val DICE_SIMILARITY_THRESHOLD = 2.0 / 3.0

    fun match(
        expectedFingerprint: String?,
        observedRoot: AccessibilityNodeSnapshot,
        targetPackageName: String,
        observedFingerprint: String = buildObservedFingerprint(observedRoot),
    ): EntryScreenFingerprintMatch {
        if (expectedFingerprint == null) {
            return noExpectedFingerprintMatch()
        }
        if (observedRoot.packageName != targetPackageName) {
            return unrelatedEntryIdentitiesMatch()
        }
        if (observedFingerprint == expectedFingerprint) {
            return exactFingerprintMatch()
        }

        val expectedFields = ReplayFingerprintCodec.decode(expectedFingerprint)
            ?: return decodeFailedMatch()
        if (expectedFields.rootClass != observedRoot.className.orEmpty()) {
            return rootClassMismatchMatch()
        }

        val expectedIdentities = expectedFields.elements
            .map { it.encodedIdentity }
            .toSet()
        val observedIdentities = observedRoot.entryIdentitySet()
        if (expectedIdentities.isEmpty() || observedIdentities.isEmpty()) {
            return emptyIdentitySetMatch(
                expectedIdentityCount = expectedIdentities.size,
                observedIdentityCount = observedIdentities.size,
            )
        }

        val overlapCount = expectedIdentities.intersect(observedIdentities).size
        val expectedCoverage = overlapCount.toDouble() / expectedIdentities.size
        val observedCoverage = overlapCount.toDouble() / observedIdentities.size
        val diceSimilarity = (2.0 * overlapCount) / (expectedIdentities.size + observedIdentities.size)

        val reason = when {
            overlapCount == 0 -> EntryScreenFingerprintMatchReason.UNRELATED_ENTRY_IDENTITIES
            observedIdentities.containsAll(expectedIdentities) &&
                observedIdentities.size >= expectedIdentities.size ->
                EntryScreenFingerprintMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES
            diceSimilarity >= DICE_SIMILARITY_THRESHOLD ->
                EntryScreenFingerprintMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET
            else -> EntryScreenFingerprintMatchReason.IDENTITY_OVERLAP_TOO_LOW
        }

        return EntryScreenFingerprintMatch(
            compatible = reason == EntryScreenFingerprintMatchReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES ||
                reason == EntryScreenFingerprintMatchReason.IDENTITY_OVERLAP_THRESHOLD_MET,
            reason = reason,
            expectedIdentityCount = expectedIdentities.size,
            observedIdentityCount = observedIdentities.size,
            overlapCount = overlapCount,
            expectedCoverage = expectedCoverage,
            observedCoverage = observedCoverage,
            diceSimilarity = diceSimilarity,
        )
    }

    private fun buildObservedFingerprint(root: AccessibilityNodeSnapshot): String {
        return ReplayFingerprintCodec.encode(
            rootClass = root.className.orEmpty(),
            elements = root.entryIdentitySet()
                .sorted()
                .map { encoded ->
                    val fields = encoded.split('|')
                    ReplayFingerprintCodec.ElementFields(
                        resourceId = fields[0],
                        label = fields[1],
                        className = fields[2],
                        isListItem = fields[3],
                        checkable = fields[4],
                        editable = fields[5],
                    )
                },
        )
    }

    private fun AccessibilityNodeSnapshot.entryIdentitySet(): Set<String> {
        return AccessibilityTreeSnapshotter.collectPressableElements(this)
            .filterNot(::looksLikeEntryBackAffordance)
            .map { ElementFingerprint.of(it).encoded }
            .toSet()
    }

    private fun looksLikeEntryBackAffordance(element: PressableElement): Boolean {
        val normalizedLabel = normalizeFingerprintToken(element.label)
        val normalizedResourceId = normalizeFingerprintToken(element.resourceId)
        val hasBackSignal = normalizedResourceId.contains("back") ||
            normalizedResourceId.contains("navigate up") ||
            normalizedResourceId.contains("navigateup") ||
            normalizedResourceId.contains("up button") ||
            normalizedResourceId.contains("upbutton") ||
            normalizedResourceId.contains("nav button") ||
            normalizedResourceId.contains("navbutton") ||
            normalizedLabel == "navigate up" ||
            normalizedLabel == "go back" ||
            normalizedLabel == "navigate back" ||
            normalizedLabel == "back" ||
            normalizedLabel == "up"
        if (!hasBackSignal) {
            return false
        }

        val top = parseFingerprintBounds(element.bounds)?.top ?: return false
        return top <= 300
    }

    private fun normalizeFingerprintToken(value: String?): String {
        return value
            ?.substringAfterLast('/')
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun parseFingerprintBounds(bounds: String): FingerprintBounds? {
        val match = BOUNDS_REGEX.matchEntire(bounds) ?: return null
        return FingerprintBounds(top = match.groupValues[2].toInt())
    }

    private val ReplayFingerprintCodec.ElementFields.encodedIdentity: String
        get() = listOf(
            resourceId,
            label,
            className,
            isListItem,
            checkable,
            editable,
        ).joinToString("|")

    private fun noExpectedFingerprintMatch() = EntryScreenFingerprintMatch(
        compatible = false,
        reason = EntryScreenFingerprintMatchReason.NO_EXPECTED_FINGERPRINT,
        expectedIdentityCount = 0,
        observedIdentityCount = 0,
        overlapCount = 0,
        expectedCoverage = 0.0,
        observedCoverage = 0.0,
        diceSimilarity = 0.0,
    )

    private fun exactFingerprintMatch() = EntryScreenFingerprintMatch(
        compatible = true,
        reason = EntryScreenFingerprintMatchReason.EXACT_FINGERPRINT_MATCH,
        expectedIdentityCount = 0,
        observedIdentityCount = 0,
        overlapCount = 0,
        expectedCoverage = 1.0,
        observedCoverage = 1.0,
        diceSimilarity = 1.0,
    )

    private fun decodeFailedMatch() = EntryScreenFingerprintMatch(
        compatible = false,
        reason = EntryScreenFingerprintMatchReason.DECODE_FAILED,
        expectedIdentityCount = 0,
        observedIdentityCount = 0,
        overlapCount = 0,
        expectedCoverage = 0.0,
        observedCoverage = 0.0,
        diceSimilarity = 0.0,
    )

    private fun rootClassMismatchMatch() = EntryScreenFingerprintMatch(
        compatible = false,
        reason = EntryScreenFingerprintMatchReason.ROOT_CLASS_MISMATCH,
        expectedIdentityCount = 0,
        observedIdentityCount = 0,
        overlapCount = 0,
        expectedCoverage = 0.0,
        observedCoverage = 0.0,
        diceSimilarity = 0.0,
    )

    private fun emptyIdentitySetMatch(
        expectedIdentityCount: Int,
        observedIdentityCount: Int,
    ) = EntryScreenFingerprintMatch(
        compatible = false,
        reason = EntryScreenFingerprintMatchReason.EMPTY_IDENTITY_SET,
        expectedIdentityCount = expectedIdentityCount,
        observedIdentityCount = observedIdentityCount,
        overlapCount = 0,
        expectedCoverage = 0.0,
        observedCoverage = 0.0,
        diceSimilarity = 0.0,
    )

    private fun unrelatedEntryIdentitiesMatch() = EntryScreenFingerprintMatch(
        compatible = false,
        reason = EntryScreenFingerprintMatchReason.UNRELATED_ENTRY_IDENTITIES,
        expectedIdentityCount = 0,
        observedIdentityCount = 0,
        overlapCount = 0,
        expectedCoverage = 0.0,
        observedCoverage = 0.0,
        diceSimilarity = 0.0,
    )

    private data class FingerprintBounds(val top: Int)

    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
}
