package com.example.apptohtml

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import com.example.apptohtml.crawler.AccessibilityTreeSnapshotter
import com.example.apptohtml.crawler.CaptureFileStore
import com.example.apptohtml.crawler.CrawlerPhase
import com.example.apptohtml.crawler.CrawlerSession
import com.example.apptohtml.crawler.ScrollScanCoordinator
import com.example.apptohtml.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppToHtmlAccessibilityService : AccessibilityService() {
    private val scrollScanCoordinator = ScrollScanCoordinator()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    companion object {
        private const val captureDebounceMillis = 350L
        private const val scrollSettleDelayMillis = 350L
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

            CrawlerSession.beginScanning(
                requestId = requestId,
                message = "Scanning the first visible screen.",
            )

            val initialRoot = AccessibilityTreeSnapshotter.captureRootSnapshot(root)
            val snapshot = scrollScanCoordinator.scan(
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
                    delay(scrollSettleDelayMillis)
                    val liveRoot = rootInActiveWindow ?: return@scan null
                    if (liveRoot.packageName?.toString() != targetPackageName) {
                        return@scan null
                    }
                    AccessibilityTreeSnapshotter.captureRootSnapshot(liveRoot)
                },
                onProgress = { message ->
                    CrawlerSession.updateProgress(requestId, message)
                },
            )
            val files = CaptureFileStore.save(this, snapshot)
            CrawlerSession.completeCapture(
                requestId = requestId,
                screenName = snapshot.screenName,
                files = files,
                scrollStepCount = snapshot.scrollStepCount,
            )
        } catch (error: Throwable) {
            CrawlerSession.failRequest(
                requestId = requestId,
                message = "Failed to capture the first screen: ${error.message ?: "unknown error"}.",
            )
        }
    }

    private fun performScroll(
        root: AccessibilityNodeInfo,
        childIndexPath: List<Int>,
        action: Int,
    ): Boolean {
        val pathNodes = resolvePathNodes(root, childIndexPath)
        if (attemptScrollOnCandidates(pathNodes.asReversed(), action, "path")) {
            return true
        }

        val fallbackCandidates = collectScrollableCandidates(root)
            .filterNot { candidate -> pathNodes.any { it === candidate } }
        if (attemptScrollOnCandidates(fallbackCandidates, action, "fallback")) {
            return true
        }

        DiagnosticLogger.log(
            "Scroll action ${actionName(action)} failed for path=$childIndexPath; no candidate accepted the gesture."
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

    private fun attemptScrollOnCandidates(
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

            else -> listOf(requestedAction)
        }

        val preferredSupported = preferred.filter { actionId -> actionId in supported }
        return (preferredSupported + preferred.filterNot { it in preferredSupported }).distinct()
    }

    private fun collectScrollableCandidates(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<LiveScrollableCandidate>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (node.isVisibleToUser && node.isScrollable) {
                candidates += LiveScrollableCandidate(node = node, score = scrollableCandidateScore(node, depth))
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
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "ACTION_SCROLL_DOWN"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "ACTION_SCROLL_UP"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id -> "ACTION_PAGE_DOWN"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id -> "ACTION_PAGE_UP"
            else -> "ACTION_$action"
        }
    }

    private data class LiveScrollableCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
    )
}
