package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef

enum class CrawlerPhase {
    IDLE,
    LAUNCHING,
    WAITING_FOR_TARGET_SCREEN,
    SCANNING_TARGET_SCREEN,
    CAPTURED,
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
    val scrollStepCount: Int? = null,
    val failureMessage: String? = null,
) {
    fun withLaunching(requestId: Long, selectedApp: SelectedAppRef, alreadyRunning: Boolean): CrawlerUiState {
        val message = if (alreadyRunning) {
            "Target app appears to already be running. Relaunching it now."
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
        scrollStepCount = null,
        failureMessage = null,
    )

    fun withCaptured(screenName: String, files: CapturedScreenFiles, scrollStepCount: Int): CrawlerUiState = copy(
        phase = CrawlerPhase.CAPTURED,
        statusMessage = if (scrollStepCount > 1) {
            "Captured the first visible screen across $scrollStepCount scroll steps."
        } else {
            "Captured the first visible screen."
        },
        screenName = screenName,
        htmlPath = files.htmlFile.absolutePath,
        xmlPath = files.xmlFile.absolutePath,
        scrollStepCount = scrollStepCount,
        failureMessage = null,
    )

    fun withFailure(message: String): CrawlerUiState = copy(
        phase = CrawlerPhase.FAILED,
        statusMessage = message,
        failureMessage = message,
        screenName = null,
        htmlPath = null,
        xmlPath = null,
        scrollStepCount = null,
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
    val firstSeenStep: Int = 0,
)

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
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val bounds: String,
    val children: List<AccessibilityNodeSnapshot>,
    val childIndexPath: List<Int> = emptyList(),
)
