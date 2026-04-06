package com.example.apptohtml

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.apptohtml.crawler.AccessibilityNodeSnapshot
import com.example.apptohtml.crawler.AccessibilityTreeSnapshotter
import com.example.apptohtml.crawler.AppLaunchHelper
import com.example.apptohtml.crawler.CaptureFileStore
import com.example.apptohtml.crawler.CapturedScreenFiles
import com.example.apptohtml.crawler.CrawlBlacklistLoader
import com.example.apptohtml.crawler.CrawlEdgeStatus
import com.example.apptohtml.crawler.CrawlRunStatus
import com.example.apptohtml.crawler.CrawlRunSummary
import com.example.apptohtml.crawler.CrawlRunTracker
import com.example.apptohtml.crawler.CrawlSessionDirectory
import com.example.apptohtml.crawler.CrawlerPhase
import com.example.apptohtml.crawler.CrawlerSession
import com.example.apptohtml.crawler.EntryScreenResetStopReason
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
import kotlin.math.abs

class AppToHtmlAccessibilityService : AccessibilityService() {
    private val scrollScanCoordinator = ScrollScanCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    companion object {
        private const val captureDebounceMillis = 350L
        private const val scrollSettleDelayMillis = 350L
        private const val maxBackNavigationAttempts = 3
        private const val clickBoundsTolerancePx = 24
        private val boundsRegex = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
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
        DiagnosticLogger.log("Accessibility capability connected")
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
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            delay(captureDebounceMillis)
            attemptCapture(
                requestId = requestId,
                targetPackageName = selectedApp.packageName,
                eventClassName = eventClassName,
            )
        }
    }

    override fun onInterrupt() {
        DiagnosticLogger.log("Accessibility capability interrupted")
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun attemptCapture(
        requestId: Long,
        targetPackageName: String,
        eventClassName: String?,
    ) {
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

            val crawlStartedAt = System.currentTimeMillis()
            val blacklist = CrawlBlacklistLoader.load(this)
            val session = CaptureFileStore.createSession(this, targetPackageName, crawlStartedAt)
            val tracker = CrawlRunTracker(
                sessionId = session.sessionId,
                packageName = targetPackageName,
                startedAt = crawlStartedAt,
            )

            CrawlerSession.beginScanning(
                requestId = requestId,
                message = "Resetting to the first screen.",
            )

            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = targetPackageName,
                initialRoot = AccessibilityTreeSnapshotter.captureRootSnapshot(root),
                requestId = requestId,
            )
            if (entryResetResult.stopReason != EntryScreenResetStopReason.NO_BACK_AFFORDANCE) {
                throw IllegalStateException(entryScreenResetFailureMessage(entryResetResult.stopReason))
            }
            val entryRoot = entryResetResult.root

            val liveEntryRoot = ensureTargetAppForegroundForRootScan(
                selectedApp = selectedApp,
                targetPackageName = targetPackageName,
                requestId = requestId,
            )

            val rootSnapshot = scanCurrentScreen(
                selectedApp = selectedApp,
                eventClassName = liveEntryRoot.className ?: entryRoot.className ?: eventClassName,
                initialRoot = liveEntryRoot,
                capturePackageName = targetPackageName,
                requestId = requestId,
                progressPrefix = "Mapping the root screen.",
            )

            val rootSequence = tracker.nextScreenSequenceNumber()
            val rootScreenId = screenIdFor(rootSequence)
            val rootScreenFingerprint = ScreenNaming.dedupFingerprint(rootSnapshot.screenName)
            val rootFiles = CaptureFileStore.saveScreen(
                session = session,
                snapshot = rootSnapshot,
                sequenceNumber = rootSequence,
                screenPrefix = "root",
            )
            val resolvedRootLinks = mutableMapOf<PressableElementLinkKey, String>()
            tracker.addScreen(
                screenId = rootScreenId,
                snapshot = rootSnapshot,
                screenFingerprint = rootScreenFingerprint,
                files = rootFiles,
                parentScreenId = null,
                triggerElement = null,
                depth = 0,
            )

            val rootTopSnapshot = rootSnapshot.stepSnapshots.firstOrNull()?.root
                ?: throw IllegalStateException("Root capture did not preserve a top-of-screen snapshot.")
            val rootTopFingerprint = scrollScanCoordinator.viewportFingerprint(rootTopSnapshot)

            val traversalPlan = TraversalPlanner.planRootTraversal(rootSnapshot, blacklist)
            traversalPlan.skippedElements.forEach { skipped ->
                tracker.addEdge(
                    parentScreenId = rootScreenId,
                    element = skipped.element,
                    status = CrawlEdgeStatus.SKIPPED_BLACKLIST,
                    message = skipped.reason,
                )
            }
            CaptureFileStore.saveManifest(session, tracker.buildManifest(CrawlRunStatus.IN_PROGRESS))

            if (traversalPlan.eligibleElements.isNotEmpty()) {
                CrawlerSession.beginTraversingChildren(
                    requestId = requestId,
                    message = "Mapped the root screen. Visiting ${traversalPlan.eligibleElements.size} child target(s).",
                )
            }

            traversalPlan.eligibleElements.forEachIndexed { index, element ->
                try {
                    CrawlerSession.updateProgress(
                        requestId = requestId,
                        message = "Visiting child target ${index + 1} of ${traversalPlan.eligibleElements.size}: ${element.label}",
                    )

                    val topRoot = restoreRootToTop(
                        selectedApp = selectedApp,
                        targetPackageName = targetPackageName,
                        requestId = requestId,
                    ) ?: failCurrentEdge(
                        element = element,
                        message = "Could not restore the root screen before opening '${element.label}'.",
                    )

                    if (scrollScanCoordinator.viewportFingerprint(topRoot) != rootTopFingerprint) {
                        failCurrentEdge(
                            element = element,
                            message = "The root screen no longer matches the original screen before opening '${element.label}'.",
                        )
                    }

                    val targetStepRoot = scrollScanCoordinator.moveToStep(
                        selectedApp = selectedApp,
                        initialRoot = topRoot,
                        targetStepIndex = element.firstSeenStep,
                        tryScrollForward = { path ->
                            val liveRoot = rootInActiveWindow ?: return@moveToStep false
                            performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        },
                        captureCurrentRoot = {
                            captureCurrentRootSnapshot(targetPackageName)
                        },
                        onProgress = { message ->
                            CrawlerSession.updateProgress(requestId, message)
                        },
                    ) ?: failCurrentEdge(
                        element = element,
                        message = "Could not scroll back to '${element.label}' on the root screen.",
                    )

                    val beforeClickFingerprint = scrollScanCoordinator.viewportFingerprint(targetStepRoot)
                    val liveRoot = rootInActiveWindow ?: failCurrentEdge(
                        element = element,
                        message = "Lost the live root before clicking '${element.label}'.",
                    )

                    if (!performClick(liveRoot, element)) {
                        failCurrentEdge(
                            element = element,
                            message = "Could not click '${element.label}' after returning to its screen position.",
                        )
                    }

                    val childInitialRoot = captureCurrentRootSnapshot(expectedPackageName = null) ?: failCurrentEdge(
                        element = element,
                        message = "The target app was lost immediately after clicking '${element.label}'.",
                    )
                    val childPackageName = childInitialRoot.packageName ?: targetPackageName
                    val afterClickFingerprint = scrollScanCoordinator.viewportFingerprint(childInitialRoot)
                    if (afterClickFingerprint == beforeClickFingerprint || afterClickFingerprint == rootTopFingerprint) {
                        tracker.addEdge(
                            parentScreenId = rootScreenId,
                            element = element,
                            status = CrawlEdgeStatus.SKIPPED_NO_NAVIGATION,
                            message = "No distinct child screen detected.",
                        )
                        CaptureFileStore.saveManifest(session, tracker.buildManifest(CrawlRunStatus.IN_PROGRESS))
                        return@forEachIndexed
                    }

                    CrawlerSession.updateProgress(
                        requestId = requestId,
                        message = "Mapping child screen opened by '${element.label}'.",
                    )

                    val childSnapshot = scanCurrentScreen(
                        selectedApp = selectedApp,
                        eventClassName = childInitialRoot.className,
                        initialRoot = childInitialRoot,
                        capturePackageName = childPackageName,
                        requestId = requestId,
                        progressPrefix = "Mapping child screen '${element.label}'.",
                    )
                    val childScreenFingerprint = ScreenNaming.dedupFingerprint(childSnapshot.screenName)
                    val existingChildScreenId = tracker.findScreenIdByFingerprint(childScreenFingerprint)

                    if (existingChildScreenId != null) {
                        val existingChildScreen = tracker.findScreen(existingChildScreenId)
                            ?: throw IllegalStateException(
                                "Existing screen '$existingChildScreenId' was not found for fingerprint '$childScreenFingerprint'."
                            )
                        tracker.addEdge(
                            parentScreenId = rootScreenId,
                            childScreenId = existingChildScreenId,
                            element = element,
                            status = CrawlEdgeStatus.LINKED_EXISTING,
                            message = "Linked to existing screen '${existingChildScreen.screenName}'.",
                        )
                        resolvedRootLinks[element.toLinkKey()] = File(existingChildScreen.htmlPath).name
                    } else {
                        val childSequence = tracker.nextScreenSequenceNumber()
                        val childScreenId = screenIdFor(childSequence)
                        val childFiles = CaptureFileStore.saveScreen(
                            session = session,
                            snapshot = childSnapshot,
                            sequenceNumber = childSequence,
                            screenPrefix = "child",
                        )
                        tracker.addScreen(
                            screenId = childScreenId,
                            snapshot = childSnapshot,
                            screenFingerprint = childScreenFingerprint,
                            files = childFiles,
                            parentScreenId = rootScreenId,
                            triggerElement = element,
                            depth = 1,
                        )
                        tracker.addEdge(
                            parentScreenId = rootScreenId,
                            childScreenId = childScreenId,
                            element = element,
                            status = CrawlEdgeStatus.CAPTURED,
                            message = "Captured child screen '${childSnapshot.screenName}'.",
                        )
                        resolvedRootLinks[element.toLinkKey()] = childFiles.htmlFile.name
                    }
                    CaptureFileStore.rewriteScreenHtml(
                        files = rootFiles,
                        snapshot = rootSnapshot,
                        resolvedChildLinks = resolvedRootLinks,
                    )
                    CaptureFileStore.saveManifest(session, tracker.buildManifest(CrawlRunStatus.IN_PROGRESS))

                    val returnedRoot = navigateBackToRoot(
                        selectedApp = selectedApp,
                        targetPackageName = targetPackageName,
                        expectedRootFingerprint = rootTopFingerprint,
                        requestId = requestId,
                    ) ?: failCurrentEdge(
                        element = element,
                        message = "Captured '${childSnapshot.screenName}', but could not return to the original root screen afterward.",
                    )

                    if (scrollScanCoordinator.viewportFingerprint(returnedRoot) != rootTopFingerprint) {
                        failCurrentEdge(
                            element = element,
                            message = "Returned from '${childSnapshot.screenName}', but the root screen no longer matches the original map.",
                        )
                    }
                } catch (edgeFailure: RecoverableChildTraversalException) {
                    val recoveredToRoot = recoverToRootAfterEdgeFailure(
                        selectedApp = selectedApp,
                        targetPackageName = targetPackageName,
                        expectedRootFingerprint = rootTopFingerprint,
                        requestId = requestId,
                    )
                    if (!recoveredToRoot) {
                        abortPartialCapture(
                            tracker = tracker,
                            rootScreenId = rootScreenId,
                            session = session,
                            rootSnapshot = rootSnapshot,
                            rootFiles = rootFiles,
                            message = edgeFailure.message.orEmpty(),
                            failedElement = edgeFailure.element,
                        )
                    }

                    tracker.addEdge(
                        parentScreenId = rootScreenId,
                        element = edgeFailure.element,
                        status = CrawlEdgeStatus.FAILED,
                        message = edgeFailure.message,
                    )
                    CaptureFileStore.saveManifest(session, tracker.buildManifest(CrawlRunStatus.IN_PROGRESS))
                    CrawlerSession.updateProgress(
                        requestId = requestId,
                        message = "Recovered to the root screen after '${edgeFailure.element.label}' failed. Continuing with the next target.",
                    )
                }
            }

            val manifestFile = CaptureFileStore.saveManifest(
                session,
                tracker.buildManifest(CrawlRunStatus.COMPLETED),
            )
            CrawlerSession.completeCapture(
                requestId = requestId,
                summary = buildSummary(
                    tracker = tracker,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    manifestFile = manifestFile,
                ),
            )
        } catch (partialAbort: PartialCrawlAbortException) {
            CrawlerSession.abortCapture(
                requestId = requestId,
                summary = partialAbort.summary,
                message = partialAbort.message.orEmpty(),
            )
        } catch (cancellation: CancellationException) {
            DiagnosticLogger.log(
                "Capture coroutine canceled for requestId=$requestId; treating as expected reschedule/shutdown."
            )
            throw cancellation
        } catch (error: Throwable) {
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

    private fun entryScreenResetFailureMessage(stopReason: EntryScreenResetStopReason): String {
        return when (stopReason) {
            EntryScreenResetStopReason.NO_BACK_AFFORDANCE ->
                "Reset to the first screen unexpectedly failed after the back button disappeared."

            EntryScreenResetStopReason.BACK_ACTION_FAILED ->
                "Could not reset to the first screen because a visible in-app back button was still present when Android back stopped working."

            EntryScreenResetStopReason.LEFT_TARGET_APP ->
                "Could not reset to the first screen because backing out left the target app before the in-app back button disappeared."

            EntryScreenResetStopReason.MAX_ATTEMPTS_REACHED ->
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
                performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            },
            tryScrollBackward = { path ->
                val liveRoot = rootInActiveWindow ?: return@scan false
                performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            },
            captureCurrentRoot = {
                captureCurrentRootSnapshot(capturePackageName)
            },
            onProgress = { message ->
                CrawlerSession.updateProgress(requestId, "$progressPrefix $message")
            },
        )
    }

    private suspend fun restoreRootToTop(
        selectedApp: SelectedAppRef,
        targetPackageName: String,
        requestId: Long,
    ): AccessibilityNodeSnapshot? {
        val currentRoot = captureCurrentRootSnapshot(targetPackageName) ?: return null
        return scrollScanCoordinator.rewindToTop(
            selectedApp = selectedApp,
            initialRoot = currentRoot,
            tryScrollBackward = { path ->
                val liveRoot = rootInActiveWindow ?: return@rewindToTop false
                performScroll(liveRoot, path, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            },
            captureCurrentRoot = {
                captureCurrentRootSnapshot(targetPackageName)
            },
            onProgress = { message ->
                CrawlerSession.updateProgress(requestId, message)
            },
        )
    }

    private suspend fun navigateBackToRoot(
        selectedApp: SelectedAppRef,
        targetPackageName: String,
        expectedRootFingerprint: String,
        requestId: Long,
    ): AccessibilityNodeSnapshot? {
        repeat(maxBackNavigationAttempts) { attempt ->
            CrawlerSession.updateProgress(
                requestId = requestId,
                message = "Returning to the root screen. Back attempt ${attempt + 1} of $maxBackNavigationAttempts.",
            )
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                return null
            }

            val rewoundRoot = restoreRootToTop(
                selectedApp = selectedApp,
                targetPackageName = targetPackageName,
                requestId = requestId,
            ) ?: return@repeat

            if (scrollScanCoordinator.viewportFingerprint(rewoundRoot) == expectedRootFingerprint) {
                return rewoundRoot
            }
        }

        return null
    }

    private suspend fun recoverToRootAfterEdgeFailure(
        selectedApp: SelectedAppRef,
        targetPackageName: String,
        expectedRootFingerprint: String,
        requestId: Long,
    ): Boolean {
        val currentRoot = captureCurrentRootSnapshot(expectedPackageName = null)
        if (currentRoot?.packageName == targetPackageName) {
            val rewoundRoot = restoreRootToTop(
                selectedApp = selectedApp,
                targetPackageName = targetPackageName,
                requestId = requestId,
            )
            if (rewoundRoot != null && scrollScanCoordinator.viewportFingerprint(rewoundRoot) == expectedRootFingerprint) {
                return true
            }
        }

        val returnedRoot = navigateBackToRoot(
            selectedApp = selectedApp,
            targetPackageName = targetPackageName,
            expectedRootFingerprint = expectedRootFingerprint,
            requestId = requestId,
        )
        return returnedRoot != null &&
            scrollScanCoordinator.viewportFingerprint(returnedRoot) == expectedRootFingerprint
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

    private fun performScroll(
        root: AccessibilityNodeInfo,
        childIndexPath: List<Int>,
        action: Int,
    ): Boolean {
        val pathNodes = resolvePathNodes(root, childIndexPath)
        if (attemptActionOnCandidates(pathNodes.asReversed(), action, "path")) {
            return true
        }

        val fallbackCandidates = collectScrollableCandidates(root)
            .filterNot { candidate -> pathNodes.any { it === candidate } }
        if (attemptActionOnCandidates(fallbackCandidates, action, "fallback")) {
            return true
        }

        DiagnosticLogger.log(
            "Scroll action ${actionName(action)} failed for path=$childIndexPath; no candidate accepted the gesture."
        )
        return false
    }

    private fun performClick(
        root: AccessibilityNodeInfo,
        element: PressableElement,
    ): Boolean {
        val pathNodes = resolvePathNodes(root, element.childIndexPath)
            .filter { node -> node.isVisibleToUser && node.isEnabled }
        if (attemptActionOnCandidates(pathNodes.asReversed(), AccessibilityNodeInfo.ACTION_CLICK, "path")) {
            return true
        }

        val fallbackCandidates = collectClickableCandidates(root, element)
            .filterNot { candidate -> pathNodes.any { it === candidate } }
        if (attemptActionOnCandidates(fallbackCandidates, AccessibilityNodeInfo.ACTION_CLICK, "fallback")) {
            return true
        }

        DiagnosticLogger.log(
            "Click action failed for '${element.label}' with path=${element.childIndexPath}; no candidate accepted the gesture."
        )
        return false
    }

    private fun resolvePathNodes(
        root: AccessibilityNodeInfo,
        childIndexPath: List<Int>,
    ): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = root
        nodes += root
        childIndexPath.forEach { childIndex ->
            current = current?.getChild(childIndex) ?: return nodes
            nodes += current!!
        }
        return nodes
    }

    private fun attemptActionOnCandidates(
        candidates: List<AccessibilityNodeInfo>,
        requestedAction: Int,
        source: String,
    ): Boolean {
        candidates.forEach { candidate ->
            val actionIds = preferredActionIds(candidate, requestedAction)
            actionIds.forEach { actionId ->
                val success = candidate.performAction(actionId)
                DiagnosticLogger.log(
                    "Tried ${actionName(actionId)} on ${describeNode(candidate)} from $source candidate; success=$success"
                )
                if (success) {
                    return true
                }
            }
        }
        return false
    }

    private fun preferredActionIds(
        node: AccessibilityNodeInfo,
        requestedAction: Int,
    ): List<Int> {
        val supported = node.actionList.map { it.id }.toSet()
        val preferred = when (requestedAction) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> listOf(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id,
            )

            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> listOf(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id,
            )

            AccessibilityNodeInfo.ACTION_CLICK -> listOf(
                AccessibilityNodeInfo.ACTION_CLICK,
            )

            else -> listOf(requestedAction)
        }

        val preferredSupported = preferred.filter { actionId -> actionId in supported }
        return (preferredSupported + preferred.filterNot { it in preferredSupported }).distinct()
    }

    private fun collectScrollableCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<LiveCandidate>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (node.isVisibleToUser && node.isScrollable) {
                candidates += LiveCandidate(node = node, score = scrollableCandidateScore(node, depth))
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child ->
                    walk(child, depth + 1)
                }
            }
        }

        walk(root, depth = 0)
        return candidates.sortedByDescending { it.score }.map { it.node }
    }

    private fun collectClickableCandidates(
        root: AccessibilityNodeInfo,
        element: PressableElement,
    ): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<LiveCandidate>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val score = clickableCandidateScore(node, element, depth)
            if (score > 0) {
                candidates += LiveCandidate(node = node, score = score)
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child ->
                    walk(child, depth + 1)
                }
            }
        }

        walk(root, depth = 0)
        return candidates.sortedByDescending { it.score }.map { it.node }
    }

    private fun scrollableCandidateScore(node: AccessibilityNodeInfo, depth: Int): Int {
        val className = node.className?.toString().orEmpty()
        val classScore = when {
            className.contains("RecyclerView") -> 600
            className.contains("ListView") -> 575
            className.contains("GridView") -> 550
            className.contains("NestedScrollView") -> 525
            className.contains("ScrollView") -> 500
            className.endsWith("LinearLayout") -> 350
            else -> 250
        }
        return classScore + depth
    }

    private fun clickableCandidateScore(
        node: AccessibilityNodeInfo,
        element: PressableElement,
        depth: Int,
    ): Int {
        if (!node.isVisibleToUser || !node.isEnabled) {
            return Int.MIN_VALUE
        }
        if (!node.isClickable && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            return Int.MIN_VALUE
        }

        var score = 100 - depth
        if (node.viewIdResourceName == element.resourceId && !element.resourceId.isNullOrBlank()) {
            score += 1_000
        }
        if (node.className?.toString() == element.className && !element.className.isNullOrBlank()) {
            score += 300
        }
        if (resolveLiveLabel(node) == element.label) {
            score += 700
        }
        if (node.isCheckable == element.checkable) {
            score += 75
        }
        if (isNodeChecked(node) == element.checked) {
            score += 25
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (isBoundsMatch(bounds, element.bounds)) {
            score += 200
        }

        return score
    }

    private fun resolveLiveLabel(node: AccessibilityNodeInfo): String? {
        val directText = node.text?.toString()?.trim().orEmpty()
        if (directText.isNotEmpty()) {
            return directText
        }

        val directDescription = node.contentDescription?.toString()?.trim().orEmpty()
        if (directDescription.isNotEmpty()) {
            return directDescription
        }

        findNestedTitleLabel(node)?.let { return it }
        return findNestedTextLabel(node)
    }

    private fun findNestedTitleLabel(node: AccessibilityNodeInfo): String? {
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            if (child.viewIdResourceName?.substringAfterLast('/') == "title") {
                val label = resolveLiveLabel(child)
                if (!label.isNullOrBlank()) {
                    return label
                }
            }
            findNestedTitleLabel(child)?.let { return it }
        }
        return null
    }

    private fun findNestedTextLabel(node: AccessibilityNodeInfo): String? {
        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            val label = resolveLiveLabel(child)
            if (!label.isNullOrBlank()) {
                return label
            }
            findNestedTextLabel(child)?.let { return it }
        }
        return null
    }

    private fun isBoundsMatch(bounds: Rect, targetBounds: String): Boolean {
        val parsed = parseBounds(targetBounds) ?: return false
        return abs(bounds.left - parsed.left) <= clickBoundsTolerancePx &&
            abs(bounds.top - parsed.top) <= clickBoundsTolerancePx &&
            abs(bounds.right - parsed.right) <= clickBoundsTolerancePx &&
            abs(bounds.bottom - parsed.bottom) <= clickBoundsTolerancePx
    }

    private fun parseBounds(bounds: String): Rect? {
        val match = boundsRegex.matchEntire(bounds) ?: return null
        return Rect(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
        )
    }

    @Suppress("DEPRECATION")
    private fun isNodeChecked(node: AccessibilityNodeInfo): Boolean = node.isChecked

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return buildString {
            append(node.className?.toString().orEmpty())
            append('[')
            append(node.viewIdResourceName.orEmpty())
            append(']')
            append('@')
            append(bounds.toShortString())
        }
    }

    private fun actionName(action: Int): String {
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
            AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "ACTION_SCROLL_DOWN"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "ACTION_SCROLL_UP"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id -> "ACTION_PAGE_DOWN"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id -> "ACTION_PAGE_UP"
            else -> "ACTION_$action"
        }
    }

    private fun screenIdFor(sequenceNumber: Int): String {
        return "screen_%03d".format(sequenceNumber)
    }

    private fun buildSummary(
        tracker: CrawlRunTracker,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        manifestFile: File,
    ): CrawlRunSummary {
        return CrawlRunSummary(
            rootScreenName = rootSnapshot.screenName,
            rootFiles = rootFiles,
            manifestFile = manifestFile,
            rootScrollStepCount = rootSnapshot.scrollStepCount,
            capturedScreenCount = tracker.capturedScreenCount(),
            capturedChildScreenCount = tracker.capturedChildScreenCount(),
            skippedElementCount = tracker.skippedElementCount(),
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
                tracker = tracker,
                rootSnapshot = rootSnapshot,
                rootFiles = rootFiles,
                manifestFile = manifestFile,
            ),
            message = message,
        )
    }

    private data class LiveCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
    )

    private class PartialCrawlAbortException(
        val summary: CrawlRunSummary,
        message: String,
    ) : IllegalStateException(message)

    private class RecoverableChildTraversalException(
        val element: PressableElement,
        message: String,
    ) : IllegalStateException(message)
}
