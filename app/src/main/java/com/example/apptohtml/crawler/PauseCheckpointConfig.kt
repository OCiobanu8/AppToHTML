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
    EXTERNAL_PACKAGE_BOUNDARY,
}

data class PauseProgressSnapshot(
    val elapsedTimeMs: Long,
    val capturedScreenCount: Int,
    val capturedChildScreenCount: Int,
    val failedEdgeCount: Int,
)

data class ExternalPackageDecisionContext(
    val currentPackageName: String,
    val nextPackageName: String,
    val parentScreenId: String,
    val parentScreenName: String,
    val triggerLabel: String,
)

enum class PauseDecision {
    CONTINUE,
    STOP,
    SKIP_EDGE,
}
