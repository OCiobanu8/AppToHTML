package com.example.apptohtml.crawler

internal class DestinationSettler {
    suspend fun settle(request: DestinationSettleRequest): DestinationSettleResult {
        val startedAt = request.timeProvider()
        val samples = mutableListOf<DestinationSample>()
        var attemptNumber = 0
        var previousFingerprint: String? = null
        var firstEligibleSample: DestinationSample? = null
        var lastEligibleSample: DestinationSample? = null
        var bestSample: DestinationSample? = null

        while (true) {
            attemptNumber += 1
            val root = request.capture(request.expectedPackageName)
            val elapsedMillis = request.timeProvider() - startedAt
            var sample = buildSample(
                request = request,
                root = root,
                attemptNumber = attemptNumber,
                elapsedMillis = elapsedMillis,
                previousFingerprint = previousFingerprint,
            )

            if (sample.fingerprint != null) {
                previousFingerprint = sample.fingerprint
            }

            if (sample.eligible) {
                val becameCurrentBest = sampleBecomesCurrentBest(
                    currentBest = bestSample,
                    candidate = sample,
                )
                if (becameCurrentBest) {
                    sample = sample.copy(becameCurrentBest = true)
                }
            }

            samples += sample

            if (sample.eligible) {
                if (firstEligibleSample == null) {
                    firstEligibleSample = sample
                }
                lastEligibleSample = sample

                val matchesKnownDestination = request.mode == DestinationSettleMode.ROUTE_REPLAY &&
                    request.knownDestinationFingerprint != null &&
                    sample.fingerprint == request.knownDestinationFingerprint
                if (matchesKnownDestination) {
                    return resultForSample(
                        sample = sample,
                        samples = samples,
                        stopReason = DestinationSettleStopReason.KNOWN_DESTINATION_FINGERPRINT_MATCHED,
                        selectionReason = DestinationSelectionReason.KNOWN_ROUTE_FINGERPRINT_MATCH,
                        elapsedMillis = elapsedMillis,
                    )
                }

                if (sample.becameCurrentBest) {
                    bestSample = sample
                }
            }

            if (elapsedMillis >= request.maxSettleMillis) {
                val selectedSample = bestSample
                return if (selectedSample != null) {
                    resultForSample(
                        sample = selectedSample,
                        samples = samples,
                        stopReason = DestinationSettleStopReason.FIXED_DWELL_EXHAUSTED,
                        selectionReason = selectionReason(
                            selectedSample = selectedSample,
                            firstEligibleSample = firstEligibleSample,
                            lastEligibleSample = lastEligibleSample,
                        ),
                        elapsedMillis = elapsedMillis,
                    )
                } else {
                    DestinationSettleResult(
                        root = null,
                        fingerprint = null,
                        packageName = null,
                        samples = samples.toList(),
                        stopReason = DestinationSettleStopReason.NO_ELIGIBLE_SAMPLE,
                        elapsedMillis = elapsedMillis,
                        selectionReason = null,
                    )
                }
            }
        }
    }

    private fun buildSample(
        request: DestinationSettleRequest,
        root: AccessibilityNodeSnapshot?,
        attemptNumber: Int,
        elapsedMillis: Long,
        previousFingerprint: String?,
    ): DestinationSample {
        if (root == null) {
            return DestinationSample(
                attemptNumber = attemptNumber,
                elapsedMillis = elapsedMillis,
                root = null,
                packageName = null,
                fingerprint = null,
                metrics = null,
                expectedPackageMatched = false,
                packageChanged = false,
                fingerprintChangedFromBefore = false,
                fingerprintChangedFromTop = false,
                sameFingerprintAsPrevious = false,
                eligible = false,
                eligibilityReason = DestinationEligibilityReason.NULL_CAPTURE,
                becameCurrentBest = false,
            )
        }

        val fingerprint = request.fingerprint(root)
        val metrics = DestinationRichnessMetrics.from(root, fingerprint)
        val packageName = root.packageName
        val expectedPackageMatched = request.expectedPackageName == null ||
            packageName == request.expectedPackageName
        val packageChanged = packageName != null && packageName != request.parentPackageName
        val fingerprintChangedFromBefore = fingerprint != request.beforeClickFingerprint
        val fingerprintChangedFromTop = fingerprint != request.topFingerprint
        val sameFingerprintAsPrevious = fingerprint == previousFingerprint
        val eligibilityReason = eligibilityReason(
            request = request,
            packageName = packageName,
            expectedPackageMatched = expectedPackageMatched,
            packageChanged = packageChanged,
            fingerprintChangedFromBefore = fingerprintChangedFromBefore,
            fingerprintChangedFromTop = fingerprintChangedFromTop,
        )

        return DestinationSample(
            attemptNumber = attemptNumber,
            elapsedMillis = elapsedMillis,
            root = root,
            packageName = packageName,
            fingerprint = fingerprint,
            metrics = metrics,
            expectedPackageMatched = expectedPackageMatched,
            packageChanged = packageChanged,
            fingerprintChangedFromBefore = fingerprintChangedFromBefore,
            fingerprintChangedFromTop = fingerprintChangedFromTop,
            sameFingerprintAsPrevious = sameFingerprintAsPrevious,
            eligible = eligibilityReason.isEligible,
            eligibilityReason = eligibilityReason,
            becameCurrentBest = false,
        )
    }

    fun compatibility(
        expectedRoot: AccessibilityNodeSnapshot,
        expectedFingerprint: String,
        expectedMetrics: DestinationRichnessMetrics,
        actualRoot: AccessibilityNodeSnapshot,
        actualFingerprint: String,
        actualMetrics: DestinationRichnessMetrics,
    ): DestinationCompatibilityResult {
        val expectedPackageName = expectedRoot.packageName
        val actualPackageName = actualRoot.packageName
        if (expectedPackageName == null || actualPackageName == null || expectedPackageName != actualPackageName) {
            return DestinationCompatibilityResult(
                isCompatible = false,
                reason = DestinationCompatibilityReason.PACKAGE_MISMATCH,
                expectedMetrics = expectedMetrics,
                actualMetrics = actualMetrics,
            )
        }

        if (expectedFingerprint == actualFingerprint) {
            return DestinationCompatibilityResult(
                isCompatible = true,
                reason = DestinationCompatibilityReason.EXACT_FINGERPRINT_MATCH,
                expectedMetrics = expectedMetrics,
                actualMetrics = actualMetrics,
            )
        }

        val expectedIdentities = meaningfulPressableIdentities(expectedRoot)
        val actualIdentities = meaningfulPressableIdentities(actualRoot)
        if (expectedIdentities.isNotEmpty() && actualIdentities.isNotEmpty()) {
            val expectedSubsetOfActual = actualIdentities.containsAll(expectedIdentities)
            val actualSubsetOfExpected = expectedIdentities.containsAll(actualIdentities)
            if (expectedSubsetOfActual && actualMetrics.richnessScore >= expectedMetrics.richnessScore) {
                return DestinationCompatibilityResult(
                    isCompatible = true,
                    reason = DestinationCompatibilityReason.ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
                    expectedMetrics = expectedMetrics,
                    actualMetrics = actualMetrics,
                )
            }
            if (actualSubsetOfExpected && expectedMetrics.richnessScore >= actualMetrics.richnessScore) {
                return DestinationCompatibilityResult(
                    isCompatible = true,
                    reason = DestinationCompatibilityReason.ACTUAL_IS_COMPATIBLE_SUBSET,
                    expectedMetrics = expectedMetrics,
                    actualMetrics = actualMetrics,
                )
            }

            val overlappingIdentityCount = expectedIdentities.intersect(actualIdentities).size
            if (
                expectedLooksSparseForCompatibility(expectedMetrics) &&
                overlappingIdentityCount > 0 &&
                actualMetrics.richnessScore >= expectedMetrics.richnessScore
            ) {
                return DestinationCompatibilityResult(
                    isCompatible = true,
                    reason = DestinationCompatibilityReason.SPARSE_EXPECTED_IDENTITY_OVERLAP,
                    expectedMetrics = expectedMetrics,
                    actualMetrics = actualMetrics,
                )
            }
        }

        if (
            expectedIdentities.isEmpty() &&
            expectedLooksSparseForCompatibility(expectedMetrics) &&
            expectedRoot.className == actualRoot.className &&
            actualMetrics.richnessScore >= expectedMetrics.richnessScore
        ) {
            return DestinationCompatibilityResult(
                isCompatible = true,
                reason = DestinationCompatibilityReason.SPARSE_EXPECTED_ROOT_CLASS_MATCH,
                expectedMetrics = expectedMetrics,
                actualMetrics = actualMetrics,
            )
        }

        return DestinationCompatibilityResult(
            isCompatible = false,
            reason = DestinationCompatibilityReason.UNRELATED_DESTINATION_IDENTITIES,
            expectedMetrics = expectedMetrics,
            actualMetrics = actualMetrics,
        )
    }

    private fun eligibilityReason(
        request: DestinationSettleRequest,
        packageName: String?,
        expectedPackageMatched: Boolean,
        packageChanged: Boolean,
        fingerprintChangedFromBefore: Boolean,
        fingerprintChangedFromTop: Boolean,
    ): DestinationEligibilityReason {
        if (request.expectedPackageName != null) {
            return if (expectedPackageMatched) {
                DestinationEligibilityReason.EXPECTED_PACKAGE_MATCHED
            } else {
                DestinationEligibilityReason.EXPECTED_PACKAGE_MISMATCH
            }
        }

        if (packageChanged) {
            return DestinationEligibilityReason.PACKAGE_CHANGED
        }

        return if (fingerprintChangedFromBefore && fingerprintChangedFromTop) {
            DestinationEligibilityReason.FINGERPRINT_CHANGED
        } else {
            DestinationEligibilityReason.UNCHANGED_FROM_PARENT
        }
    }

    private fun sampleBecomesCurrentBest(
        currentBest: DestinationSample?,
        candidate: DestinationSample,
    ): Boolean {
        val bestMetrics = currentBest?.metrics ?: return true
        val candidateMetrics = candidate.metrics ?: return false
        return when {
            candidateMetrics.richnessScore > bestMetrics.richnessScore -> true
            candidateMetrics.richnessScore < bestMetrics.richnessScore -> false
            candidate.attemptNumber > currentBest.attemptNumber -> true
            else -> false
        }
    }

    private fun selectionReason(
        selectedSample: DestinationSample,
        firstEligibleSample: DestinationSample?,
        lastEligibleSample: DestinationSample?,
    ): DestinationSelectionReason {
        val selectedScore = selectedSample.metrics?.richnessScore
        val firstScore = firstEligibleSample?.metrics?.richnessScore
        return when {
            selectedScore != null && firstScore != null && selectedScore > firstScore ->
                DestinationSelectionReason.BEST_RICHNESS

            selectedSample == lastEligibleSample && selectedSample != firstEligibleSample ->
                DestinationSelectionReason.FINAL_AVAILABLE_SAMPLE

            selectedSample != firstEligibleSample ->
                DestinationSelectionReason.BEST_RICHNESS

            else ->
                DestinationSelectionReason.FIRST_ELIGIBLE_FALLBACK
        }
    }

    private fun resultForSample(
        sample: DestinationSample,
        samples: List<DestinationSample>,
        stopReason: DestinationSettleStopReason,
        selectionReason: DestinationSelectionReason,
        elapsedMillis: Long,
    ): DestinationSettleResult {
        return DestinationSettleResult(
            root = sample.root,
            fingerprint = sample.fingerprint,
            packageName = sample.packageName,
            samples = samples.toList(),
            stopReason = stopReason,
            elapsedMillis = elapsedMillis,
            selectionReason = selectionReason,
        )
    }

    private fun meaningfulPressableIdentities(root: AccessibilityNodeSnapshot): Set<String> {
        return AccessibilityTreeSnapshotter.collectPressableElements(root)
            .mapNotNull(::meaningfulPressableIdentity)
            .toSet()
    }

    private fun meaningfulPressableIdentity(element: PressableElement): String? {
        val label = normalizeCompatibilityToken(element.label)
        val resourceId = normalizeCompatibilityToken(element.resourceId)
        if (label.isEmpty() && resourceId.isEmpty()) {
            return null
        }
        return buildList {
            add(label)
            add(resourceId)
            add(normalizeCompatibilityToken(element.className))
            add(element.isListItem.toString())
            add(element.checkable.toString())
            add(element.checked.toString())
            add(element.editable.toString())
        }.joinToString("|")
    }

    private fun normalizeCompatibilityToken(value: String?): String {
        return value
            ?.trim()
            ?.lowercase()
            .orEmpty()
    }

    private fun expectedLooksSparseForCompatibility(metrics: DestinationRichnessMetrics): Boolean {
        return metrics.distinctPressableCount <= 1 &&
            metrics.visibleTextOrContentDescriptionCount <= 1
    }
}

internal data class DestinationSettleRequest(
    val parentPackageName: String,
    val expectedPackageName: String? = null,
    val beforeClickFingerprint: String,
    val topFingerprint: String,
    val knownDestinationFingerprint: String? = null,
    val mode: DestinationSettleMode,
    val fingerprint: (AccessibilityNodeSnapshot) -> String,
    val capture: suspend (String?) -> AccessibilityNodeSnapshot?,
    val timeProvider: () -> Long,
    val maxSettleMillis: Long = 3_000L,
)

internal data class DestinationSettleResult(
    val root: AccessibilityNodeSnapshot?,
    val fingerprint: String?,
    val packageName: String?,
    val samples: List<DestinationSample>,
    val stopReason: DestinationSettleStopReason,
    val elapsedMillis: Long,
    val selectionReason: DestinationSelectionReason?,
)

internal data class DestinationSample(
    val attemptNumber: Int,
    val elapsedMillis: Long,
    val root: AccessibilityNodeSnapshot?,
    val packageName: String?,
    val fingerprint: String?,
    val metrics: DestinationRichnessMetrics?,
    val expectedPackageMatched: Boolean,
    val packageChanged: Boolean,
    val fingerprintChangedFromBefore: Boolean,
    val fingerprintChangedFromTop: Boolean,
    val sameFingerprintAsPrevious: Boolean,
    val eligible: Boolean,
    val eligibilityReason: DestinationEligibilityReason,
    val becameCurrentBest: Boolean,
)

internal data class DestinationCompatibilityResult(
    val isCompatible: Boolean,
    val reason: DestinationCompatibilityReason,
    val expectedMetrics: DestinationRichnessMetrics,
    val actualMetrics: DestinationRichnessMetrics,
)

internal data class DestinationRichnessMetrics(
    val visibleNodeCount: Int,
    val visibleTextOrContentDescriptionCount: Int,
    val visibleTextOrContentDescriptionCharacterCount: Int,
    val distinctPressableCount: Int,
    val nonEmptyPressableLabelCount: Int,
    val logicalFingerprintLength: Int,
    val scrollableNodeCount: Int,
    val progressIndicatorCount: Int,
) {
    val richnessScore: Int =
        visibleNodeCount +
            visibleTextOrContentDescriptionCount * 3 +
            visibleTextOrContentDescriptionCharacterCount / 4 +
            distinctPressableCount * 10 +
            nonEmptyPressableLabelCount * 8 +
            logicalFingerprintLength / 20 +
            scrollableNodeCount * 2 -
            progressIndicatorCount * 5

    companion object {
        fun from(
            root: AccessibilityNodeSnapshot,
            logicalFingerprint: String,
        ): DestinationRichnessMetrics {
            val visibleNodes = flattenVisibleNodes(root)
            val textValues = visibleNodes.flatMap { node ->
                listOf(node.text, node.contentDescription)
                    .map { value -> value?.trim().orEmpty() }
                    .filter { value -> value.isNotEmpty() }
            }
            val distinctPressables = AccessibilityTreeSnapshotter.collectPressableElements(root)
                .distinctBy(::pressableIdentity)

            return DestinationRichnessMetrics(
                visibleNodeCount = visibleNodes.size,
                visibleTextOrContentDescriptionCount = textValues.size,
                visibleTextOrContentDescriptionCharacterCount = textValues.sumOf { it.length },
                distinctPressableCount = distinctPressables.size,
                nonEmptyPressableLabelCount = distinctPressables.count { it.label.isNotBlank() },
                logicalFingerprintLength = logicalFingerprint.length,
                scrollableNodeCount = visibleNodes.count { it.scrollable },
                progressIndicatorCount = visibleNodes.count(::looksLikeProgressIndicator),
            )
        }

        private fun flattenVisibleNodes(root: AccessibilityNodeSnapshot): List<AccessibilityNodeSnapshot> {
            val children = root.children.flatMap(::flattenVisibleNodes)
            return if (root.visibleToUser) {
                listOf(root) + children
            } else {
                children
            }
        }

        private fun pressableIdentity(element: PressableElement): String {
            return buildList {
                add(element.label)
                add(element.resourceId.orEmpty())
                add(element.className.orEmpty())
                add(element.isListItem.toString())
                add(element.checkable.toString())
                add(element.checked.toString())
                add(element.editable.toString())
            }.joinToString("|")
        }

        private fun looksLikeProgressIndicator(node: AccessibilityNodeSnapshot): Boolean {
            val className = node.className.orEmpty()
            return className.contains("ProgressBar") ||
                className.contains("ProgressIndicator") ||
                className.contains("Loading")
        }
    }
}

internal enum class DestinationSettleMode {
    DISCOVERY,
    ROUTE_REPLAY,
}

internal enum class DestinationSettleStopReason {
    FIXED_DWELL_EXHAUSTED,
    KNOWN_DESTINATION_FINGERPRINT_MATCHED,
    NO_ELIGIBLE_SAMPLE,
}

internal enum class DestinationSelectionReason {
    BEST_RICHNESS,
    FINAL_AVAILABLE_SAMPLE,
    FIRST_ELIGIBLE_FALLBACK,
    KNOWN_ROUTE_FINGERPRINT_MATCH,
}

internal enum class DestinationEligibilityReason(val isEligible: Boolean) {
    NULL_CAPTURE(false),
    EXPECTED_PACKAGE_MATCHED(true),
    EXPECTED_PACKAGE_MISMATCH(false),
    PACKAGE_CHANGED(true),
    FINGERPRINT_CHANGED(true),
    UNCHANGED_FROM_PARENT(false),
}

internal enum class DestinationCompatibilityReason {
    PACKAGE_MISMATCH,
    EXACT_FINGERPRINT_MATCH,
    ACTUAL_ENRICHES_EXPECTED_IDENTITIES,
    ACTUAL_IS_COMPATIBLE_SUBSET,
    SPARSE_EXPECTED_IDENTITY_OVERLAP,
    SPARSE_EXPECTED_ROOT_CLASS_MATCH,
    UNRELATED_DESTINATION_IDENTITIES,
}
