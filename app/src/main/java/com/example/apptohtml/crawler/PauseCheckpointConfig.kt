package com.example.apptohtml.crawler

data class PauseCheckpointConfig(
    val initialTimeThresholdMs: Long = 30L * 60 * 1_000,
    val subsequentTimeThresholdMs: Long = 15L * 60 * 1_000,
    val initialFailedEdgeThreshold: Int = 30,
    val subsequentFailedEdgeThreshold: Int = 15,
)

enum class PauseReason {
    ELAPSED_TIME_EXCEEDED,
    FAILED_EDGE_COUNT_EXCEEDED,

    /** A screen's identity needs an operator; the case says which and why ([IdentityCase]). */
    IDENTITY_CASE,
}

data class PauseProgressSnapshot(
    val elapsedTimeMs: Long,
    val capturedScreenCount: Int,
    val capturedChildScreenCount: Int,
    val failedEdgeCount: Int,
)

enum class PauseDecision {
    CONTINUE,
    STOP,
}
