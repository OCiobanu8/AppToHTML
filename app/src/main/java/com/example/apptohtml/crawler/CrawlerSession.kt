package com.example.apptohtml.crawler

import android.content.Context
import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
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

    @Volatile
    private var lastObservedPackage: String? = null

    val uiState: StateFlow<CrawlerUiState> = _uiState.asStateFlow()

    fun currentState(): CrawlerUiState = _uiState.value

    fun recordObservedPackage(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            lastObservedPackage = packageName
        }
    }

    @Synchronized
    fun startCapture(context: Context, selectedApp: SelectedAppRef) {
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
        )
        DiagnosticLogger.log("Starting first-screen capture for ${selectedApp.packageName}; requestId=$requestId")

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

    private fun returnToApp() {
        val context = appContext ?: return
        runCatching {
            AppToHtmlNavigator.returnToApp(context)
        }.onFailure { error ->
            DiagnosticLogger.error("Failed to bring AppToHTML back to the foreground.", error)
        }
    }
}
