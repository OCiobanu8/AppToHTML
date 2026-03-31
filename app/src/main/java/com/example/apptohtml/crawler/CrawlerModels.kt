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
    CAPTURED,
    ABORTED,
    FAILED,
}

data class CrawlerUiState(
    val phase: CrawlerPhase = CrawlerPhase.IDLE,
    val selectedApp: SelectedAppRef? = null,
    val requestId: Long? = null,
    val statusMessage: String = "No capture started yet.",
    val screenName: String? = null,
    val htmlPath: String? = null,
    val xmlPath: String? = null,
    val crawlIndexPath: String? = null,
    val scrollStepCount: Int? = null,
    val capturedScreenCount: Int? = null,
    val capturedChildScreenCount: Int? = null,
    val skippedElementCount: Int? = null,
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
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        scrollStepCount = null,
        failureMessage = null,
    )

    fun withScanning(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.SCANNING_TARGET_SCREEN,
        statusMessage = message,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        crawlIndexPath = null,
        scrollStepCount = null,
        capturedScreenCount = null,
        capturedChildScreenCount = null,
        skippedElementCount = null,
        partialResult = false,
        failureMessage = null,
    )

    fun withTraversingChildren(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.TRAVERSING_CHILD_SCREENS,
        statusMessage = message,
        failureMessage = null,
    )

    fun withCaptured(summary: CrawlRunSummary): CrawlerUiState = copy(
        phase = CrawlerPhase.CAPTURED,
        statusMessage = if (summary.capturedChildScreenCount > 0) {
            "Captured ${summary.capturedScreenCount} screen(s), including ${summary.capturedChildScreenCount} child screen(s)."
        } else {
            "Captured the first visible screen."
        },
        screenName = summary.rootScreenName,
        htmlPath = summary.rootFiles.htmlFile.absolutePath,
        xmlPath = summary.rootFiles.xmlFile.absolutePath,
        crawlIndexPath = summary.manifestFile.absolutePath,
        scrollStepCount = summary.rootScrollStepCount,
        capturedScreenCount = summary.capturedScreenCount,
        capturedChildScreenCount = summary.capturedChildScreenCount,
        skippedElementCount = summary.skippedElementCount,
        partialResult = false,
        failureMessage = null,
    )

    fun withAborted(summary: CrawlRunSummary, message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.ABORTED,
        statusMessage = message,
        screenName = summary.rootScreenName,
        htmlPath = summary.rootFiles.htmlFile.absolutePath,
        xmlPath = summary.rootFiles.xmlFile.absolutePath,
        crawlIndexPath = summary.manifestFile.absolutePath,
        scrollStepCount = summary.rootScrollStepCount,
        capturedScreenCount = summary.capturedScreenCount,
        capturedChildScreenCount = summary.capturedChildScreenCount,
        skippedElementCount = summary.skippedElementCount,
        partialResult = true,
        failureMessage = message,
    )

    fun withFailure(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.FAILED,
        statusMessage = message,
        failureMessage = message,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        crawlIndexPath = null,
        scrollStepCount = null,
        capturedScreenCount = null,
        capturedChildScreenCount = null,
        skippedElementCount = null,
        partialResult = false,
    )

    companion object {
        fun idle(): CrawlerUiState = CrawlerUiState()
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
    val firstSeenStep: Int = 0,
)

data class PressableElementLinkKey(
    val label: String,
    val resourceId: String?,
    val bounds: String,
    val className: String?,
    val isListItem: Boolean,
    val childIndexPath: List<Int>,
    val checkable: Boolean,
    val checked: Boolean,
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
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val bounds: String,
    val children: List<AccessibilityNodeSnapshot>,
    val childIndexPath: List<Int> = emptyList(),
)

enum class CrawlRunStatus {
    IN_PROGRESS,
    COMPLETED,
    PARTIAL_ABORT,
    FAILED,
}

enum class CrawlEdgeStatus {
    CAPTURED,
    SKIPPED_BLACKLIST,
    SKIPPED_NO_NAVIGATION,
    FAILED,
}

data class CrawlScreenRecord(
    val screenId: String,
    val screenName: String,
    val htmlPath: String,
    val xmlPath: String,
    val scrollStepCount: Int,
    val parentScreenId: String?,
    val triggerLabel: String?,
    val triggerResourceId: String?,
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
    val screens: List<CrawlScreenRecord> = emptyList(),
    val edges: List<CrawlEdgeRecord> = emptyList(),
)

data class CrawlRunSummary(
    val rootScreenName: String,
    val rootFiles: CapturedScreenFiles,
    val manifestFile: File,
    val rootScrollStepCount: Int,
    val capturedScreenCount: Int,
    val capturedChildScreenCount: Int,
    val skippedElementCount: Int,
)

internal fun CrawlRunStatus.displayName(): String = name.lowercase(Locale.US)
