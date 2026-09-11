package com.example.apptohtml

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.apptohtml.crawler.AccessibilityNodeSnapshot
import com.example.apptohtml.crawler.AccessibilityTreeSnapshotter
import com.example.apptohtml.crawler.AppLaunchHelper
import com.example.apptohtml.crawler.CaptureFileStore
import com.example.apptohtml.crawler.CapturedScreenFiles
import com.example.apptohtml.crawler.ClickFallbackMatcher
import com.example.apptohtml.crawler.CrawlBlacklistLoader
import com.example.apptohtml.crawler.CrawlEdgeStatus
import com.example.apptohtml.crawler.CrawlLogger
import com.example.apptohtml.crawler.CrawlRunStatus
import com.example.apptohtml.crawler.CrawlRunSummary
import com.example.apptohtml.crawler.CrawlRunTracker
import com.example.apptohtml.crawler.CrawlSessionDirectory
import com.example.apptohtml.crawler.CrawlerPhase
import com.example.apptohtml.crawler.CrawlerSession
import com.example.apptohtml.crawler.DeepCrawlCoordinator
import com.example.apptohtml.crawler.EntryScreenResetOutcome
import com.example.apptohtml.crawler.LiveActionIds
import com.example.apptohtml.crawler.LiveNodeActions
import com.example.apptohtml.crawler.LiveNodeAttributes
import com.example.apptohtml.crawler.PauseDecision
import com.example.apptohtml.crawler.PauseProgressSnapshot
import com.example.apptohtml.crawler.PauseReason
import com.example.apptohtml.crawler.SnapshotCaptureCoordinator
import com.example.apptohtml.crawler.SnapshotCaptureOutcome
import com.example.apptohtml.crawler.SnapshotCaptureRequest
import com.example.apptohtml.crawler.SnapshotRequestParser
import com.example.apptohtml.crawler.PressableElementLinkKey
import com.example.apptohtml.crawler.PressableElement
import com.example.apptohtml.crawler.ScreenNaming
import com.example.apptohtml.crawler.ScreenSnapshot
import com.example.apptohtml.crawler.ScrollScanCoordinator
import com.example.apptohtml.crawler.TraversalPlanner
import com.example.apptohtml.crawler.toLinkKey
import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class AppToHtmlAccessibilityService : AccessibilityService() {
    private val scrollScanCoordinator = ScrollScanCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val waitingCaptureGenerationGate = WaitingCaptureGenerationGate()
    private var captureJob: Job? = null
    private var snapshotJob: Job? = null
    private var snapshotReceiver: BroadcastReceiver? = null
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var activeCrawlLogger: CrawlLogger? = null

    /**
     * Deliberately `by lazy`, not an eager field or a companion constant.
     * [AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN] only exists from API 29, so
     * resolving the action-id vocabulary eagerly would move a potential `NoSuchFieldError` on an
     * API 24â€“28 device from "the first time a scroll is attempted" (where it lives today) to
     * "service construction". Lazy keeps the pre-refactor timing.
     */
    private val liveNodeActions: LiveNodeActions<AccessibilityNodeInfo> by lazy {
        LiveNodeActions(
            childCount = { node -> node.childCount },
            childAt = { node, index -> node.getChild(index) },
            attributes = { node -> node.toLiveNodeAttributes() },
            supportedActionIds = { node -> node.actionList.map { it.id }.toSet() },
            performAction = { node, actionId -> node.performAction(actionId) },
            actionIds = LiveActionIds(
                scrollForward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                scrollBackward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                click = AccessibilityNodeInfo.ACTION_CLICK,
                scrollDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                scrollUp = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                pageDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
                pageUp = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            ),
            logger = { activeCrawlLogger },
        )
    }

    companion object {
        private const val captureDebounceMillis = 350L
        private const val scrollSettleDelayMillis = 350L
    }

    private fun AccessibilityNodeInfo.toLiveNodeAttributes(): LiveNodeAttributes {
        val bounds = Rect()
        getBoundsInScreen(bounds)
        return LiveNodeAttributes(
            className = className?.toString(),
            viewIdResourceName = viewIdResourceName,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            boundsShortString = bounds.toShortString(),
            visibleToUser = isVisibleToUser,
            enabled = isEnabled,
            scrollable = isScrollable,
            clickable = isClickable,
            checkable = isCheckable,
            editable = isEditable,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        registerSnapshotReceiver()
        DiagnosticLogger.log("Accessibility capability connected")
    }

    /**
     * Registered at runtime in **every** build variant. Runtime receivers are exempt from the
     * Android 8 implicit-broadcast restrictions, and the service process is alive whenever the
     * accessibility service is enabled, so `am broadcast` reaches it.
     *
     * This is an unauthenticated exported surface that can dump the foreground app's accessibility
     * tree to disk. That is a deliberate, accepted trade for a local development tool; gating it
     * before any production release is tracked separately as bead `a2h-oo0`.
     */
    private fun registerSnapshotReceiver() {
        if (snapshotReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val safeIntent = intent ?: return
                if (safeIntent.action != SnapshotRequestParser.ACTION_CAPTURE_SCREEN) {
                    return
                }
                onSnapshotRequested(safeIntent)
            }
        }
        snapshotReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(SnapshotRequestParser.ACTION_CAPTURE_SCREEN),
            ContextCompat.RECEIVER_EXPORTED,
        )
        DiagnosticLogger.log("Snapshot broadcast receiver registered")
    }

    private fun onSnapshotRequested(intent: Intent) {
        val request = SnapshotRequestParser.parse(
            token = intent.getStringExtra(SnapshotRequestParser.EXTRA_TOKEN),
            name = intent.getStringExtra(SnapshotRequestParser.EXTRA_NAME),
            scrollBoolean = if (intent.hasExtra(SnapshotRequestParser.EXTRA_SCROLL)) {
                runCatching { intent.getBooleanExtra(SnapshotRequestParser.EXTRA_SCROLL, true) }.getOrNull()
            } else {
                null
            },
            scrollString = runCatching {
                intent.getStringExtra(SnapshotRequestParser.EXTRA_SCROLL)
            }.getOrNull(),
        )

        // The receiver itself does no work and does not use goAsync(): ordered-broadcast results
        // are capped around 10 s and a long scrollable screen exceeds that. Completion is signalled
        // by the .done marker on disk instead.
        snapshotJob?.cancel()
        snapshotJob = serviceScope.launch {
            runSnapshotCapture(request)
        }
    }

    private suspend fun runSnapshotCapture(request: SnapshotCaptureRequest) {
        val foreground = rootInActiveWindow?.packageName?.toString()
        CrawlerSession.snapshotCaptureStarted(foreground)

        val coordinator = SnapshotCaptureCoordinator(
            host = object : SnapshotCaptureCoordinator.Host {
                override fun currentCrawlPhase(): CrawlerPhase = CrawlerSession.currentState().phase

                override fun foregroundPackageName(): String? =
                    rootInActiveWindow?.packageName?.toString()

                override fun appLabelFor(packageName: String): String = runCatching {
                    val info = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(packageName)

                override suspend fun captureCurrentRootSnapshot(
                    expectedPackageName: String?,
                ): AccessibilityNodeSnapshot? =
                    this@AppToHtmlAccessibilityService.captureCurrentRootSnapshot(expectedPackageName)

                override fun scrollForward(childIndexPath: List<Int>): Boolean {
                    val liveRoot = rootInActiveWindow ?: return false
                    return liveNodeActions.performScroll(
                        liveRoot,
                        childIndexPath,
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                    )
                }

                override fun scrollBackward(childIndexPath: List<Int>): Boolean {
                    val liveRoot = rootInActiveWindow ?: return false
                    return liveNodeActions.performScroll(
                        liveRoot,
                        childIndexPath,
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                    )
                }

                override fun baseDirectory(): File =
                    getExternalFilesDir(null) ?: filesDir

                override fun publishProgress(message: String) {
                    DiagnosticLogger.log("snapshot_progress $message")
                }
            },
        )

        when (val outcome = coordinator.capture(request)) {
            is SnapshotCaptureOutcome.Captured -> {
                CrawlerSession.snapshotCaptured(outcome.result)
                showSnapshotToast(
                    "Captured '${outcome.result.screenName}' (${outcome.result.elementCount} elements)"
                )
            }

            is SnapshotCaptureOutcome.Rejected -> {
                CrawlerSession.snapshotFailed(foreground, outcome.reason.wireValue)
                showSnapshotToast("Snapshot rejected: ${outcome.reason.wireValue}")
            }

            is SnapshotCaptureOutcome.Failed -> {
                CrawlerSession.snapshotFailed(foreground, outcome.reason)
                showSnapshotToast("Snapshot failed: ${outcome.reason}")
            }
        }
    }

    private fun showSnapshotToast(message: String) {
        mainThreadHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val eventPackage = safeEvent.packageName?.toString()
        CrawlerSession.recordObservedPackage(eventPackage)

        val current = CrawlerSession.currentState()
        val requestId = current.requestId ?: return
        val selectedApp = current.selectedApp ?: return
        if (current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN) {
            return
        }

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (eventPackage != selectedApp.packageName && rootPackage != selectedApp.packageName) {
            return
        }

        val eventClassName = safeEvent.className?.toString()
        val scheduledGeneration = waitingCaptureGenerationGate.scheduleNextAttempt()
        captureJob = serviceScope.launch {
            delay(captureDebounceMillis)
            attemptCapture(
                requestId = requestId,
                scheduledGeneration = scheduledGeneration,
                targetPackageName = selectedApp.packageName,
                eventClassName = eventClassName,
            )
        }
    }

    override fun onInterrupt() {
        DiagnosticLogger.log("Accessibility capability interrupted")
    }

    override fun onDestroy() {
        snapshotReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
            snapshotReceiver = null
        }
        snapshotJob?.cancel()
        captureJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun attemptCapture(
        requestId: Long,
        scheduledGeneration: Long,
        targetPackageName: String,
        eventClassName: String?,
    ) {
        if (!waitingCaptureGenerationGate.isCurrent(scheduledGeneration)) {
            return
        }

        val current = CrawlerSession.currentState()
        val selectedApp = current.selectedApp ?: return
        if (current.requestId != requestId || current.phase != CrawlerPhase.WAITING_FOR_TARGET_SCREEN) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            val rootPackage = root.packageName?.toString()
            if (rootPackage != targetPackageName) {
                return
            }

            if (!waitingCaptureGenerationGate.isCurrent(scheduledGeneration)) {
                return
            }

            if (
                !CrawlerSession.claimScanning(
                    requestId = requestId,
                    message = "Resetting to the first screen.",
                )
            ) {
                return
            }

            val initialRoot = AccessibilityTreeSnapshotter.captureRootSnapshot(root)
            val coordinator = DeepCrawlCoordinator(
                selectedApp = selectedApp,
                host = object : DeepCrawlCoordinator.Host {
                    override suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot? {
                        return this@AppToHtmlAccessibilityService.captureCurrentRootSnapshot(expectedPackageName)
                    }

                    override fun scrollForward(childIndexPath: List<Int>): Boolean {
                        val liveRoot = rootInActiveWindow ?: return false
                        return liveNodeActions.performScroll(
                            liveRoot,
                            childIndexPath,
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                        )
                    }

                    override fun scrollBackward(childIndexPath: List<Int>): Boolean {
                        val liveRoot = rootInActiveWindow ?: return false
                        return liveNodeActions.performScroll(
                            liveRoot,
                            childIndexPath,
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                        )
                    }

                    override fun click(element: PressableElement): Boolean {
                        val liveRoot = rootInActiveWindow ?: return false
                        return liveNodeActions.performClick(liveRoot, element)
                    }

                    override fun performGlobalBack(): Boolean {
                        return performGlobalAction(GLOBAL_ACTION_BACK)
                    }

                    override suspend fun relaunchTargetApp(selectedApp: SelectedAppRef): String? {
                        return AppLaunchHelper.launchSelectedApp(
                            context = this@AppToHtmlAccessibilityService,
                            selectedApp = selectedApp,
                            lastObservedPackage = null,
                        ).errorMessage
                    }

                    override suspend fun awaitPauseDecision(
                        reason: PauseReason,
                        snapshot: PauseProgressSnapshot,
                    ): PauseDecision {
                        return CrawlerSession.pauseForDecision(
                            reason = reason,
                            snapshot = snapshot,
                        )
                    }

                    override fun publishProgress(message: String) {
                        CrawlerSession.updateProgress(requestId, message)
                    }

                    override fun setActiveCrawlLogger(logger: CrawlLogger?) {
                        activeCrawlLogger = logger
                    }
                },
                loadBlacklist = {
                    CrawlBlacklistLoader.load(this@AppToHtmlAccessibilityService)
                },
                createSession = { startedAt, wipeExisting ->
                    CaptureFileStore.createSession(
                        context = this@AppToHtmlAccessibilityService,
                        packageName = targetPackageName,
                        startedAt = startedAt,
                        wipeExisting = wipeExisting,
                    )
                },
                scrollScanCoordinator = scrollScanCoordinator,
            )

            when (
                val result = coordinator.crawl(
                    initialRoot = initialRoot,
                    eventClassName = eventClassName,
                    intent = current.crawlStartIntent,
                    resumeMode = current.resumeMode,
                )
            ) {
                is DeepCrawlCoordinator.DeepCrawlOutcome.Completed -> {
                    CrawlerSession.completeCapture(
                        requestId = requestId,
                        summary = result.summary,
                    )
                }

                is DeepCrawlCoordinator.DeepCrawlOutcome.PartialAbort -> {
                    CrawlerSession.abortCapture(
                        requestId = requestId,
                        summary = result.summary,
                        message = result.message,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            DiagnosticLogger.log(
                "Capture coroutine canceled for requestId=$requestId; treating as expected shutdown/control flow."
            )
            throw cancellation
        } catch (error: Throwable) {
            DiagnosticLogger.error(
                "Failed to crawl the target app for requestId=$requestId package=$targetPackageName.",
                error,
            )
            CrawlerSession.failRequest(
                requestId = requestId,
                message = "Failed to crawl the target app: ${error.message ?: "unknown error"}.",
            )
        }
    }

    private suspend fun normalizeRootToEntryScreen(
        targetPackageName: String,
        initialRoot: AccessibilityNodeSnapshot,
        requestId: Long,
    ) = scrollScanCoordinator.rewindToEntryScreen(
        initialRoot = initialRoot,
        targetPackageName = targetPackageName,
        tryBack = {
            performGlobalAction(GLOBAL_ACTION_BACK)
        },
        captureCurrentRoot = {
            captureCurrentRootSnapshot(expectedPackageName = null)
        },
        onProgress = { message ->
            CrawlerSession.updateProgress(requestId, message)
        },
    )

    private fun entryScreenResetFailureMessage(outcome: EntryScreenResetOutcome): String {
        return when (outcome) {
            EntryScreenResetOutcome.MATCHED_EXPECTED_LOGICAL,
            EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL,
            EntryScreenResetOutcome.NO_BACK_AFFORDANCE_ASSUMED_ENTRY ->
                "Reset to the first screen succeeded and was reported as a failure unexpectedly."

            EntryScreenResetOutcome.EXPECTED_LOGICAL_NOT_FOUND ->
                "Could not reset to the first screen because the expected logical entry fingerprint was not observed."

            EntryScreenResetOutcome.BACK_ACTION_FAILED ->
                "Could not reset to the first screen because a visible in-app back button was still present when Android back stopped working."

            EntryScreenResetOutcome.LEFT_TARGET_APP ->
                "Could not reset to the first screen because backing out left the target app before the in-app back button disappeared."

            EntryScreenResetOutcome.MAX_ATTEMPTS_REACHED ->
                "Could not reset to the first screen because a visible in-app back button was still present after the maximum number of back attempts."
        }
    }

    private suspend fun ensureTargetAppForegroundForRootScan(
        selectedApp: SelectedAppRef,
        targetPackageName: String,
        requestId: Long,
    ): AccessibilityNodeSnapshot {
        captureCurrentRootSnapshot(targetPackageName)?.let { return it }

        CrawlerSession.updateProgress(
            requestId = requestId,
            message = "Returning to the target app before scanning the root screen.",
        )
        val relaunchResult = AppLaunchHelper.launchSelectedApp(
            context = this,
            selectedApp = selectedApp,
            lastObservedPackage = null,
        )
        if (relaunchResult.errorMessage != null) {
            throw IllegalStateException(relaunchResult.errorMessage)
        }

        repeat(4) {
            captureCurrentRootSnapshot(targetPackageName)?.let { return it }
        }

        throw IllegalStateException(
            "Target app left the foreground while resetting to the first screen."
        )
    }

    private suspend fun scanCurrentScreen(
        selectedApp: SelectedAppRef,
        eventClassName: String?,
        initialRoot: AccessibilityNodeSnapshot,
        capturePackageName: String?,
        requestId: Long,
        progressPrefix: String,
    ): ScreenSnapshot {
        return scrollScanCoordinator.scan(
            selectedApp = selectedApp,
            eventClassName = eventClassName,
            initialRoot = initialRoot,
            tryScrollForward = { path ->
                val liveRoot = rootInActiveWindow ?: return@scan false
                liveNodeActions.performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            },
            tryScrollBackward = { path ->
                val liveRoot = rootInActiveWindow ?: return@scan false
                liveNodeActions.performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            },
            captureCurrentRoot = {
                captureCurrentRootSnapshot(capturePackageName)
            },
            onProgress = { message ->
                CrawlerSession.updateProgress(requestId, "$progressPrefix $message")
            },
        )
    }

    private suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot? {
        delay(scrollSettleDelayMillis)
        val liveRoot = rootInActiveWindow ?: return null
        if (
            expectedPackageName != null &&
            liveRoot.packageName?.toString() != expectedPackageName
        ) {
            return null
        }
        return AccessibilityTreeSnapshotter.captureRootSnapshot(liveRoot)
    }

    private fun buildSummary(
        session: CrawlSessionDirectory,
        tracker: CrawlRunTracker,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        manifestFile: File,
    ): CrawlRunSummary {
        return CrawlRunSummary(
            rootScreenName = rootSnapshot.screenName,
            rootFiles = rootFiles,
            manifestFile = manifestFile,
            graphJsonPath = session.graphJsonFile,
            graphHtmlPath = session.graphHtmlFile,
            rootScrollStepCount = rootSnapshot.scrollStepCount,
            capturedScreenCount = tracker.capturedScreenCount(),
            capturedChildScreenCount = tracker.capturedChildScreenCount(),
            skippedElementCount = tracker.skippedElementCount(),
            maxDepthReached = tracker.maxDiscoveredDepth(),
        )
    }

    private fun failCurrentEdge(
        element: PressableElement,
        message: String,
    ): Nothing {
        throw RecoverableChildTraversalException(
            element = element,
            message = message,
        )
    }

    private fun abortPartialCapture(
        tracker: CrawlRunTracker,
        rootScreenId: String,
        session: CrawlSessionDirectory,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        message: String,
        failedElement: PressableElement? = null,
    ): Nothing {
        failedElement?.let { element ->
            tracker.addEdge(
                parentScreenId = rootScreenId,
                element = element,
                status = CrawlEdgeStatus.FAILED,
                message = message,
            )
        }
        val manifestFile = CaptureFileStore.saveManifest(
            session = session,
            manifest = tracker.buildManifest(CrawlRunStatus.PARTIAL_ABORT),
        )
        throw PartialCrawlAbortException(
            summary = buildSummary(
                session = session,
                tracker = tracker,
                rootSnapshot = rootSnapshot,
                rootFiles = rootFiles,
                manifestFile = manifestFile,
            ),
            message = message,
        )
    }

    private class PartialCrawlAbortException(
        val summary: CrawlRunSummary,
        message: String,
    ) : IllegalStateException(message)

    private class RecoverableChildTraversalException(
        val element: PressableElement,
        message: String,
    ) : IllegalStateException(message)
}
