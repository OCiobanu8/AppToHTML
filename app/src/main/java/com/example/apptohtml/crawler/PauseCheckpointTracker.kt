package com.example.apptohtml.crawler

internal class PauseCheckpointTracker(
    private val config: PauseCheckpointConfig,
    private val startedAtMs: Long,
    private val timeProvider: () -> Long,
) {
    private var nextTimeBudgetMs = config.initialTimeThresholdMs
    private var nextFailedEdgeBudget = config.initialFailedEdgeThreshold
    private var failedEdgeCount = 0

    fun recordFailedEdge() {
        failedEdgeCount += 1
    }

    fun nextTriggeredReason(): PauseReason? {
        val elapsedTimeMs = elapsedTimeMs()
        return when {
            elapsedTimeMs >= nextTimeBudgetMs -> PauseReason.ELAPSED_TIME_EXCEEDED
            failedEdgeCount >= nextFailedEdgeBudget -> PauseReason.FAILED_EDGE_COUNT_EXCEEDED
            else -> null
        }
    }

    fun rollForwardAfterContinue(reason: PauseReason) {
        when (reason) {
            PauseReason.ELAPSED_TIME_EXCEEDED -> {
                nextTimeBudgetMs += config.subsequentTimeThresholdMs
            }

            PauseReason.FAILED_EDGE_COUNT_EXCEEDED -> {
                nextFailedEdgeBudget += config.subsequentFailedEdgeThreshold
            }

            PauseReason.EXTERNAL_PACKAGE_BOUNDARY -> Unit
        }
    }

    fun progressSnapshot(
        capturedScreenCount: Int,
        capturedChildScreenCount: Int,
    ): PauseProgressSnapshot {
        return PauseProgressSnapshot(
            elapsedTimeMs = elapsedTimeMs(),
            capturedScreenCount = capturedScreenCount,
            capturedChildScreenCount = capturedChildScreenCount,
            failedEdgeCount = failedEdgeCount,
        )
    }

    private fun elapsedTimeMs(): Long {
        return (timeProvider() - startedAtMs).coerceAtLeast(0L)
    }
}
