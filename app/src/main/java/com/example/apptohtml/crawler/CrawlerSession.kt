package com.example.apptohtml.crawler

import android.content.Context
import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CrawlerSession {
    private const val captureTimeoutMillis = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CrawlerUiState.idle())
    private var timeoutJob: Job? = null
    private var appContext: Context? = null
    private var pendingPauseDecision: CompletableDeferred<PauseDecision>? = null
    private var pendingPauseDecisionId: Long? = null
    private var nextPauseDecisionId: Long = 1L

    @Volatile
    private var lastObservedPackage: String? = null

    val uiState: StateFlow<CrawlerUiState> = _uiState.asStateFlow()

    /**
     * Snapshot progress lives in its own flow. It must never be folded into [_uiState]: a snapshot
     * that moved [CrawlerPhase] off a terminal value would make the snapshot coordinator's own
     * "reject while a crawl is active" guard reject every subsequent capture.
     */
    private val _snapshotState = MutableStateFlow(SnapshotUiState())

    val snapshotState: StateFlow<SnapshotUiState> = _snapshotState.asStateFlow()

    fun currentState(): CrawlerUiState = _uiState.value

    internal fun snapshotCaptureStarted(packageName: String?) {
        _snapshotState.value = SnapshotUiState(
            status = SnapshotStatus.CAPTURING,
            packageName = packageName,
            message = "Capturing the visible screen.",
        )
    }

    internal fun snapshotCaptured(result: SnapshotResult) {
        _snapshotState.value = SnapshotUiState(
            status = SnapshotStatus.CAPTURED,
            screenName = result.screenName,
            packageName = result.packageName,
            directoryPath = result.directory.absolutePath,
            elementCount = result.elementCount,
            scrollStepCount = result.scrollStepCount,
            message = "Captured '${result.screenName}' (${result.elementCount} elements).",
        )
        DiagnosticLogger.log(
            "snapshot_captured package=${result.packageName} screen='${result.screenName}' " +
                "elements=${result.elementCount} steps=${result.scrollStepCount} " +
                "directory=${result.directory.absolutePath}"
        )
    }

    internal fun snapshotFailed(packageName: String?, reason: String) {
        _snapshotState.value = SnapshotUiState(
            status = SnapshotStatus.FAILED,
            packageName = packageName,
            message = reason,
        )
        DiagnosticLogger.error("snapshot_failed package=${packageName.orEmpty()} reason=$reason")
    }

    fun recordObservedPackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            lastObservedPackage = packageName
        }
    }

    @Synchronized
    fun startCapture(
        context: Context,
        selectedApp: SelectedAppRef,
        crawlStartIntent: CrawlStartIntent = CrawlStartIntent.RESUME,
        resumeMode: ResumeMode = ResumeMode.ContinueAuto,
    ) {
        startCrawl(
            context = context,
            selectedApp = selectedApp,
            crawlStartIntent = crawlStartIntent,
            resumeMode = resumeMode,
        )
    }

    @Synchronized
    fun startCrawl(
        context: Context,
        selectedApp: SelectedAppRef,
        crawlStartIntent: CrawlStartIntent = CrawlStartIntent.RESUME,
        resumeMode: ResumeMode = ResumeMode.ContinueAuto,
    ) {
        timeoutJob?.cancel()
        appContext = context.applicationContext

        val requestId = System.currentTimeMillis()
        val alreadyRunning = AppLaunchHelper.appearsToBeRunning(
            context = context,
            packageName = selectedApp.packageName,
            lastObservedPackage = lastObservedPackage,
        )
        _uiState.value = CrawlerUiState.idle().withLaunching(
            requestId = requestId,
            selectedApp = selectedApp,
            alreadyRunning = alreadyRunning,
            crawlStartIntent = crawlStartIntent,
            resumeMode = resumeMode,
        )
        DiagnosticLogger.log(
            "Starting deep crawl for ${selectedApp.packageName}; requestId=$requestId " +
                "intent=${crawlStartIntent.name.lowercase()} resumeMode=${resumeMode.toSessionLogString()}"
        )

        val launchResult = AppLaunchHelper.launchSelectedApp(
            context = context,
            selectedApp = selectedApp,
            lastObservedPackage = lastObservedPackage,
        )
        if (launchResult.errorMessage != null) {
            failRequest(requestId, launchResult.errorMessage)
            return
        }

        val waitingMessage = if (launchResult.alreadyRunning) {
            "Target app brought to the foreground. Waiting to inspect the visible screen."
        } else {
            "Selected app launched. Waiting to inspect the visible screen."
        }
        _uiState.value = _uiState.value.withWaiting(waitingMessage)

        timeoutJob = scope.launch {
            delay(captureTimeoutMillis)
            val current = _uiState.value
            if (
                current.requestId == requestId &&
                (
                    current.phase == CrawlerPhase.WAITING_FOR_TARGET_SCREEN ||
                        current.phase == CrawlerPhase.SCANNING_TARGET_SCREEN
                    )
            ) {
                failRequest(requestId, "Timed out waiting for the first screen scan to finish.")
            }
        }
    }

    @Synchronized
    fun claimScanning(requestId: Long, message: String): Boolean {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN
        ) {
            return false
        }

        _uiState.value = current.withScanning(message)
        timeoutJob?.cancel()
        return true
    }

    @Synchronized
    fun updateProgress(requestId: Long, message: String) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.TRAVERSING_CHILD_SCREENS
                )
        ) {
            return
        }

        _uiState.value = if (current.phase == CrawlerPhase.TRAVERSING_CHILD_SCREENS) {
            current.withTraversingChildren(message)
        } else {
            current.withScanning(message)
        }
    }

    @Synchronized
    fun beginTraversingChildren(requestId: Long, message: String) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.TRAVERSING_CHILD_SCREENS
                )
        ) {
            return
        }

        timeoutJob?.cancel()
        _uiState.value = current.withTraversingChildren(message)
    }

    suspend fun pauseForDecision(
        reason: PauseReason,
        snapshot: PauseProgressSnapshot,
    ): PauseDecision {
        val deferred = synchronized(this) {
            val current = _uiState.value
            val requestId = current.requestId
            if (
                requestId == null ||
                (
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN &&
                        current.phase != CrawlerPhase.TRAVERSING_CHILD_SCREENS
                    )
            ) {
                return PauseDecision.STOP
            }
            check(pendingPauseDecision == null) {
                "A pause decision is already pending for requestId=$requestId."
            }

            timeoutJob?.cancel()
            val decision = CompletableDeferred<PauseDecision>()
            val decisionId = nextPauseDecisionId++
            pendingPauseDecision = decision
            pendingPauseDecisionId = decisionId
            _uiState.value = current.withPausedForDecision(
                decisionId = decisionId,
                reason = reason,
                snapshot = snapshot,
            )
            logSafely(
                "Paused deep crawl for requestId=$requestId decisionId=$decisionId reason=${reason.name.lowercase()}."
            )
            decision
        }

        returnToApp()
        return deferred.await()
    }

    @Synchronized
    fun resumeCrawl(requestId: Long, decisionId: Long) {
        resolvePauseDecision(
            requestId = requestId,
            decisionId = decisionId,
            decision = PauseDecision.CONTINUE,
            resumedState = { current -> current.withResumedFromDecision() },
        )
    }

    @Synchronized
    fun stopAndSave(requestId: Long, decisionId: Long) {
        resolvePauseDecision(
            requestId = requestId,
            decisionId = decisionId,
            decision = PauseDecision.STOP,
            resumedState = { current ->
                current.withResumedFromDecision().copy(
                    statusMessage = "Stopping deep crawl and saving current progress.",
                )
            },
        )
    }

    @Synchronized
    fun completeCapture(
        requestId: Long,
        summary: CrawlRunSummary,
    ) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.TRAVERSING_CHILD_SCREENS
                )
        ) {
            return
        }

        timeoutJob?.cancel()
        DiagnosticLogger.log(
            "Captured crawl rooted at '${summary.rootScreenName}' for ${current.selectedApp?.packageName}; screens=${summary.capturedScreenCount}; manifest=${summary.manifestFile.absolutePath}"
        )
        _uiState.value = current.withCaptured(summary)
        returnToApp()
    }

    @Synchronized
    fun abortCapture(
        requestId: Long,
        summary: CrawlRunSummary,
        message: String,
    ) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.TRAVERSING_CHILD_SCREENS
                )
        ) {
            return
        }

        timeoutJob?.cancel()
        DiagnosticLogger.error("Crawl aborted after partial save: $message")
        _uiState.value = current.withAborted(summary, message)
        returnToApp()
    }

    @Synchronized
    fun failRequest(requestId: Long, message: String) {
        val current = _uiState.value
        if (current.requestId != requestId) {
            return
        }

        timeoutJob?.cancel()
        DiagnosticLogger.error("First-screen capture failed: $message")
        _uiState.value = current.withFailure(message)
        returnToApp()
    }

    private fun resolvePauseDecision(
        requestId: Long,
        decisionId: Long,
        decision: PauseDecision,
        resumedState: (CrawlerUiState) -> CrawlerUiState,
    ) {
        val current = _uiState.value
        val deferred = pendingPauseDecision
        if (
            current.requestId != requestId ||
            current.phase != CrawlerPhase.PAUSED_FOR_DECISION ||
            deferred == null ||
            pendingPauseDecisionId != decisionId
        ) {
            logSafely(
                "Ignoring stale pause decision for requestId=$requestId decisionId=$decisionId."
            )
            return
        }

        pendingPauseDecision = null
        pendingPauseDecisionId = null
        _uiState.value = resumedState(current)
        deferred.complete(decision)
        logSafely(
            "Resolved deep crawl pause for requestId=$requestId decisionId=$decisionId with ${decision.name.lowercase()}."
        )
    }

    private fun returnToApp() {
        val context = appContext ?: return
        runCatching {
            AppToHtmlNavigator.returnToApp(context)
        }.onFailure { error ->
            DiagnosticLogger.error("Failed to bring AppToHTML back to the foreground.", error)
        }
    }

    private fun logSafely(message: String) {
        runCatching {
            DiagnosticLogger.log(message)
        }
    }
}

private fun ResumeMode.toSessionLogString(): String = when (this) {
    ResumeMode.ContinueAuto -> "continue_auto"
    is ResumeMode.ResumeFromScreen -> "resume_from_screen:$screenId"
    is ResumeMode.ReExpand -> "re_expand:$screenId"
}
