package com.example.apptohtml.crawler

internal class DestinationSettler {
    suspend fun settle(request: DestinationSettleRequest): DestinationSettleResult {
        val startedAt = request.timeProvider()
        val samples = mutableListOf<DestinationSample>()
        var attemptNumber = 0
        var previousIdentity: ScreenIdentity? = null
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
                previousIdentity = previousIdentity,
            )

            if (sample.identity != null) {
                previousIdentity = sample.identity
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

                // S5 — known-destination match, resolved through the caller's named policy.
                val matchesKnownDestination = request.mode == DestinationSettleMode.ROUTE_REPLAY &&
                    request.knownDestinationIdentity != null &&
                    sample.identity != null &&
                    request.sameScreenPolicy
                        .compare(request.knownDestinationIdentity, sample.identity)
                        .matched
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
                        identity = null,
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
        previousIdentity: ScreenIdentity?,
    ): DestinationSample {
        if (root == null) {
            return DestinationSample(
                attemptNumber = attemptNumber,
                elapsedMillis = elapsedMillis,
                root = null,
                packageName = null,
                identity = null,
                metrics = null,
                expectedPackageMatched = false,
                packageChanged = false,
                identityChangedFromBefore = false,
                identityChangedFromTop = false,
                sameIdentityAsPrevious = false,
                eligible = false,
                eligibilityReason = DestinationEligibilityReason.NULL_CAPTURE,
                becameCurrentBest = false,
            )
        }

        val identity = request.identity(root)
        // The richness metric measures the LOGICAL encoding, as it did before the structure
        // existed: its length feeds richnessScore, which selects the captured sample.
        val metrics = DestinationRichnessMetrics.from(
            root = root,
            logicalFingerprint = ScreenIdentityCodec.encodeLogical(
                identity = identity,
                countBackAffordances = request.sameScreenPolicy.countBackAffordances,
            ),
        )
        val packageName = root.packageName
        val expectedPackageMatched = request.expectedPackageName == null ||
            packageName == request.expectedPackageName
        val packageChanged = packageName != null && packageName != request.parentPackageName
        // S6 — "did anything change?", resolved through the same named policy.
        val policy = request.sameScreenPolicy
        val identityChangedFromBefore =
            !policy.compare(request.beforeClickIdentity, identity).matched
        val identityChangedFromTop = !policy.compare(request.topIdentity, identity).matched
        val sameIdentityAsPrevious =
            previousIdentity != null && policy.compare(previousIdentity, identity).matched
        val eligibilityReason = eligibilityReason(
            request = request,
            packageName = packageName,
            expectedPackageMatched = expectedPackageMatched,
            packageChanged = packageChanged,
            identityChangedFromBefore = identityChangedFromBefore,
            identityChangedFromTop = identityChangedFromTop,
        )

        return DestinationSample(
            attemptNumber = attemptNumber,
            elapsedMillis = elapsedMillis,
            root = root,
            packageName = packageName,
            identity = identity,
            metrics = metrics,
            expectedPackageMatched = expectedPackageMatched,
            packageChanged = packageChanged,
            identityChangedFromBefore = identityChangedFromBefore,
            identityChangedFromTop = identityChangedFromTop,
            sameIdentityAsPrevious = sameIdentityAsPrevious,
            eligible = eligibilityReason.isEligible,
            eligibilityReason = eligibilityReason,
            becameCurrentBest = false,
        )
    }

    private fun eligibilityReason(
        request: DestinationSettleRequest,
        packageName: String?,
        expectedPackageMatched: Boolean,
        packageChanged: Boolean,
        identityChangedFromBefore: Boolean,
        identityChangedFromTop: Boolean,
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

        return if (identityChangedFromBefore && identityChangedFromTop) {
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
            identity = sample.identity,
            packageName = sample.packageName,
            samples = samples.toList(),
            stopReason = stopReason,
            elapsedMillis = elapsedMillis,
            selectionReason = selectionReason,
        )
    }

}

internal data class DestinationSettleRequest(
    val parentPackageName: String,
    val expectedPackageName: String? = null,
    val beforeClickIdentity: ScreenIdentity,
    val topIdentity: ScreenIdentity,
    val knownDestinationIdentity: ScreenIdentity? = null,
    val mode: DestinationSettleMode,
    /** The policy S5 and S6 resolve through; the caller picks it, the settler does not guess. */
    val sameScreenPolicy: SameScreenPolicy,
    val identity: (AccessibilityNodeSnapshot) -> ScreenIdentity,
    val capture: suspend (String?) -> AccessibilityNodeSnapshot?,
    val timeProvider: () -> Long,
    val maxSettleMillis: Long = 3_000L,
)

internal data class DestinationSettleResult(
    val root: AccessibilityNodeSnapshot?,
    val identity: ScreenIdentity?,
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
    val identity: ScreenIdentity?,
    val metrics: DestinationRichnessMetrics?,
    val expectedPackageMatched: Boolean,
    val packageChanged: Boolean,
    val identityChangedFromBefore: Boolean,
    val identityChangedFromTop: Boolean,
    val sameIdentityAsPrevious: Boolean,
    val eligible: Boolean,
    val eligibilityReason: DestinationEligibilityReason,
    val becameCurrentBest: Boolean,
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

