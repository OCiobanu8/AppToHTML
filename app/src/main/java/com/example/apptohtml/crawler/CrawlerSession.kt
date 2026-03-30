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
            "Relaunched the selected app. Waiting for the first screen."
        } else {
            "Selected app launched. Waiting for the first screen."
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
    fun beginScanning(requestId: Long, message: String) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN
                )
        ) {
            return
        }

        _uiState.value = current.withScanning(message)
    }

    @Synchronized
    fun updateProgress(requestId: Long, message: String) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN
                )
        ) {
            return
        }

        _uiState.value = current.withScanning(message)
    }

    @Synchronized
    fun completeCapture(
        requestId: Long,
        screenName: String,
        files: CapturedScreenFiles,
        scrollStepCount: Int,
    ) {
        val current = _uiState.value
        if (
            current.requestId != requestId ||
            (
                current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN &&
                    current.phase != CrawlerPhase.SCANNING_TARGET_SCREEN
                )
        ) {
            return
        }

        timeoutJob?.cancel()
        DiagnosticLogger.log(
            "Captured first screen '$screenName' for ${current.selectedApp?.packageName} across $scrollStepCount step(s); html=${files.htmlFile.absolutePath}; xml=${files.xmlFile.absolutePath}"
        )
        _uiState.value = current.withCaptured(screenName, files, scrollStepCount)
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
