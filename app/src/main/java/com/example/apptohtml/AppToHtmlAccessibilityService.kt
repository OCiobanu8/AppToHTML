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
import com.example.apptohtml.crawler.ExternalPackageDecisionContext
import com.example.apptohtml.crawler.PathReplayResolver
import com.example.apptohtml.crawler.PauseDecision
import com.example.apptohtml.crawler.PauseProgressSnapshot
import com.example.apptohtml.crawler.PauseReason
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
    @Volatile
    private var activeCrawlLogger: CrawlLogger? = null

    companion object {
        private const val captureDebounceMillis = 350L
        private const val scrollSettleDelayMillis = 350L
        private const val maxBackNavigationAttempts = 3
        private const val clickBoundsTolerancePx = 24
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
                        return performScroll(liveRoot, childIndexPath, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    }

                    override fun scrollBackward(childIndexPath: List<Int>): Boolean {
                        val liveRoot = rootInActiveWindow ?: return false
                        return performScroll(liveRoot, childIndexPath, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    }

                    override fun click(element: PressableElement): Boolean {
                        val liveRoot = rootInActiveWindow ?: return false
                        return performClick(liveRoot, element)
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
                        externalPackageContext: ExternalPackageDecisionContext?,
                    ): PauseDecision {
                        return CrawlerSession.pauseForDecision(
                            reason = reason,
                            snapshot = snapshot,
                            externalPackageContext = externalPackageContext,
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
                createSession = { startedAt ->
                    CaptureFileStore.createSession(
                        context = this@AppToHtmlAccessibilityService,
                        packageName = targetPackageName,
                        startedAt = startedAt,
                    )
                },
                scrollScanCoordinator = scrollScanCoordinator,
            )

            when (
                val result = coordinator.crawl(
                    initialRoot = initialRoot,
                    eventClassName = eventClassName,
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
        expectedRootLogicalFingerprint: String,
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

            if (scrollScanCoordinator.logicalViewportFingerprint(rewoundRoot) == expectedRootLogicalFingerprint) {
                return rewoundRoot
            }
        }

        return null
    }

    private suspend fun recoverToRootAfterEdgeFailure(
        selectedApp: SelectedAppRef,
        targetPackageName: String,
        expectedRootLogicalFingerprint: String,
        requestId: Long,
    ): Boolean {
        val currentRoot = captureCurrentRootSnapshot(expectedPackageName = null)
        if (currentRoot?.packageName == targetPackageName) {
            val rewoundRoot = restoreRootToTop(
                selectedApp = selectedApp,
                targetPackageName = targetPackageName,
                requestId = requestId,
            )
            if (
                rewoundRoot != null &&
                scrollScanCoordinator.logicalViewportFingerprint(rewoundRoot) == expectedRootLogicalFingerprint
            ) {
                return true
            }
        }

        val returnedRoot = navigateBackToRoot(
            selectedApp = selectedApp,
            targetPackageName = targetPackageName,
            expectedRootLogicalFingerprint = expectedRootLogicalFingerprint,
            requestId = requestId,
        )
        return returnedRoot != null &&
            scrollScanCoordinator.logicalViewportFingerprint(returnedRoot) == expectedRootLogicalFingerprint
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
        val pathResolution = resolvePathNodes(root, childIndexPath)
        logPathDivergenceIfNeeded(action, pathResolution)
        val pathNodes = pathResolution.usableNodes()
        activeCrawlLogger?.info(
            "live_action_start type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "resolvedNodeCount=${pathNodes.size} resolvedPathDepth=${pathResolution.resolvedDepth} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} candidateSource=path"
        )
        if (attemptActionOnCandidates(pathNodes.asReversed(), action, "path")) {
            return true
        }

        val fallbackCandidates = collectScrollableCandidates(root)
            .filterNot { candidate -> pathNodes.any { it === candidate } }
        activeCrawlLogger?.info(
            "live_action_fallback type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "fallbackCandidateCount=${fallbackCandidates.size}"
        )
        if (attemptActionOnCandidates(fallbackCandidates, action, "fallback")) {
            return true
        }

        activeCrawlLogger?.warn(
            "live_action_failed type=${actionName(action)} intendedChildIndexPath=$childIndexPath " +
                "resolvedNodeCount=${pathNodes.size} fallbackCandidateCount=${fallbackCandidates.size} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()}"
        )
        DiagnosticLogger.log(
            "Scroll action ${actionName(action)} failed for path=$childIndexPath; no candidate accepted the gesture."
        )
        return false
    }

    private fun performClick(
        root: AccessibilityNodeInfo,
        element: PressableElement,
    ): Boolean {
        val pathResolution = resolvePathNodes(root, element.childIndexPath)
        logPathDivergenceIfNeeded(AccessibilityNodeInfo.ACTION_CLICK, pathResolution)
        val pathNodes = pathResolution.usableNodes()
            .filter { node -> node.isVisibleToUser && node.isEnabled }
        activeCrawlLogger?.info(
            "live_action_start type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "resolvedNodeCount=${pathNodes.size} resolvedPathDepth=${pathResolution.resolvedDepth} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} candidateSource=path " +
                "label=${quoteForLog(element.label)} resourceId=${quoteForLog(element.resourceId.orEmpty())} " +
                "className=${quoteForLog(element.className.orEmpty())} bounds=${quoteForLog(element.bounds)}"
        )
        if (attemptActionOnCandidates(pathNodes.asReversed(), AccessibilityNodeInfo.ACTION_CLICK, "path")) {
            return true
        }

        val target = clickFallbackTargetFor(element)
        val allCandidates = collectClickFallbackCandidates(root)
            .filterNot { entry -> pathNodes.any { it === entry.node } }
        val matches = ClickFallbackMatcher.selectMatches(
            candidates = allCandidates.map { it.candidate },
            target = target,
            boundsTolerancePx = clickBoundsTolerancePx,
        )
        val fallbackCandidates = matches.map { match -> match.candidate.handle }
        activeCrawlLogger?.info(
            "live_action_fallback type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "fallbackCandidateCount=${fallbackCandidates.size} totalLiveCandidateCount=${allCandidates.size} " +
                "label=${quoteForLog(element.label)} eligibilityReasons=${quoteForLog(formatEligibilityReasons(matches))} " +
                "topRankScore=${matches.firstOrNull()?.rankScore ?: 0}"
        )
        if (attemptActionOnCandidates(fallbackCandidates, AccessibilityNodeInfo.ACTION_CLICK, "fallback")) {
            return true
        }

        activeCrawlLogger?.warn(
            "live_action_failed type=ACTION_CLICK intendedChildIndexPath=${element.childIndexPath} " +
                "resolvedNodeCount=${pathNodes.size} fallbackCandidateCount=${fallbackCandidates.size} " +
                "totalLiveCandidateCount=${allCandidates.size} " +
                "pathResolutionStatus=${pathResolution.status.name.lowercase()} label=${quoteForLog(element.label)}"
        )
        DiagnosticLogger.log(
            "Click action failed for '${element.label}' with path=${element.childIndexPath}; no candidate accepted the gesture."
        )
        return false
    }

    private fun formatEligibilityReasons(
        matches: List<ClickFallbackMatcher.Match<AccessibilityNodeInfo>>,
    ): String {
        if (matches.isEmpty()) {
            return ""
        }
        return matches
            .groupingBy { it.eligibilityReason.name.lowercase() }
            .eachCount()
            .entries
            .joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private fun clickFallbackTargetFor(element: PressableElement): ClickFallbackMatcher.Target {
        return ClickFallbackMatcher.Target(
            label = element.label,
            resourceId = element.resourceId,
            className = element.className,
            bounds = element.bounds,
            checkable = element.checkable,
            checked = element.checked,
        )
    }

    private fun resolvePathNodes(
        root: AccessibilityNodeInfo,
        childIndexPath: List<Int>,
    ): PathReplayResolver.Resolution<AccessibilityNodeInfo> {
        return PathReplayResolver.resolve(
            root = root,
            childIndexPath = childIndexPath,
            childCount = { node -> node.childCount },
            childAt = { node, index -> node.getChild(index) },
        )
    }

    private fun logPathDivergenceIfNeeded(
        action: Int,
        resolution: PathReplayResolver.Resolution<AccessibilityNodeInfo>,
    ) {
        if (resolution.status == PathReplayResolver.ResolutionStatus.FULL) {
            return
        }

        activeCrawlLogger?.warn(
            "live_action_path_diverged type=${actionName(action)} intendedChildIndexPath=${resolution.intendedPath} " +
                "resolvedDepth=${resolution.resolvedDepth} failingChildIndex=${resolution.failingChildIndex} " +
                "availableChildCount=${resolution.availableChildCount} status=${resolution.status.name.lowercase()}"
        )
        DiagnosticLogger.log(
            "Accessibility path replay diverged for ${actionName(action)} path=${resolution.intendedPath} " +
                "resolvedDepth=${resolution.resolvedDepth} failingChildIndex=${resolution.failingChildIndex} " +
                "availableChildCount=${resolution.availableChildCount}."
        )
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
                activeCrawlLogger?.info(
                    "live_action_attempt requestedAction=${actionName(requestedAction)} source=$source " +
                        "candidate=${quoteForLog(describeNode(candidate))} actionId=${actionName(actionId)} success=$success"
                )
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

    private fun collectClickFallbackCandidates(
        root: AccessibilityNodeInfo,
    ): List<LiveClickCandidate> {
        val candidates = mutableListOf<LiveClickCandidate>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            val supportsClick = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (node.isVisibleToUser && node.isEnabled && (node.isClickable || supportsClick)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                candidates += LiveClickCandidate(
                    node = node,
                    candidate = ClickFallbackMatcher.Candidate(
                        handle = node,
                        visible = node.isVisibleToUser,
                        enabled = node.isEnabled,
                        clickable = node.isClickable,
                        supportsClickAction = supportsClick,
                        resolvedLabel = resolveLiveLabel(node),
                        resourceId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        bounds = ClickFallbackMatcher.Bounds(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                        ),
                        checkable = node.isCheckable,
                        checked = isNodeChecked(node),
                        depth = depth,
                    ),
                )
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { child ->
                    walk(child, depth + 1)
                }
            }
        }

        walk(root, depth = 0)
        return candidates
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

    private fun quoteForLog(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
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

    private data class LiveCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
    )

    private data class LiveClickCandidate(
        val node: AccessibilityNodeInfo,
        val candidate: ClickFallbackMatcher.Candidate<AccessibilityNodeInfo>,
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
