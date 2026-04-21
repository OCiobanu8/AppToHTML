package com.example.apptohtml.crawler

import java.io.File
import java.util.Locale
import com.example.apptohtml.model.SelectedAppRef

enum class CrawlerPhase {
    IDLE,
    LAUNCHING,
    WAITING_FOR_TARGET_SCREEN,
    SCANNING_TARGET_SCREEN,
    TRAVERSING_CHILD_SCREENS,
    PAUSED_FOR_DECISION,
    CAPTURED,
    ABORTED,
    FAILED,
}

data class CrawlerUiState(
    val phase: CrawlerPhase = CrawlerPhase.IDLE,
    val selectedApp: SelectedAppRef? = null,
    val requestId: Long? = null,
    val statusMessage: String = "No capture started yet.",
    val pauseDecisionId: Long? = null,
    val pauseReason: PauseReason? = null,
    val pauseElapsedTimeMs: Long? = null,
    val pauseFailedEdgeCount: Int? = null,
    val pausedCapturedScreenCount: Int? = null,
    val pausedCapturedChildScreenCount: Int? = null,
    val pauseCurrentPackageName: String? = null,
    val pauseNextPackageName: String? = null,
    val pauseTriggerLabel: String? = null,
    val screenName: String? = null,
    val htmlPath: String? = null,
    val xmlPath: String? = null,
    val mergedXmlPath: String? = null,
    val crawlIndexPath: String? = null,
    val graphJsonPath: String? = null,
    val graphHtmlPath: String? = null,
    val scrollStepCount: Int? = null,
    val capturedScreenCount: Int? = null,
    val capturedChildScreenCount: Int? = null,
    val skippedElementCount: Int? = null,
    val maxDepthReached: Int? = null,
    val partialResult: Boolean = false,
    val failureMessage: String? = null,
) {
    fun withLaunching(requestId: Long, selectedApp: SelectedAppRef, alreadyRunning: Boolean): CrawlerUiState {
        val message = if (alreadyRunning) {
            "Target app appears to already be running. Bringing it to the foreground now."
        } else {
            "Launching the selected app."
        }
        return CrawlerUiState(
            phase = CrawlerPhase.LAUNCHING,
            selectedApp = selectedApp,
            requestId = requestId,
            statusMessage = message,
        )
    }

    fun withWaiting(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.WAITING_FOR_TARGET_SCREEN,
        statusMessage = message,
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        mergedXmlPath = null,
        crawlIndexPath = null,
        graphJsonPath = null,
        graphHtmlPath = null,
        scrollStepCount = null,
        failureMessage = null,
    )

    fun withScanning(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.SCANNING_TARGET_SCREEN,
        statusMessage = message,
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        mergedXmlPath = null,
        crawlIndexPath = null,
        graphJsonPath = null,
        graphHtmlPath = null,
        scrollStepCount = null,
        capturedScreenCount = null,
        capturedChildScreenCount = null,
        skippedElementCount = null,
        maxDepthReached = null,
        partialResult = false,
        failureMessage = null,
    )

    fun withTraversingChildren(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.TRAVERSING_CHILD_SCREENS,
        statusMessage = message,
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        failureMessage = null,
    )

    fun withPausedForDecision(
        decisionId: Long,
        reason: PauseReason,
        snapshot: PauseProgressSnapshot,
        externalPackageContext: ExternalPackageDecisionContext? = null,
    ): CrawlerUiState = copy(
        phase = CrawlerPhase.PAUSED_FOR_DECISION,
        statusMessage = pauseMessage(reason),
        pauseDecisionId = decisionId,
        pauseReason = reason,
        pauseElapsedTimeMs = snapshot.elapsedTimeMs,
        pauseFailedEdgeCount = snapshot.failedEdgeCount,
        pausedCapturedScreenCount = snapshot.capturedScreenCount,
        pausedCapturedChildScreenCount = snapshot.capturedChildScreenCount,
        pauseCurrentPackageName = externalPackageContext?.currentPackageName,
        pauseNextPackageName = externalPackageContext?.nextPackageName,
        pauseTriggerLabel = externalPackageContext?.triggerLabel,
        failureMessage = null,
    )

    fun withResumedFromDecision(): CrawlerUiState = copy(
        phase = CrawlerPhase.TRAVERSING_CHILD_SCREENS,
        statusMessage = "Resuming deep crawl after pause.",
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        failureMessage = null,
    )

    fun withCaptured(summary: CrawlRunSummary): CrawlerUiState = copy(
        phase = CrawlerPhase.CAPTURED,
        statusMessage = if (summary.capturedChildScreenCount > 0) {
            "Captured ${summary.capturedScreenCount} screen(s), including ${summary.capturedChildScreenCount} child screen(s)."
        } else {
            "Captured the first visible screen."
        },
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        screenName = summary.rootScreenName,
        htmlPath = summary.rootFiles.htmlFile.absolutePath,
        xmlPath = summary.rootFiles.xmlFile.absolutePath,
        mergedXmlPath = summary.rootFiles.mergedXmlFile?.absolutePath,
        crawlIndexPath = summary.manifestFile.absolutePath,
        graphJsonPath = summary.graphJsonPath.absolutePath,
        graphHtmlPath = summary.graphHtmlPath.absolutePath,
        scrollStepCount = summary.rootScrollStepCount,
        capturedScreenCount = summary.capturedScreenCount,
        capturedChildScreenCount = summary.capturedChildScreenCount,
        skippedElementCount = summary.skippedElementCount,
        maxDepthReached = summary.maxDepthReached,
        partialResult = false,
        failureMessage = null,
    )

    fun withAborted(summary: CrawlRunSummary, message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.ABORTED,
        statusMessage = message,
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        screenName = summary.rootScreenName,
        htmlPath = summary.rootFiles.htmlFile.absolutePath,
        xmlPath = summary.rootFiles.xmlFile.absolutePath,
        mergedXmlPath = summary.rootFiles.mergedXmlFile?.absolutePath,
        crawlIndexPath = summary.manifestFile.absolutePath,
        graphJsonPath = summary.graphJsonPath.absolutePath,
        graphHtmlPath = summary.graphHtmlPath.absolutePath,
        scrollStepCount = summary.rootScrollStepCount,
        capturedScreenCount = summary.capturedScreenCount,
        capturedChildScreenCount = summary.capturedChildScreenCount,
        skippedElementCount = summary.skippedElementCount,
        maxDepthReached = summary.maxDepthReached,
        partialResult = true,
        failureMessage = message,
    )

    fun withFailure(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.FAILED,
        statusMessage = message,
        pauseDecisionId = null,
        pauseReason = null,
        pauseElapsedTimeMs = null,
        pauseFailedEdgeCount = null,
        pausedCapturedScreenCount = null,
        pausedCapturedChildScreenCount = null,
        pauseCurrentPackageName = null,
        pauseNextPackageName = null,
        pauseTriggerLabel = null,
        failureMessage = message,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        mergedXmlPath = null,
        crawlIndexPath = null,
        graphJsonPath = null,
        graphHtmlPath = null,
        scrollStepCount = null,
        capturedScreenCount = null,
        capturedChildScreenCount = null,
        skippedElementCount = null,
        maxDepthReached = null,
        partialResult = false,
    )

    companion object {
        fun idle(): CrawlerUiState = CrawlerUiState()
    }

    private fun pauseMessage(reason: PauseReason): String {
        return when (reason) {
            PauseReason.ELAPSED_TIME_EXCEEDED ->
                "Deep crawl paused after reaching the elapsed-time checkpoint."

            PauseReason.FAILED_EDGE_COUNT_EXCEEDED ->
                "Deep crawl paused after reaching the failed-edge checkpoint."

            PauseReason.EXTERNAL_PACKAGE_BOUNDARY ->
                "Deep crawl paused before continuing into another package."
        }
    }
}

data class PressableElement(
    val label: String,
    val resourceId: String?,
    val bounds: String,
    val className: String?,
    val isListItem: Boolean,
    val childIndexPath: List<Int> = emptyList(),
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val editable: Boolean = false,
    val firstSeenStep: Int = 0,
)

data class CrawlRouteStep(
    val childIndexPath: List<Int>,
    val bounds: String,
    val resourceId: String?,
    val className: String?,
    val label: String,
    val checkable: Boolean,
    val checked: Boolean,
    val editable: Boolean,
    val firstSeenStep: Int,
    val expectedPackageName: String? = null,
)

data class CrawlRoute(
    val steps: List<CrawlRouteStep> = emptyList(),
) {
    fun append(step: CrawlRouteStep): CrawlRoute = CrawlRoute(steps = steps + step)
}

data class PressableElementLinkKey(
    val label: String,
    val resourceId: String?,
    val bounds: String,
    val className: String?,
    val isListItem: Boolean,
    val childIndexPath: List<Int>,
    val checkable: Boolean,
    val checked: Boolean,
    val editable: Boolean,
    val firstSeenStep: Int,
)

internal fun PressableElement.toLinkKey(): PressableElementLinkKey {
    return PressableElementLinkKey(
        label = label,
        resourceId = resourceId,
        bounds = bounds,
        className = className,
        isListItem = isListItem,
        childIndexPath = childIndexPath,
        checkable = checkable,
        checked = checked,
        editable = editable,
        firstSeenStep = firstSeenStep,
    )
}

internal fun PressableElement.toRouteStep(expectedPackageName: String? = null): CrawlRouteStep {
    return CrawlRouteStep(
        childIndexPath = childIndexPath,
        bounds = bounds,
        resourceId = resourceId,
        className = className,
        label = label,
        checkable = checkable,
        checked = checked,
        editable = editable,
        firstSeenStep = firstSeenStep,
        expectedPackageName = expectedPackageName,
    )
}

internal fun CrawlRouteStep.toPressableElement(): PressableElement {
    return PressableElement(
        label = label,
        resourceId = resourceId,
        bounds = bounds,
        className = className,
        isListItem = false,
        childIndexPath = childIndexPath,
        checkable = checkable,
        checked = checked,
        editable = editable,
        firstSeenStep = firstSeenStep,
    )
}

data class ScrollCaptureStep(
    val stepIndex: Int,
    val root: AccessibilityNodeSnapshot,
    val newElementCount: Int,
)

data class ScreenSnapshot(
    val screenName: String,
    val packageName: String,
    val elements: List<PressableElement>,
    val xmlDump: String,
    val mergedRoot: AccessibilityNodeSnapshot? = null,
    val mergedXmlDump: String? = null,
    val stepSnapshots: List<ScrollCaptureStep> = emptyList(),
    val scrollStepCount: Int = 1,
)

data class AccessibilityNodeSnapshot(
    val className: String?,
    val packageName: String?,
    val viewIdResourceName: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val supportsClickAction: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val bounds: String,
    val children: List<AccessibilityNodeSnapshot>,
    val childIndexPath: List<Int> = emptyList(),
    val synthetic: Boolean = false,
    val merged: Boolean = false,
    val syntheticScrollContainer: Boolean = false,
    val sourceStepIndices: List<Int> = emptyList(),
    val firstSeenStep: Int? = null,
)

enum class CrawlRunStatus {
    IN_PROGRESS,
    COMPLETED,
    PARTIAL_ABORT,
    FAILED,
}

enum class CrawlEdgeStatus {
    CAPTURED,
    LINKED_EXISTING,
    SKIPPED_BLACKLIST,
    SKIPPED_NO_NAVIGATION,
    SKIPPED_EXTERNAL_PACKAGE,
    FAILED,
}

data class CrawlScreenRecord(
    val screenId: String,
    val screenName: String,
    val packageName: String,
    val screenFingerprint: String,
    val htmlPath: String,
    val xmlPath: String,
    val mergedXmlPath: String? = null,
    val scrollStepCount: Int,
    val parentScreenId: String?,
    val triggerLabel: String?,
    val triggerResourceId: String?,
    val route: CrawlRoute = CrawlRoute(),
    val depth: Int,
)

data class CrawlEdgeRecord(
    val edgeId: String,
    val parentScreenId: String,
    val childScreenId: String? = null,
    val label: String,
    val resourceId: String?,
    val className: String?,
    val bounds: String,
    val childIndexPath: List<Int>,
    val firstSeenStep: Int,
    val status: CrawlEdgeStatus,
    val message: String? = null,
)

data class CrawlManifest(
    val sessionId: String,
    val packageName: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: CrawlRunStatus = CrawlRunStatus.IN_PROGRESS,
    val rootScreenId: String? = null,
    val maxDepthReached: Int = 0,
    val screens: List<CrawlScreenRecord> = emptyList(),
    val edges: List<CrawlEdgeRecord> = emptyList(),
)

data class CrawlRunSummary(
    val rootScreenName: String,
    val rootFiles: CapturedScreenFiles,
    val manifestFile: File,
    val graphJsonPath: File,
    val graphHtmlPath: File,
    val rootScrollStepCount: Int,
    val capturedScreenCount: Int,
    val capturedChildScreenCount: Int,
    val skippedElementCount: Int,
    val maxDepthReached: Int,
)

internal fun CrawlRunStatus.displayName(): String = name.lowercase(Locale.US)
