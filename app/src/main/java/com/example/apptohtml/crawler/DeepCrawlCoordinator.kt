package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.ArrayDeque

internal class DeepCrawlCoordinator(
    private val selectedApp: SelectedAppRef,
    private val host: Host,
    private val loadBlacklist: () -> CrawlBlacklist,
    private val createSession: (Long, Boolean) -> CrawlSessionDirectory,
    private val pauseConfig: PauseCheckpointConfig = PauseCheckpointConfig(),
    private val scrollScanCoordinator: ScrollScanCoordinator = ScrollScanCoordinator(),
    private val destinationSettler: DestinationSettler = DestinationSettler(),
    private val scanScreenOverride: (suspend (
        eventClassName: String?,
        initialRoot: AccessibilityNodeSnapshot,
        capturePackageName: String?,
        progressPrefix: String,
        preferredName: String?,
    ) -> ScreenSnapshot)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val postClickSettleTimeProvider: () -> Long = timeProvider,
    private val maxPostClickSettleMillis: Long = DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS,
    private val entryRestoreSettleTimeProvider: () -> Long = timeProvider,
    private val maxEntryRestoreSettleMillis: Long = DEFAULT_MAX_ENTRY_RESTORE_SETTLE_MILLIS,
) {
    private val resolvedLinksByScreenId = mutableMapOf<String, MutableMap<PressableElementLinkKey, String>>()
    private val crashContext = CrawlCrashContext()
    private var crawlLogger: CrawlLogger? = null
    private var lastLoggedManifestStatus: CrawlRunStatus? = null
    private val allowedPackageNames = linkedSetOf<String>()

    suspend fun crawl(
        initialRoot: AccessibilityNodeSnapshot,
        eventClassName: String?,
        intent: CrawlStartIntent = CrawlStartIntent.RESUME,
        resumeMode: ResumeMode = ResumeMode.ContinueAuto,
    ): DeepCrawlOutcome {
        val crawlStartedAt = timeProvider()
        val blacklist = loadBlacklist()
        val wipeExisting = intent == CrawlStartIntent.NEW_CRAWL
        val session = createSession(crawlStartedAt, wipeExisting)
        val loaded = if (intent == CrawlStartIntent.RESUME) {
            SavedCrawlLoader.load(session.directory)?.takeIf { it.screens.isNotEmpty() }
        } else null
        val tracker = if (loaded != null) {
            CrawlRunTracker.fromExistingState(
                sessionId = session.sessionId,
                packageName = selectedApp.packageName,
                startedAt = crawlStartedAt,
                screens = loaded.screens,
                edges = loaded.edges,
                screenFingerprintToId = loaded.screenFingerprintToId,
                rootScreenId = loaded.rootScreenId,
                nextScreenSequence = loaded.nextScreenSequence,
                nextEdgeSequence = loaded.nextEdgeSequence,
            )
        } else {
            CrawlRunTracker(
                sessionId = session.sessionId,
                packageName = selectedApp.packageName,
                startedAt = crawlStartedAt,
            )
        }
        val pauseTracker = PauseCheckpointTracker(
            config = pauseConfig,
            startedAtMs = crawlStartedAt,
            timeProvider = timeProvider,
        )
        val logger = CrawlLogger(
            sessionId = session.sessionId,
            logFile = session.logFile,
            timeProvider = timeProvider,
        )
        crawlLogger = logger
        host.setActiveCrawlLogger(logger)
        lastLoggedManifestStatus = null
        crashContext.reset()
        allowedPackageNames.clear()
        allowedPackageNames += selectedApp.packageName
        if (loaded != null) {
            allowedPackageNames += loaded.allowedPackages
        }
        resolvedLinksByScreenId.clear()
        loaded?.resolvedLinks?.forEach { (parentId, links) ->
            resolvedLinksByScreenId[parentId] = links.toMutableMap()
        }
        logger.info(
            "crawl_start startedAt=$crawlStartedAt packageName=${selectedApp.packageName} " +
                "appName=${selectedApp.appName.ifBlank { "<blank>" }} initialEventClass=${eventClassName.orEmpty()} " +
                "manifestFile=${session.manifestFile.absolutePath} logFile=${session.logFile.absolutePath} " +
                "intent=${intent.name.lowercase()} resumeMode=${resumeMode.toLogString()} resumeLoaded=${loaded != null}"
        )

        try {
            val liveEntryRoot = restoreToEntryScreenOrRelaunch(
                expectedEntryLogicalFingerprint = null,
                preferRelaunchWhenEntryIsAmbiguous = true,
            )
                ?: throw IllegalStateException(
                    "Target app left the foreground while resetting to the first screen."
                )

            val rootScreenId: String
            val rootFiles: CapturedScreenFiles
            val rootSnapshot: ScreenSnapshot
            val entryScreenLogicalFingerprint: String

            if (loaded != null) {
                val loadedRoot = loaded.screens.firstOrNull { it.depth == 0 }
                    ?: throw IllegalStateException("Loaded crawl has no root screen.")
                rootScreenId = loadedRoot.screenId
                rootFiles = filesFor(loadedRoot)
                rootSnapshot = scanCurrentScreen(
                    eventClassName = liveEntryRoot.className ?: initialRoot.className ?: eventClassName,
                    initialRoot = liveEntryRoot,
                    capturePackageName = selectedApp.packageName,
                    progressPrefix = "Mapping the root screen.",
                    preferredName = loadedRoot.screenName,
                )
                entryScreenLogicalFingerprint = scrollScanCoordinator.logicalEntryViewportFingerprint(
                    rootSnapshot.stepSnapshots.firstOrNull() ?.root ?: liveEntryRoot
                )
                rememberScreen(rootScreenId, loadedRoot.screenName)
                logger.info(
                    "crawl_resume_hydrated rootScreenId=$rootScreenId rootScreenName=${quote(loadedRoot.screenName)} " +
                        "screensLoaded=${loaded.screens.size} edgesLoaded=${loaded.edges.size} " +
                        "allowedPackagesLoaded=${quote(formatAllowedPackageNames())}"
                )
            } else {
                rootSnapshot = scanCurrentScreen(
                    eventClassName = liveEntryRoot.className ?: initialRoot.className ?: eventClassName,
                    initialRoot = liveEntryRoot,
                    capturePackageName = selectedApp.packageName,
                    progressPrefix = "Mapping the root screen.",
                )
                entryScreenLogicalFingerprint = scrollScanCoordinator.logicalEntryViewportFingerprint(
                    rootSnapshot.stepSnapshots.firstOrNull()?.root ?: liveEntryRoot
                )

                val rootSequence = tracker.nextScreenSequenceNumber()
                rootScreenId = screenIdFor(rootSequence)
                val rootScreenIdentity = screenIdentityFor(
                    snapshot = rootSnapshot,
                    root = rootSnapshot.mergedRoot ?: liveEntryRoot,
                )
                val rootScreenFingerprint = rootScreenIdentity.fingerprint
                rootFiles = CaptureFileStore.saveScreen(
                    session = session,
                    snapshot = rootSnapshot,
                    screenId = rootScreenId,
                )
                tracker.addScreen(
                    screenId = rootScreenId,
                    snapshot = rootSnapshot,
                    screenFingerprint = rootScreenFingerprint,
                    replayFingerprint = entryScreenLogicalFingerprint,
                    indexFingerprint = rootScreenIdentity.canLinkToExisting,
                    files = rootFiles,
                    parentScreenId = null,
                    triggerElement = null,
                    route = CrawlRoute(),
                    depth = 0,
                )
                rewriteScreenXmlFor(
                    tracker = tracker,
                    snapshot = rootSnapshot,
                    screenId = rootScreenId,
                    runStatus = CrawlRunStatus.IN_PROGRESS,
                )
                resolvedLinksByScreenId[rootScreenId] = mutableMapOf()
                rememberScreen(rootScreenId, rootSnapshot.screenName)
                logPersistedScreenCapture(
                    screenId = rootScreenId,
                    parentScreenId = null,
                    depth = 0,
                    route = CrawlRoute(),
                    snapshot = rootSnapshot,
                    screenFingerprint = rootScreenFingerprint,
                    files = rootFiles,
                    namingEventClassName = liveEntryRoot.className ?: initialRoot.className ?: eventClassName,
                    namingRoot = rootSnapshot.mergedRoot ?: liveEntryRoot,
                )
            }
            saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)

            val frontier = ArrayDeque<String>()
            if (loaded != null) {
                seedResumeFrontier(frontier, tracker, resumeMode)
                if (frontier.isEmpty()) {
                    logger.info(
                        "crawl_resume_complete reason=no_pending_screens resumeMode=${resumeMode.toLogString()}"
                    )
                    val manifestFile = saveManifest(
                        session = session,
                        tracker = tracker,
                        status = CrawlRunStatus.COMPLETED,
                    )
                    return DeepCrawlOutcome.Completed(
                        summary = buildSummary(
                            session = session,
                            tracker = tracker,
                            rootSnapshot = rootSnapshot,
                            rootFiles = rootFiles,
                            manifestFile = manifestFile,
                        ),
                    )
                }
            } else {
                frontier.add(rootScreenId)
            }
            rememberFrontier(frontier)
            logFrontierState(
                mutation = "enqueue_initial_root",
                screenId = rootScreenId,
                frontier = frontier,
            )
            var cachedRootSnapshot: ScreenSnapshot? = rootSnapshot.takeIf { frontier.contains(rootScreenId) }

            while (frontier.isNotEmpty()) {
                val nextScreenId = frontier.first()
                handlePauseCheckpointIfNeeded(
                    session = session,
                    tracker = tracker,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    pauseTracker = pauseTracker,
                    currentScreenId = nextScreenId,
                    currentScreenName = tracker.findScreen(nextScreenId)?.screenName,
                )
                val screenId = frontier.removeFirst()
                rememberFrontier(frontier)
                val screenRecord = tracker.findScreen(screenId) ?: continue
                rememberScreen(screenRecord.screenId, screenRecord.screenName)
                logger.info(
                    "frontier_dequeue screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                        "frontierSize=${frontier.size} frontier=${formatFrontier(frontier)}"
                )

                val expansionSnapshot = if (screenId == rootScreenId && cachedRootSnapshot != null) {
                    cachedRootSnapshot.also { cachedRootSnapshot = null }
                } else {
                    when (val prepared = prepareScreenForExpansion(
                        tracker = tracker,
                        screenRecord = screenRecord,
                        entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
                    )) {
                        is ScreenPreparationResult.Success -> prepared.snapshot
                        is ScreenPreparationResult.Failure -> {
                            handlePreparationFailure(
                                tracker = tracker,
                                session = session,
                                rootSnapshot = rootSnapshot,
                                rootFiles = rootFiles,
                                failure = prepared,
                                entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
                            )
                            continue
                        }
                    }
                } ?: continue

                expandScreen(
                    session = session,
                    tracker = tracker,
                    frontier = frontier,
                    screenRecord = screenRecord,
                    snapshot = expansionSnapshot,
                    blacklist = blacklist,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
                    pauseTracker = pauseTracker,
                )
            }

            val manifestFile = saveManifest(
                session = session,
                tracker = tracker,
                status = CrawlRunStatus.COMPLETED,
            )
            logger.info(
                "crawl_complete status=${CrawlRunStatus.COMPLETED.displayName()} " +
                    "capturedScreenCount=${tracker.capturedScreenCount()} capturedChildScreenCount=${tracker.capturedChildScreenCount()} " +
                    "skippedElementCount=${tracker.skippedElementCount()} maxDepthReached=${tracker.maxDiscoveredDepth()}"
            )
            return DeepCrawlOutcome.Completed(
                summary = buildSummary(
                    session = session,
                    tracker = tracker,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    manifestFile = manifestFile,
                ),
            )
        } catch (partialAbort: PartialCrawlAbortException) {
            logger.warn(
                "crawl_complete status=${CrawlRunStatus.PARTIAL_ABORT.displayName()} " +
                    "message=${quote(partialAbort.message.orEmpty())}"
            )
            return DeepCrawlOutcome.PartialAbort(
                summary = partialAbort.summary,
                message = partialAbort.message.orEmpty(),
            )
        } catch (cancellation: CancellationException) {
            logger.info(
                "crawl_canceled sessionId=${session.sessionId} " +
                    "lastReplayOrRecoveryStage=${quote(crashContext.lastReplayOrRecoveryStage.orEmpty())}"
            )
            throw cancellation
        } catch (error: Throwable) {
            runCatching {
                saveManifest(session, tracker, CrawlRunStatus.FAILED)
            }.onFailure { manifestError ->
                logger.error("failed_to_persist_failure_manifest manifestFile=${session.manifestFile.absolutePath}", manifestError)
            }
            logger.error(buildUnexpectedFailureMessage(error), error)
            DiagnosticLogger.error(
                "Unexpected crawler failure for session=${session.sessionId} package=${selectedApp.packageName}.",
                error,
            )
            throw error
        } finally {
            host.setActiveCrawlLogger(null)
            crawlLogger = null
            lastLoggedManifestStatus = null
            crashContext.reset()
        }
    }

    private fun seedResumeFrontier(
        frontier: ArrayDeque<String>,
        tracker: CrawlRunTracker,
        mode: ResumeMode,
    ) {
        when (mode) {
            ResumeMode.ContinueAuto -> {
                val inProgress = tracker.allScreens()
                    .firstOrNull { it.expansionStatus == ScreenExpansionStatus.IN_PROGRESS }
                inProgress?.let { frontier.add(it.screenId) }
                tracker.allScreens()
                    .filter { it.expansionStatus == ScreenExpansionStatus.NOT_STARTED }
                    .sortedBy { it.screenId }
                    .forEach { frontier.add(it.screenId) }
            }

            is ResumeMode.ResumeFromScreen -> {
                val start = tracker.findScreen(mode.screenId)
                    ?: throw IllegalArgumentException(
                        "ResumeFromScreen target '${mode.screenId}' not found in saved crawl."
                    )
                val visited = linkedSetOf(start.screenId)
                val toExplore = ArrayDeque<String>().apply { add(start.screenId) }
                while (toExplore.isNotEmpty()) {
                    val current = toExplore.removeFirst()
                    tracker.outboundEdges(current)
                        .asSequence()
                        .filter {
                            it.status == CrawlEdgeStatus.CAPTURED ||
                                it.status == CrawlEdgeStatus.LINKED_EXISTING
                        }
                        .mapNotNull { it.childScreenId }
                        .forEach { childId ->
                            if (visited.add(childId)) {
                                toExplore.add(childId)
                            }
                        }
                }
                frontier.add(start.screenId)
                visited.asSequence()
                    .drop(1) // skip start; already added.
                    .mapNotNull { tracker.findScreen(it) }
                    .filter { it.expansionStatus == ScreenExpansionStatus.NOT_STARTED }
                    .sortedBy { it.screenId }
                    .forEach { frontier.add(it.screenId) }
            }

            is ResumeMode.ReExpand -> {
                tracker.findScreen(mode.screenId)
                    ?: throw IllegalArgumentException(
                        "ReExpand target '${mode.screenId}' not found in saved crawl."
                    )
                tracker.clearOutboundEdges(mode.screenId)
                tracker.setScreenExpansionStatus(mode.screenId, ScreenExpansionStatus.NOT_STARTED)
                frontier.add(mode.screenId)
            }
        }
    }

    private suspend fun expandScreen(
        session: CrawlSessionDirectory,
        tracker: CrawlRunTracker,
        frontier: ArrayDeque<String>,
        screenRecord: CrawlScreenRecord,
        snapshot: ScreenSnapshot,
        blacklist: CrawlBlacklist,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        entryScreenLogicalFingerprint: String,
        pauseTracker: PauseCheckpointTracker,
    ) {
        val topSnapshot = snapshot.stepSnapshots.firstOrNull()?.root
            ?: throw IllegalStateException("Capture for '${snapshot.screenName}' did not preserve a top-of-screen snapshot.")
        val usesEntryFingerprint = screenRecord.route.steps.isEmpty()
        val topFingerprint = if (usesEntryFingerprint) {
            entryScreenLogicalFingerprint
        } else {
            scrollScanCoordinator.logicalViewportFingerprint(topSnapshot)
        }
        val isResumeExpansion = screenRecord.expansionStatus != ScreenExpansionStatus.NOT_STARTED
        val traversalPlan = if (isResumeExpansion) {
            TraversalPlan(
                eligibleElements = emptyList(),
                skippedElements = emptyList(),
            )
        } else {
            TraversalPlanner.planTraversal(snapshot, blacklist)
        }
        rememberScreen(screenRecord.screenId, screenRecord.screenName)
        if (isResumeExpansion) {
            crawlLogger?.info(
                "traversal_resume_plan screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                    "pendingEdgeCount=${tracker.outboundEdges(screenRecord.screenId).count { it.status == CrawlEdgeStatus.PENDING || it.status == CrawlEdgeStatus.IN_PROGRESS }}"
            )
        } else {
            logTraversalPlan(screenRecord, traversalPlan)
            if (tracker.outboundEdges(screenRecord.screenId).isEmpty()) {
                rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
            }
        }

        tracker.setScreenExpansionStatus(screenRecord.screenId, ScreenExpansionStatus.IN_PROGRESS)

        if (!isResumeExpansion) {
            traversalPlan.skippedElements.forEach { skipped ->
                val skippedEdgeId = tracker.addPendingEdge(
                    parentScreenId = screenRecord.screenId,
                    element = skipped.element,
                )
                tracker.updateEdgeStatus(
                    edgeId = skippedEdgeId,
                    status = CrawlEdgeStatus.SKIPPED_BLACKLIST,
                    message = skipped.reason,
                )
                crawlLogger?.info(
                    "edge_skipped_blacklist parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "reason=${quote(skipped.reason)} element=${formatElement(skipped.element)}"
                )
            }
        }
        rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
        saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)

        val edgeWorkItems = if (isResumeExpansion) {
            resumeEdgeWorkItems(
                tracker = tracker,
                screenRecord = screenRecord,
                snapshot = snapshot,
            )
        } else {
            traversalPlan.eligibleElements.map { element ->
                EdgeWorkItem(
                    edgeId = null,
                    element = element,
                )
            }
        }

        if (edgeWorkItems.isEmpty()) {
            tracker.setScreenExpansionStatus(screenRecord.screenId, ScreenExpansionStatus.COMPLETE)
            rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
            crawlLogger?.info(
                "screen_expansion_complete screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                    "result=${if (isResumeExpansion) "no_pending_edges" else "no_eligible_elements"}"
            )
            return
        }

        host.publishProgress(
            "Mapped '${snapshot.screenName}'. Visiting ${edgeWorkItems.size} target(s)."
        )

        edgeWorkItems.forEachIndexed { index, workItem ->
            val element = workItem.element
            rememberElement(element)
            val currentEdgeId = workItem.edgeId ?: tracker.addPendingEdge(
                parentScreenId = screenRecord.screenId,
                element = element,
            )
            tracker.updateEdgeStatus(
                edgeId = currentEdgeId,
                status = CrawlEdgeStatus.IN_PROGRESS,
            )
            rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
            try {
                host.publishProgress(
                    "Visiting target ${index + 1} of ${edgeWorkItems.size} from '${snapshot.screenName}': ${element.label}"
                )
                crawlLogger?.info(
                    "edge_visit_start parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "edgeIndex=${index + 1}/${edgeWorkItems.size} edgeId=$currentEdgeId resume=$isResumeExpansion element=${formatElement(element)}"
                )
                val openedChild = openChildFromScreen(
                    tracker = tracker,
                    screenRecord = screenRecord,
                    snapshot = snapshot,
                    element = element,
                    entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
                    expectedChildPackageName = null,
                    expectedTopFingerprint = topFingerprint,
                    usesEntryFingerprint = usesEntryFingerprint,
                )
                var activeChildRoot = openedChild.root
                val beforeClickFingerprint = openedChild.beforeClickFingerprint
                val currentPackageName = screenRecord.packageName
                val childPackageName = activeChildRoot.packageName ?: currentPackageName
                val afterClickFingerprint = openedChild.fingerprint
                crawlLogger?.info(
                    "edge_click_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "fingerprintType=logical beforeClickFingerprint=${quote(beforeClickFingerprint)} afterClickFingerprint=${quote(afterClickFingerprint)} " +
                        "topFingerprint=${quote(topFingerprint)} settleStopReason=${openedChild.settleStopReason.name.lowercase()} " +
                        "settleElapsedMillis=${openedChild.settleElapsedMillis} settleSampleCount=${openedChild.sampleCount} " +
                        "selectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))} element=${formatElement(element)}"
                )
                val remainedInCurrentPackage = childPackageName == currentPackageName
                if (
                    remainedInCurrentPackage &&
                    (afterClickFingerprint == beforeClickFingerprint || afterClickFingerprint == topFingerprint)
                ) {
                    tracker.updateEdgeStatus(
                        edgeId = currentEdgeId,
                        status = CrawlEdgeStatus.SKIPPED_NO_NAVIGATION,
                        message = "No distinct child screen detected.",
                    )
                    crawlLogger?.info(
                        "edge_skipped_no_navigation parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                            "element=${formatElement(element)}"
                    )
                    rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                    saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                    return@forEachIndexed
                }

                val externalPackage = childPackageName.takeIf { it != currentPackageName }
                if (externalPackage != null) {
                    tracker.updateEdgeStatus(
                        edgeId = currentEdgeId,
                        status = CrawlEdgeStatus.IN_PROGRESS,
                        externalPackage = externalPackage,
                    )
                }

                if (childPackageName !in allowedPackageNames) {
                    val pauseSnapshot = pauseTracker.progressSnapshot(
                        capturedScreenCount = tracker.capturedScreenCount(),
                        capturedChildScreenCount = tracker.capturedChildScreenCount(),
                    )
                    val externalPackageContext = ExternalPackageDecisionContext(
                        currentPackageName = currentPackageName,
                        nextPackageName = childPackageName,
                        parentScreenId = screenRecord.screenId,
                        parentScreenName = screenRecord.screenName,
                        triggerLabel = element.label,
                    )
                    saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                    crawlLogger?.warn(
                        "crawl_pause reason=${PauseReason.EXTERNAL_PACKAGE_BOUNDARY.name.lowercase()} " +
                            "currentScreenId=${screenRecord.screenId} currentScreenName=${quote(screenRecord.screenName)} " +
                            "currentPackageName=${quote(currentPackageName)} nextPackageName=${quote(childPackageName)} " +
                            "triggerLabel=${quote(element.label)} elapsedTimeMs=${pauseSnapshot.elapsedTimeMs} " +
                            "capturedScreenCount=${pauseSnapshot.capturedScreenCount} " +
                            "capturedChildScreenCount=${pauseSnapshot.capturedChildScreenCount} " +
                            "failedEdgeCount=${pauseSnapshot.failedEdgeCount}"
                    )
                    when (
                        val decision = host.awaitPauseDecision(
                            reason = PauseReason.EXTERNAL_PACKAGE_BOUNDARY,
                            snapshot = pauseSnapshot,
                            externalPackageContext = externalPackageContext,
                        )
                    ) {
                        PauseDecision.CONTINUE -> {
                            allowedPackageNames += childPackageName
                            tracker.updateEdgeStatus(
                                edgeId = currentEdgeId,
                                status = CrawlEdgeStatus.IN_PROGRESS,
                                approval = CrawlEdgeApproval.EXPLICIT,
                                externalPackage = childPackageName,
                            )
                            rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                            val allowedPackageSet = formatAllowedPackageNames()
                            crawlLogger?.info(
                                "crawl_pause_resolved reason=${PauseReason.EXTERNAL_PACKAGE_BOUNDARY.name.lowercase()} " +
                                    "decision=${decision.name.lowercase()} currentScreenId=${screenRecord.screenId} " +
                                    "nextPackageName=${quote(childPackageName)}"
                            )
                            crawlLogger?.info(
                                "external_package_accepted parentScreenId=${screenRecord.screenId} " +
                                    "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                                    "currentPackageName=${quote(currentPackageName)} nextPackageName=${quote(childPackageName)} " +
                                    "allowedPackageNames=${quote(allowedPackageSet)} " +
                                    "expectedDestinationFingerprint=${quote(afterClickFingerprint)} " +
                                    "settleStopReason=${openedChild.settleStopReason.name.lowercase()} " +
                                    "settleElapsedMillis=${openedChild.settleElapsedMillis} settleSampleCount=${openedChild.sampleCount} " +
                                    "destinationCompatible=pending compatibilityReason=deferred_until_restore " +
                                    "selectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))}"
                            )
                            crawlLogger?.info(
                                "external_boundary_restore_attempt parentScreenId=${screenRecord.screenId} " +
                                    "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                                    "currentPackageName=${quote(currentPackageName)} nextPackageName=${quote(childPackageName)} " +
                                    "allowedPackageNames=${quote(allowedPackageSet)} " +
                                    "expectedDestinationFingerprint=${quote(afterClickFingerprint)}"
                            )
                            val restoredChild = openChildFromScreen(
                                tracker = tracker,
                                screenRecord = screenRecord,
                                snapshot = snapshot,
                                element = element,
                                entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
                                expectedChildPackageName = childPackageName,
                                expectedTopFingerprint = topFingerprint,
                                usesEntryFingerprint = usesEntryFingerprint,
                            )
                            val restoredRoot = restoredChild.root
                            val restoredPackageName = restoredRoot.packageName
                            val restoredFingerprint = restoredChild.fingerprint
                            val compatibility = destinationSettler.compatibility(
                                expectedRoot = activeChildRoot,
                                expectedFingerprint = afterClickFingerprint,
                                expectedMetrics = openedChild.selectedMetrics,
                                actualRoot = restoredRoot,
                                actualFingerprint = restoredFingerprint,
                                actualMetrics = restoredChild.selectedMetrics,
                            )
                            crawlLogger?.info(
                                "external_boundary_restore_result parentScreenId=${screenRecord.screenId} " +
                                    "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                                    "currentPackageName=${quote(currentPackageName)} nextPackageName=${quote(childPackageName)} " +
                                    "allowedPackageNames=${quote(allowedPackageSet)} " +
                                    "expectedPackageName=${quote(childPackageName)} actualPackageName=${quote(restoredPackageName.orEmpty())} " +
                                    "expectedDestinationFingerprint=${quote(afterClickFingerprint)} " +
                                    "actualDestinationFingerprint=${quote(restoredFingerprint)} " +
                                    "destinationFingerprintMatched=${restoredFingerprint == afterClickFingerprint} " +
                                    "destinationCompatible=${compatibility.isCompatible} " +
                                    "compatibilityReason=${compatibility.reason.name.lowercase()} " +
                                    "settleStopReason=${restoredChild.settleStopReason.name.lowercase()} " +
                                    "settleElapsedMillis=${restoredChild.settleElapsedMillis} settleSampleCount=${restoredChild.sampleCount} " +
                                    "expectedSelectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))} " +
                                    "actualSelectedMetrics=${quote(formatDestinationMetrics(restoredChild.selectedMetrics))}"
                            )
                            if (restoredPackageName != childPackageName || !compatibility.isCompatible) {
                                failCurrentEdge(
                                    parentScreenId = screenRecord.screenId,
                                    element = element,
                                    message = "Could not restore external package screen '${element.label}' after continue decision.",
                                )
                            }
                            activeChildRoot = restoredRoot
                        }

                        PauseDecision.SKIP_EDGE -> {
                            tracker.updateEdgeStatus(
                                edgeId = currentEdgeId,
                                status = CrawlEdgeStatus.SKIPPED_EXTERNAL_PACKAGE,
                                message = "Skipped external package '$childPackageName'.",
                                externalPackage = childPackageName,
                            )
                            crawlLogger?.info(
                                "edge_skipped_external_package parentScreenId=${screenRecord.screenId} " +
                                    "parentScreenName=${quote(screenRecord.screenName)} currentPackageName=${quote(currentPackageName)} " +
                                    "nextPackageName=${quote(childPackageName)} element=${formatElement(element)}"
                            )
                            CaptureFileStore.rewriteScreenHtml(
                                files = filesFor(screenRecord),
                                snapshot = snapshot,
                                resolvedChildLinks = resolvedLinksByScreenId
                                    .getOrPut(screenRecord.screenId) { mutableMapOf() },
                            )
                            rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                            saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                            return@forEachIndexed
                        }

                        PauseDecision.STOP -> {
                            crawlLogger?.warn(
                                "crawl_pause_resolved reason=${PauseReason.EXTERNAL_PACKAGE_BOUNDARY.name.lowercase()} " +
                                    "decision=${decision.name.lowercase()} currentScreenId=${screenRecord.screenId} " +
                                    "nextPackageName=${quote(childPackageName)}"
                            )
                            abortPartialCapture(
                                tracker = tracker,
                                failedParentScreenId = screenRecord.screenId,
                                session = session,
                                rootSnapshot = rootSnapshot,
                                rootFiles = rootFiles,
                                message = stopMessageForPauseReason(PauseReason.EXTERNAL_PACKAGE_BOUNDARY),
                            )
                        }
                    }
                } else if (childPackageName != currentPackageName) {
                    crawlLogger?.info(
                        "external_package_already_allowed parentScreenId=${screenRecord.screenId} " +
                            "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                            "currentPackageName=${quote(currentPackageName)} nextPackageName=${quote(childPackageName)} " +
                            "allowedPackageNames=${quote(formatAllowedPackageNames())} " +
                            "expectedDestinationFingerprint=${quote(afterClickFingerprint)} " +
                            "actualDestinationFingerprint=${quote(afterClickFingerprint)}"
                    )
                }

                host.publishProgress("Mapping screen opened by '${element.label}'.")
                val childSnapshot = scanCurrentScreen(
                    eventClassName = activeChildRoot.className,
                    initialRoot = activeChildRoot,
                    capturePackageName = childPackageName,
                    progressPrefix = "Mapping screen '${element.label}'.",
                )
                val childTopRoot = childSnapshot.stepSnapshots.firstOrNull()?.root ?: activeChildRoot
                val childReplayFingerprint = scrollScanCoordinator.logicalViewportFingerprint(childTopRoot)
                val childRoute = screenRecord.route.append(
                    element.toRouteStep(
                        expectedPackageName = childSnapshot.packageName,
                        expectedDestinationFingerprint = openedChild.fingerprint,
                        expectedReplayFingerprint = childReplayFingerprint,
                        expectedReplayScreenName = childSnapshot.screenName,
                    )
                )
                val childScreenIdentity = screenIdentityFor(
                    snapshot = childSnapshot,
                    root = childSnapshot.mergedRoot ?: activeChildRoot,
                )
                val childScreenFingerprint = childScreenIdentity.fingerprint
                val existingChildScreenId = if (childScreenIdentity.canLinkToExisting) {
                    tracker.findScreenIdByFingerprint(childScreenFingerprint)
                } else {
                    null
                }
                crawlLogger?.info(
                    "child_capture_candidate parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "depth=${screenRecord.depth + 1} route=${quote(formatRoute(childRoute))} " +
                        "candidateScreenName=${quote(childSnapshot.screenName)} candidateFingerprint=${quote(childScreenFingerprint)} " +
                        "candidateDedupConfidence=${childScreenIdentity.confidence.name.lowercase()} " +
                        "candidateIdentityHints=${quote(formatIdentityHints(childScreenIdentity.identityHints))} " +
                        "scrollStepCount=${childSnapshot.scrollStepCount} element=${formatElement(element)}"
                )
                logNamingInputs(
                    eventClassName = activeChildRoot.className,
                    root = childSnapshot.mergedRoot ?: activeChildRoot,
                    screenName = childSnapshot.screenName,
                )

                if (!childScreenIdentity.canLinkToExisting) {
                    crawlLogger?.info(
                        "linked_existing_skipped parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                            "candidateScreenName=${quote(childSnapshot.screenName)} candidateFingerprint=${quote(childScreenFingerprint)} " +
                            "reason=${quote("Screen identity was too weak for dedup.")} element=${formatElement(element)}"
                    )
                }

                if (existingChildScreenId != null) {
                    val existingChildScreen = tracker.findScreen(existingChildScreenId)
                        ?: throw IllegalStateException(
                            "Existing screen '$existingChildScreenId' was not found for fingerprint '$childScreenFingerprint'."
                        )
                    tracker.updateEdgeStatus(
                        edgeId = currentEdgeId,
                        status = CrawlEdgeStatus.LINKED_EXISTING,
                        childScreenId = existingChildScreenId,
                        childScreenName = existingChildScreen.screenName,
                        message = "Linked to existing screen '${existingChildScreen.screenName}'.",
                    )
                    crawlLogger?.info(
                        "linked_existing parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                            "candidateScreenName=${quote(childSnapshot.screenName)} candidateFingerprint=${quote(childScreenFingerprint)} " +
                            "matchedScreenId=${existingChildScreen.screenId} matchedScreenName=${quote(existingChildScreen.screenName)} " +
                            "matchedFingerprint=${quote(existingChildScreen.screenFingerprint)} " +
                            "reason=${quote("Matched existing screen by strong dedup fingerprint.")} element=${formatElement(element)}"
                    )
                    resolvedLinksByScreenId
                        .getOrPut(screenRecord.screenId) { mutableMapOf() }[element.toLinkKey()] =
                        File(existingChildScreen.htmlPath).name
                } else {
                    val childSequence = tracker.nextScreenSequenceNumber()
                    val childScreenId = screenIdFor(childSequence)
                    val childFiles = CaptureFileStore.saveScreen(
                        session = session,
                        snapshot = childSnapshot,
                        screenId = childScreenId,
                    )
                    tracker.addScreen(
                        screenId = childScreenId,
                        snapshot = childSnapshot,
                        screenFingerprint = childScreenFingerprint,
                        replayFingerprint = childReplayFingerprint,
                        indexFingerprint = childScreenIdentity.canLinkToExisting,
                        files = childFiles,
                        parentScreenId = screenRecord.screenId,
                        triggerElement = element,
                        route = childRoute,
                        depth = screenRecord.depth + 1,
                    )
                    tracker.updateEdgeStatus(
                        edgeId = currentEdgeId,
                        status = CrawlEdgeStatus.CAPTURED,
                        childScreenId = childScreenId,
                        childScreenName = childSnapshot.screenName,
                        message = "Captured child screen '${childSnapshot.screenName}'.",
                    )
                    rememberScreen(childScreenId, childSnapshot.screenName)
                    logPersistedScreenCapture(
                        screenId = childScreenId,
                        parentScreenId = screenRecord.screenId,
                        depth = screenRecord.depth + 1,
                        route = childRoute,
                        snapshot = childSnapshot,
                        screenFingerprint = childScreenFingerprint,
                        files = childFiles,
                        namingEventClassName = activeChildRoot.className,
                        namingRoot = childSnapshot.mergedRoot ?: activeChildRoot,
                    )
                    resolvedLinksByScreenId
                        .getOrPut(screenRecord.screenId) { mutableMapOf() }[element.toLinkKey()] =
                        childFiles.htmlFile.name
                    resolvedLinksByScreenId.putIfAbsent(childScreenId, mutableMapOf())
                    rewriteScreenXmlFor(tracker, childSnapshot, childScreenId, CrawlRunStatus.IN_PROGRESS)
                    frontier.add(childScreenId)
                    rememberFrontier(frontier)
                    logFrontierState(
                        mutation = "enqueue_child",
                        screenId = childScreenId,
                        frontier = frontier,
                    )
                }

                CaptureFileStore.rewriteScreenHtml(
                    files = filesFor(screenRecord),
                    snapshot = snapshot,
                    resolvedChildLinks = resolvedLinksByScreenId
                        .getOrPut(screenRecord.screenId) { mutableMapOf() },
                )
                rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
            } catch (edgeFailure: RecoverableTraversalException) {
                crawlLogger?.warn(
                    "edge_failure_recoverable parentScreenId=${edgeFailure.parentScreenId} message=${quote(edgeFailure.message.orEmpty())} " +
                        "element=${formatElement(edgeFailure.element)}"
                )
                tracker.updateEdgeStatus(
                    edgeId = currentEdgeId,
                    status = CrawlEdgeStatus.FAILED,
                    message = edgeFailure.message,
                )
                rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                val recovered = recoverToReplayableState(entryScreenLogicalFingerprint)
                crawlLogger?.info(
                    "edge_recovery_result parentScreenId=${edgeFailure.parentScreenId} recoverySucceeded=$recovered " +
                        "continued=$recovered partialAbort=${!recovered} element=${formatElement(edgeFailure.element)}"
                )
                if (!recovered) {
                    abortPartialCapture(
                        tracker = tracker,
                        failedParentScreenId = edgeFailure.parentScreenId,
                        session = session,
                        rootSnapshot = rootSnapshot,
                        rootFiles = rootFiles,
                        message = edgeFailure.message.orEmpty(),
                    )
                }
                pauseTracker.recordFailedEdge()
                val pausedAtCheckpoint = handlePauseCheckpointIfNeeded(
                    session = session,
                    tracker = tracker,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    pauseTracker = pauseTracker,
                    currentScreenId = edgeFailure.parentScreenId,
                    currentScreenName = tracker.findScreen(edgeFailure.parentScreenId)?.screenName,
                )
                if (!pausedAtCheckpoint) {
                    saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                }
                host.publishProgress(
                    "Recovered after '${edgeFailure.element.label}' failed. Continuing with the next target."
                )
            }
        }

        tracker.setScreenExpansionStatus(screenRecord.screenId, ScreenExpansionStatus.COMPLETE)
        rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
    }

    private fun resumeEdgeWorkItems(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        snapshot: ScreenSnapshot,
    ): List<EdgeWorkItem> {
        val pendingEdges = tracker.outboundEdges(screenRecord.screenId)
            .filter { edge ->
                edge.status == CrawlEdgeStatus.PENDING ||
                    edge.status == CrawlEdgeStatus.IN_PROGRESS
            }
        val workItems = mutableListOf<EdgeWorkItem>()
        pendingEdges.forEach { edge ->
            val liveElement = snapshot.elements.firstOrNull { element -> matchesElement(edge, element) }
            if (liveElement == null) {
                tracker.updateEdgeStatus(
                    edgeId = edge.edgeId,
                    status = CrawlEdgeStatus.FAILED,
                    message = "Element no longer present on rescan.",
                )
                crawlLogger?.warn(
                    "resume_edge_missing screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                        "edgeId=${edge.edgeId} element=${formatEdgeRecord(edge)}"
                )
            } else {
                workItems += EdgeWorkItem(
                    edgeId = edge.edgeId,
                    element = liveElement,
                )
            }
        }
        return workItems
    }

    private suspend fun handlePauseCheckpointIfNeeded(
        session: CrawlSessionDirectory,
        tracker: CrawlRunTracker,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        pauseTracker: PauseCheckpointTracker,
        currentScreenId: String,
        currentScreenName: String?,
    ): Boolean {
        val reason = pauseTracker.nextTriggeredReason() ?: return false
        val snapshot = pauseTracker.progressSnapshot(
            capturedScreenCount = tracker.capturedScreenCount(),
            capturedChildScreenCount = tracker.capturedChildScreenCount(),
        )
        saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
        crawlLogger?.warn(
            "crawl_pause reason=${reason.name.lowercase()} currentScreenId=$currentScreenId " +
                "currentScreenName=${quote(currentScreenName ?: "<unknown>")} " +
                "elapsedTimeMs=${snapshot.elapsedTimeMs} capturedScreenCount=${snapshot.capturedScreenCount} " +
                "capturedChildScreenCount=${snapshot.capturedChildScreenCount} failedEdgeCount=${snapshot.failedEdgeCount}"
        )
        return when (
            val decision = host.awaitPauseDecision(
                reason = reason,
                snapshot = snapshot,
            )
        ) {
            PauseDecision.CONTINUE -> {
                pauseTracker.rollForwardAfterContinue(reason)
                crawlLogger?.info(
                    "crawl_pause_resolved reason=${reason.name.lowercase()} decision=${decision.name.lowercase()} " +
                        "currentScreenId=$currentScreenId"
                )
                true
            }

            PauseDecision.STOP -> {
                crawlLogger?.warn(
                    "crawl_pause_resolved reason=${reason.name.lowercase()} decision=${decision.name.lowercase()} " +
                        "currentScreenId=$currentScreenId"
                )
                abortPartialCapture(
                    tracker = tracker,
                    failedParentScreenId = currentScreenId,
                    session = session,
                    rootSnapshot = rootSnapshot,
                    rootFiles = rootFiles,
                    message = stopMessageForPauseReason(reason),
                )
            }

            PauseDecision.SKIP_EDGE -> {
                throw IllegalStateException(
                    "Pause decision SKIP_EDGE is not supported for ${reason.name.lowercase()} checkpoints."
                )
            }
        }
    }

    private suspend fun prepareScreenForExpansion(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        entryScreenLogicalFingerprint: String,
    ): ScreenPreparationResult {
        rememberScreen(screenRecord.screenId, screenRecord.screenName)
        setReplayOrRecoveryStage("prepare_screen_for_expansion screenId=${screenRecord.screenId}")
        val replayResult = replayRouteToScreen(
            tracker = tracker,
            screenRecord = screenRecord,
            entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
        )
        if (replayResult is ReplayToScreenResult.Failure) {
            return ScreenPreparationResult.Failure(
                parentScreenId = replayResult.parentScreenId,
                element = replayResult.element,
                message = replayResult.message,
            )
        }

        replayResult as ReplayToScreenResult.Success
        val snapshot = scanCurrentScreen(
            eventClassName = replayResult.root.className,
            initialRoot = replayResult.root,
            capturePackageName = screenRecord.packageName,
            progressPrefix = "Replaying route to '${screenRecord.screenName}'.",
            preferredName = screenRecord.screenName,
        )
        val liveFingerprint = screenIdentityFor(
            snapshot = snapshot,
            root = snapshot.mergedRoot ?: replayResult.root,
        ).fingerprint
        crawlLogger?.info(
                "replay_validation destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=screen_identity expectedFingerprint=${quote(screenRecord.screenFingerprint)} actualFingerprint=${quote(liveFingerprint)} " +
                "route=${quote(formatRoute(screenRecord.route))}"
        )
        logNamingInputs(
            eventClassName = replayResult.root.className,
            root = snapshot.mergedRoot ?: replayResult.root,
            screenName = snapshot.screenName,
        )
        if (liveFingerprint != screenRecord.screenFingerprint) {
            val routeElement = screenRecord.route.steps.last().toPressableElement()
            return ScreenPreparationResult.Failure(
                parentScreenId = screenRecord.parentScreenId ?: screenRecord.screenId,
                element = routeElement,
                message = "Route replay diverged for '${screenRecord.screenName}'. Expected '${screenRecord.screenName}' but found '${snapshot.screenName}'.",
            )
        }

        return ScreenPreparationResult.Success(snapshot)
    }

    private suspend fun handlePreparationFailure(
        tracker: CrawlRunTracker,
        session: CrawlSessionDirectory,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        failure: ScreenPreparationResult.Failure,
        entryScreenLogicalFingerprint: String,
    ) {
        rememberElement(failure.element)
        crawlLogger?.warn(
            "screen_preparation_failure parentScreenId=${failure.parentScreenId} message=${quote(failure.message)} " +
                "element=${formatElement(failure.element)}"
        )
        val recovered = recoverToReplayableState(entryScreenLogicalFingerprint)
        crawlLogger?.info(
            "screen_preparation_recovery parentScreenId=${failure.parentScreenId} recoverySucceeded=$recovered " +
                "continued=$recovered partialAbort=${!recovered}"
        )
        if (!recovered) {
            abortPartialCapture(
                tracker = tracker,
                failedParentScreenId = failure.parentScreenId,
                session = session,
                rootSnapshot = rootSnapshot,
                rootFiles = rootFiles,
                message = failure.message,
                failedElement = failure.element,
            )
        }

        tracker.addEdge(
            parentScreenId = failure.parentScreenId,
            element = failure.element,
            status = CrawlEdgeStatus.FAILED,
            message = failure.message,
        )
        saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
        host.publishProgress(
            "Skipped a queued branch after route replay failed. Continuing with the remaining frontier."
        )
    }

    private suspend fun openChildFromScreen(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        snapshot: ScreenSnapshot,
        element: PressableElement,
        entryScreenLogicalFingerprint: String,
        expectedChildPackageName: String?,
        expectedTopFingerprint: String,
        usesEntryFingerprint: Boolean,
    ): OpenedChildDestination {
        crawlLogger?.info(
            "child_open_restore_attempt parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "triggerLabel=${quote(element.label)} expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "expectedTopFingerprint=${quote(expectedTopFingerprint)}"
        )
        val liveScreenRoot = restoreLiveScreenForEdge(
            tracker = tracker,
            screenRecord = screenRecord,
            element = element,
            entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
        )
        setReplayOrRecoveryStage("rewind_to_top screenId=${screenRecord.screenId}")
        val topRoot = scrollScanCoordinator.rewindToTop(
            selectedApp = selectedApp,
            initialRoot = liveScreenRoot,
            tryScrollBackward = { path -> host.scrollBackward(path) },
            captureCurrentRoot = {
                host.captureCurrentRootSnapshot(screenRecord.packageName)
            },
            onProgress = host::publishProgress,
        )

        val liveTopFingerprint = if (usesEntryFingerprint) {
            scrollScanCoordinator.logicalEntryViewportFingerprint(topRoot)
        } else {
            scrollScanCoordinator.logicalViewportFingerprint(topRoot)
        }
        crawlLogger?.info(
            "screen_top_validation screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=logical expectedFingerprint=${quote(expectedTopFingerprint)} actualFingerprint=${quote(liveTopFingerprint)}"
        )
        if (liveTopFingerprint != expectedTopFingerprint) {
            crawlLogger?.info(
                "child_open_restore_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                    "triggerLabel=${quote(element.label)} expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                    "destinationFingerprintMatched=false result=top_fingerprint_mismatch"
            )
            failCurrentEdge(
                parentScreenId = screenRecord.screenId,
                element = element,
                message = "The screen '${snapshot.screenName}' no longer matches its captured top state before opening '${element.label}'.",
            )
        }

        setReplayOrRecoveryStage(
            "move_to_step screenId=${screenRecord.screenId} targetStep=${element.firstSeenStep} label=${quote(element.label)}"
        )
        val targetStepRoot = scrollScanCoordinator.moveToStep(
            selectedApp = selectedApp,
            initialRoot = topRoot,
            targetStepIndex = element.firstSeenStep,
            tryScrollForward = { path -> host.scrollForward(path) },
            captureCurrentRoot = {
                host.captureCurrentRootSnapshot(screenRecord.packageName)
            },
            onProgress = host::publishProgress,
        ) ?: failCurrentEdge(
            parentScreenId = screenRecord.screenId,
            element = element,
            message = "Could not scroll back to '${element.label}' on '${snapshot.screenName}'.",
        )

        val beforeClickFingerprint = if (usesEntryFingerprint) {
            scrollScanCoordinator.logicalEntryViewportFingerprint(targetStepRoot)
        } else {
            scrollScanCoordinator.logicalViewportFingerprint(targetStepRoot)
        }
        crawlLogger?.info(
            "edge_click_prepare parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=logical beforeClickFingerprint=${quote(beforeClickFingerprint)} element=${formatElement(element)}"
        )
        if (!host.click(element)) {
            failCurrentEdge(
                parentScreenId = screenRecord.screenId,
                element = element,
                message = "Could not click '${element.label}' after replaying '${snapshot.screenName}'.",
            )
        }

        val openedChild = captureChildDestinationAfterClick(
            screenRecord = screenRecord,
            element = element,
            expectedChildPackageName = expectedChildPackageName,
            beforeClickFingerprint = beforeClickFingerprint,
            expectedTopFingerprint = expectedTopFingerprint,
            usesEntryFingerprint = usesEntryFingerprint,
        )
        crawlLogger?.info(
            "child_open_restore_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "triggerLabel=${quote(element.label)} expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "actualPackageName=${quote(openedChild.root.packageName.orEmpty())} " +
                "destinationFingerprintMatched=${openedChild.fingerprint != beforeClickFingerprint} " +
                "settleStopReason=${openedChild.settleStopReason.name.lowercase()} " +
                "settleElapsedMillis=${openedChild.settleElapsedMillis} settleSampleCount=${openedChild.sampleCount} " +
                "selectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))} result=captured"
        )
        return openedChild
    }

    private suspend fun captureChildDestinationAfterClick(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickFingerprint: String,
        expectedTopFingerprint: String,
        usesEntryFingerprint: Boolean,
    ): OpenedChildDestination {
        val fingerprint: (AccessibilityNodeSnapshot) -> String = { root ->
            if (usesEntryFingerprint) {
                scrollScanCoordinator.logicalEntryViewportFingerprint(root)
            } else {
                scrollScanCoordinator.logicalViewportFingerprint(root)
            }
        }
        val result = destinationSettler.settle(
            DestinationSettleRequest(
                parentPackageName = screenRecord.packageName,
                expectedPackageName = expectedChildPackageName,
                beforeClickFingerprint = beforeClickFingerprint,
                topFingerprint = expectedTopFingerprint,
                mode = DestinationSettleMode.DISCOVERY,
                fingerprint = fingerprint,
                capture = { expectedPackageName ->
                    host.captureCurrentRootSnapshot(expectedPackageName)
                },
                timeProvider = postClickSettleTimeProvider,
                maxSettleMillis = maxPostClickSettleMillis,
            )
        )
        logChildDestinationSettleSamples(
            screenRecord = screenRecord,
            element = element,
            expectedChildPackageName = expectedChildPackageName,
            beforeClickFingerprint = beforeClickFingerprint,
            expectedTopFingerprint = expectedTopFingerprint,
            settleResult = result,
        )

        val selectedSample = result.samples.firstOrNull { sample ->
            sample.root != null &&
                sample.root == result.root &&
                sample.fingerprint == result.fingerprint
        } ?: result.samples.lastOrNull { sample -> sample.root != null }
        val selectedRoot = result.root ?: selectedSample?.root ?: failCurrentEdge(
            parentScreenId = screenRecord.screenId,
            element = element,
            message = "The target app was lost immediately after clicking '${element.label}'.",
        )
        val selectedFingerprint = result.fingerprint ?: selectedSample?.fingerprint ?: fingerprint(selectedRoot)
        val selectedMetrics = selectedSample?.metrics ?: DestinationRichnessMetrics.from(
            root = selectedRoot,
            logicalFingerprint = selectedFingerprint,
        )

        return OpenedChildDestination(
            root = selectedRoot,
            beforeClickFingerprint = beforeClickFingerprint,
            fingerprint = selectedFingerprint,
            settleStopReason = result.stopReason,
            settleElapsedMillis = result.elapsedMillis,
            sampleCount = result.samples.size,
            selectedMetrics = selectedMetrics,
        )
    }

    private fun logChildDestinationObserveAttempt(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickFingerprint: String,
        expectedTopFingerprint: String,
        sample: DestinationSample,
    ) {
        crawlLogger?.info(
            "child_destination_observe_attempt parentScreenId=${screenRecord.screenId} " +
                "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                "attempt=${sample.attemptNumber} elapsedSettleMillis=${sample.elapsedMillis} " +
                "maxSettleMillis=$maxPostClickSettleMillis " +
                "expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "beforeClickFingerprint=${quote(beforeClickFingerprint)} topFingerprint=${quote(expectedTopFingerprint)}"
        )
    }

    private fun logChildDestinationObserveResult(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickFingerprint: String,
        expectedTopFingerprint: String,
        sample: DestinationSample,
        result: String,
    ) {
        crawlLogger?.info(
            "child_destination_observe_result parentScreenId=${screenRecord.screenId} " +
                "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                "attempt=${sample.attemptNumber} elapsedSettleMillis=${sample.elapsedMillis} " +
                "maxSettleMillis=$maxPostClickSettleMillis " +
                "expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "actualPackageName=${quote(sample.packageName.orEmpty())} " +
                "beforeClickFingerprint=${quote(beforeClickFingerprint)} topFingerprint=${quote(expectedTopFingerprint)} " +
                "observedFingerprint=${quote(sample.fingerprint.orEmpty())} " +
                "eligibilityReason=${sample.eligibilityReason.name.lowercase()} eligible=${sample.eligible} " +
                "packageChanged=${sample.packageChanged} fingerprintChangedFromBefore=${sample.fingerprintChangedFromBefore} " +
                "fingerprintChangedFromTop=${sample.fingerprintChangedFromTop} sameFingerprintAsPrevious=${sample.sameFingerprintAsPrevious} " +
                "becameCurrentBest=${sample.becameCurrentBest} " +
                "metrics=${quote(formatDestinationMetrics(sample.metrics))} result=$result"
        )
    }

    private fun logChildDestinationSettleSamples(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickFingerprint: String,
        expectedTopFingerprint: String,
        settleResult: DestinationSettleResult,
    ) {
        settleResult.samples.forEach { sample ->
            logChildDestinationObserveAttempt(
                screenRecord = screenRecord,
                element = element,
                expectedChildPackageName = expectedChildPackageName,
                beforeClickFingerprint = beforeClickFingerprint,
                expectedTopFingerprint = expectedTopFingerprint,
                sample = sample,
            )
            logChildDestinationObserveResult(
                screenRecord = screenRecord,
                element = element,
                expectedChildPackageName = expectedChildPackageName,
                beforeClickFingerprint = beforeClickFingerprint,
                expectedTopFingerprint = expectedTopFingerprint,
                sample = sample,
                result = observeResultForSample(
                    sample = sample,
                    expectedChildPackageName = expectedChildPackageName,
                    settleResult = settleResult,
                ),
            )
        }
        crawlLogger?.info(
            "child_destination_settle_result parentScreenId=${screenRecord.screenId} " +
                "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                "expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "selectedPackageName=${quote(settleResult.packageName.orEmpty())} " +
                "selectedFingerprint=${quote(settleResult.fingerprint.orEmpty())} " +
                "sampleCount=${settleResult.samples.size} elapsedSettleMillis=${settleResult.elapsedMillis} " +
                "stopReason=${settleResult.stopReason.name.lowercase()} " +
                "selectionReason=${settleResult.selectionReason?.name?.lowercase().orEmpty()} " +
                "selectedMetrics=${quote(formatDestinationMetrics(selectedMetrics(settleResult)))}"
        )
    }

    private fun observeResultForSample(
        sample: DestinationSample,
        expectedChildPackageName: String?,
        settleResult: DestinationSettleResult,
    ): String {
        if (sample.root == null) {
            return if (expectedChildPackageName != null) {
                if (sample == settleResult.samples.last()) "expected_package_missing_final" else "expected_package_missing_retry"
            } else {
                if (sample == settleResult.samples.last()) "capture_missing_final" else "capture_missing_retry"
            }
        }

        if (sample.eligible) {
            return if (expectedChildPackageName != null) "captured" else "changed"
        }

        return when (sample.eligibilityReason) {
            DestinationEligibilityReason.EXPECTED_PACKAGE_MISMATCH ->
                if (sample == settleResult.samples.last()) "expected_package_mismatch_final" else "expected_package_mismatch_retry"

            else ->
                if (sample == settleResult.samples.last()) "unchanged_final" else "unchanged_retry"
        }
    }

    private fun selectedMetrics(settleResult: DestinationSettleResult): DestinationRichnessMetrics? {
        return settleResult.samples.firstOrNull { sample ->
            sample.root != null &&
                sample.root == settleResult.root &&
                sample.fingerprint == settleResult.fingerprint
        }?.metrics
    }

    private suspend fun restoreLiveScreenForEdge(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        entryScreenLogicalFingerprint: String,
    ): AccessibilityNodeSnapshot {
        if (screenRecord.route.steps.isEmpty()) {
            setReplayOrRecoveryStage("restore_root_for_edge screenId=${screenRecord.screenId}")
            return restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint) ?: failCurrentEdge(
                parentScreenId = screenRecord.screenId,
                element = element,
                message = "Could not restore the root screen before opening '${element.label}'.",
            )
        }

        return when (val replayResult = replayRouteToScreen(
            tracker = tracker,
            screenRecord = screenRecord,
            entryScreenLogicalFingerprint = entryScreenLogicalFingerprint,
        )) {
            is ReplayToScreenResult.Success -> replayResult.root
            is ReplayToScreenResult.Failure -> failCurrentEdge(
                parentScreenId = replayResult.parentScreenId,
                element = replayResult.element,
                message = replayResult.message,
            )
        }
    }

    private suspend fun replayRouteToScreen(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        entryScreenLogicalFingerprint: String,
    ): ReplayToScreenResult {
        crawlLogger?.info(
            "replay_attempt destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                "expectedScreenFingerprint=${quote(screenRecord.screenFingerprint)} route=${quote(formatRoute(screenRecord.route))}"
        )
        if (screenRecord.route.steps.isEmpty()) {
            val entryRoot = restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint)
                ?: return replayFailure(
                    parentScreenId = screenRecord.screenId,
                    element = PressableElement(
                        label = screenRecord.screenName,
                        resourceId = screenRecord.triggerResourceId,
                        bounds = "[0,0][0,0]",
                        className = null,
                        isListItem = false,
                    ),
                    message = "Could not restore the entry screen while replaying '${screenRecord.screenName}'.",
                )
            crawlLogger?.info(
                "replay_result destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "success=true routeStepCount=0"
            )
            return ReplayToScreenResult.Success(entryRoot)
        }

        var currentRoot = restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint)
            ?: return replayFailure(
                parentScreenId = screenRecord.parentScreenId ?: screenRecord.screenId,
                element = screenRecord.route.steps.first().toPressableElement(),
                message = "Could not restore the entry screen while replaying '${screenRecord.screenName}'.",
            )

        routeParentScreenIds(tracker, screenRecord).zip(screenRecord.route.steps).forEachIndexed { index, (parentScreenId, routeStep) ->
            val parentScreen = tracker.findScreen(parentScreenId)
                ?: return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Could not load the parent screen metadata while replaying '${screenRecord.screenName}'.",
                )
            rememberElement(routeStep.toPressableElement())
            setReplayOrRecoveryStage(
                "replay_route_step destinationScreenId=${screenRecord.screenId} stepIndex=$index label=${quote(routeStep.label)}"
            )
            crawlLogger?.info(
                "replay_route_step destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "stepIndex=$index parentScreenId=$parentScreenId parentPackageName=${quote(parentScreen.packageName)} " +
                    "step=${formatRouteStep(routeStep)}"
            )
            val topRoot = scrollScanCoordinator.rewindToTop(
                selectedApp = selectedApp,
                initialRoot = currentRoot,
                tryScrollBackward = { path -> host.scrollBackward(path) },
                captureCurrentRoot = {
                    host.captureCurrentRootSnapshot(parentScreen.packageName)
                },
                onProgress = host::publishProgress,
            )

            val targetStepRoot = scrollScanCoordinator.moveToStep(
                selectedApp = selectedApp,
                initialRoot = topRoot,
                targetStepIndex = routeStep.firstSeenStep,
                tryScrollForward = { path -> host.scrollForward(path) },
                captureCurrentRoot = {
                    host.captureCurrentRootSnapshot(parentScreen.packageName)
                },
                onProgress = host::publishProgress,
            ) ?: return replayFailure(
                parentScreenId = parentScreenId,
                element = routeStep.toPressableElement(),
                message = "Could not scroll back to '${routeStep.label}' while replaying '${screenRecord.screenName}'.",
            )

            val routeStepUsesEntryFingerprint = parentScreen.route.steps.isEmpty()
            val routeStepFingerprint: (AccessibilityNodeSnapshot) -> String = { root ->
                if (routeStepUsesEntryFingerprint) {
                    scrollScanCoordinator.logicalEntryViewportFingerprint(root)
                } else {
                    scrollScanCoordinator.logicalViewportFingerprint(root)
                }
            }
            val topFingerprint = if (routeStepUsesEntryFingerprint) {
                entryScreenLogicalFingerprint
            } else {
                scrollScanCoordinator.logicalViewportFingerprint(topRoot)
            }
            val beforeClickFingerprint = routeStepFingerprint(targetStepRoot)
            if (!host.click(routeStep.toPressableElement())) {
                return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Could not click '${routeStep.label}' while replaying '${screenRecord.screenName}'.",
                )
            }

            val settleResult = destinationSettler.settle(
                DestinationSettleRequest(
                    parentPackageName = parentScreen.packageName,
                    expectedPackageName = routeStep.expectedPackageName,
                    beforeClickFingerprint = beforeClickFingerprint,
                    topFingerprint = topFingerprint,
                    knownDestinationFingerprint = routeStep.expectedDestinationFingerprint,
                    mode = DestinationSettleMode.ROUTE_REPLAY,
                    fingerprint = routeStepFingerprint,
                    capture = { expectedPackageName ->
                        host.captureCurrentRootSnapshot(expectedPackageName)
                    },
                    timeProvider = postClickSettleTimeProvider,
                    maxSettleMillis = maxPostClickSettleMillis,
                )
            )
            logChildDestinationSettleSamples(
                screenRecord = parentScreen,
                element = routeStep.toPressableElement(),
                expectedChildPackageName = routeStep.expectedPackageName,
                beforeClickFingerprint = beforeClickFingerprint,
                expectedTopFingerprint = topFingerprint,
                settleResult = settleResult,
            )
            val nextRoot = settleResult.root ?: return replayFailure(
                parentScreenId = parentScreenId,
                element = routeStep.toPressableElement(),
                message = "The target app was lost while replaying '${screenRecord.screenName}'.",
            )
            val afterClickFingerprint = settleResult.fingerprint ?: routeStepFingerprint(nextRoot)
            crawlLogger?.info(
                "replay_route_step_settle_result destinationScreenId=${screenRecord.screenId} " +
                    "destinationScreenName=${quote(screenRecord.screenName)} stepIndex=$index " +
                    "parentScreenId=$parentScreenId expectedPackageName=${quote(routeStep.expectedPackageName.orEmpty())} " +
                    "knownDestinationFingerprint=${quote(routeStep.expectedDestinationFingerprint.orEmpty())} " +
                    "selectedPackageName=${quote(settleResult.packageName.orEmpty())} " +
                    "selectedFingerprint=${quote(afterClickFingerprint)} sampleCount=${settleResult.samples.size} " +
                    "elapsedSettleMillis=${settleResult.elapsedMillis} " +
                    "stopReason=${settleResult.stopReason.name.lowercase()} " +
                    "selectionReason=${settleResult.selectionReason?.name?.lowercase().orEmpty()} " +
                    "selectedMetrics=${quote(formatDestinationMetrics(selectedMetrics(settleResult)))} " +
                    "step=${formatRouteStep(routeStep)}"
            )
            if (
                afterClickFingerprint == beforeClickFingerprint ||
                afterClickFingerprint == topFingerprint
            ) {
                return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Clicking '${routeStep.label}' did not navigate while replaying '${screenRecord.screenName}'.",
                )
            }
            crawlLogger?.info(
                "replay_route_step_result destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "fingerprintType=logical stepIndex=$index beforeClickFingerprint=${quote(beforeClickFingerprint)} " +
                    "topFingerprint=${quote(topFingerprint)} afterClickFingerprint=${quote(afterClickFingerprint)} " +
                    "step=${formatRouteStep(routeStep)}"
            )

            val expectedReplayFingerprint = routeStep.expectedReplayFingerprint
            val expectedReplayScreenName = routeStep.expectedReplayScreenName
                ?: if (index == screenRecord.route.steps.lastIndex) screenRecord.screenName else null
            if (expectedReplayFingerprint != null) {
                val observedReplayFingerprint = scrollScanCoordinator.logicalViewportFingerprint(nextRoot)
                val matched = observedReplayFingerprint == expectedReplayFingerprint
                crawlLogger?.info(
                    "replay_route_step_validation destinationScreenId=${screenRecord.screenId} " +
                        "destinationScreenName=${quote(screenRecord.screenName)} stepIndex=$index " +
                        "expectedReplayScreenName=${quote(expectedReplayScreenName.orEmpty())} " +
                        "expectedReplayFingerprint=${quote(expectedReplayFingerprint)} " +
                        "observedReplayFingerprint=${quote(observedReplayFingerprint)} " +
                        "matchedExpectedReplay=$matched step=${formatRouteStep(routeStep)}"
                )
                if (!matched) {
                    val expectedScreenLabel = expectedReplayScreenName?.takeIf { it.isNotBlank() }
                        ?: "<unnamed>"
                    return replayFailure(
                        parentScreenId = parentScreenId,
                        element = routeStep.toPressableElement(),
                        message = "Route replay step $index for '${routeStep.label}' reached an unexpected screen while replaying '${screenRecord.screenName}': " +
                            "expected '$expectedScreenLabel' (replay fingerprint '$expectedReplayFingerprint') " +
                            "but observed replay fingerprint '$observedReplayFingerprint'.",
                    )
                }
            }

            currentRoot = nextRoot
        }

        crawlLogger?.info(
            "replay_result destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                "success=true routeStepCount=${screenRecord.route.steps.size}"
        )
        return ReplayToScreenResult.Success(currentRoot)
    }

    private suspend fun scanCurrentScreen(
        eventClassName: String?,
        initialRoot: AccessibilityNodeSnapshot,
        capturePackageName: String?,
        progressPrefix: String,
        preferredName: String? = null,
    ): ScreenSnapshot {
        scanScreenOverride?.let { override ->
            return override(
                eventClassName,
                initialRoot,
                capturePackageName,
                progressPrefix,
                preferredName,
            )
        }

        return scrollScanCoordinator.scan(
            selectedApp = selectedApp,
            eventClassName = eventClassName,
            initialRoot = initialRoot,
            tryScrollForward = { path -> host.scrollForward(path) },
            tryScrollBackward = { path -> host.scrollBackward(path) },
            captureCurrentRoot = {
                host.captureCurrentRootSnapshot(capturePackageName)
            },
            onProgress = { message ->
                host.publishProgress("$progressPrefix $message")
            },
            preferredName = preferredName,
        )
    }

    private suspend fun normalizeRootToEntryScreen(
        targetPackageName: String,
        initialRoot: AccessibilityNodeSnapshot,
        expectedEntryLogicalFingerprint: String? = null,
    ): EntryScreenResetResult {
        return scrollScanCoordinator.rewindToEntryScreen(
            initialRoot = initialRoot,
            targetPackageName = targetPackageName,
            expectedEntryLogicalFingerprint = expectedEntryLogicalFingerprint,
            tryBack = { host.performGlobalBack() },
            captureCurrentRoot = {
                host.captureCurrentRootSnapshot(expectedPackageName = null)
            },
            onProgress = host::publishProgress,
        )
    }

    private suspend fun restoreToEntryScreenOrRelaunch(
        expectedEntryLogicalFingerprint: String? = null,
        preferRelaunchWhenEntryIsAmbiguous: Boolean = false,
    ): AccessibilityNodeSnapshot? {
        setReplayOrRecoveryStage("restore_to_entry_or_relaunch")
        val currentRoot = host.captureCurrentRootSnapshot(expectedPackageName = null)
        crawlLogger?.info(
            "entry_restore_probe currentPackage=${currentRoot?.packageName.orEmpty()} " +
                "expectedEntryLogicalFingerprintPresent=${expectedEntryLogicalFingerprint != null} " +
                "preferRelaunchWhenEntryIsAmbiguous=$preferRelaunchWhenEntryIsAmbiguous"
        )
        if (currentRoot?.packageName == selectedApp.packageName) {
            val entryIsAmbiguous = preferRelaunchWhenEntryIsAmbiguous &&
                expectedEntryLogicalFingerprint == null &&
                !EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(currentRoot)
            crawlLogger?.info(
                "entry_restore_attempt strategy=restore_to_entry currentClass=${currentRoot.className.orEmpty()} " +
                    "entryIsAmbiguous=$entryIsAmbiguous"
            )
            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = selectedApp.packageName,
                initialRoot = currentRoot,
                expectedEntryLogicalFingerprint = expectedEntryLogicalFingerprint,
            )
            crawlLogger?.info(
                "entry_restore_result strategy=restore_to_entry attempt=1/1 " +
                    formatEntryResetResult(entryResetResult)
            )
            if (
                entryResetResult.verifiedForReplay &&
                !entryIsAmbiguous
            ) {
                return entryResetResult.root
            }
        }

        crawlLogger?.info("entry_restore_attempt strategy=relaunch")
        val relaunchError = host.relaunchTargetApp(selectedApp)
        if (relaunchError != null) {
            crawlLogger?.warn("entry_restore_result strategy=relaunch success=false message=${quote(relaunchError)}")
            return null
        }

        val startedAt = entryRestoreSettleTimeProvider()
        var attemptNumber = 0
        var shouldContinueSampling = true
        while (shouldContinueSampling && attemptNumber < maxEntryRestoreCaptureAttempts) {
            attemptNumber += 1
            val relaunchedRoot = host.captureCurrentRootSnapshot(selectedApp.packageName)
            if (relaunchedRoot == null) {
                val elapsedMillis = entryRestoreSettleTimeProvider() - startedAt
                crawlLogger?.info(
                    "entry_restore_relaunch_attempt attempt=$attemptNumber/$maxEntryRestoreCaptureAttempts " +
                        "elapsedSettleMillis=$elapsedMillis maxSettleMillis=$maxEntryRestoreSettleMillis " +
                        "observedPackageName=${quote("")} observedLogicalFingerprint=${quote("")} " +
                        "expectedLogicalFingerprintPresent=${expectedEntryLogicalFingerprint != null} " +
                        "expectedLogicalFingerprint=${quote(expectedEntryLogicalFingerprint.orEmpty())} " +
                        "outcome=capture_missing matchedExpectedLogical=false verifiedForReplay=false"
                )
                shouldContinueSampling = elapsedMillis < maxEntryRestoreSettleMillis
                continue
            }
            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = selectedApp.packageName,
                initialRoot = relaunchedRoot,
                expectedEntryLogicalFingerprint = expectedEntryLogicalFingerprint,
            )
            val elapsedMillis = entryRestoreSettleTimeProvider() - startedAt
            crawlLogger?.info(
                "entry_restore_relaunch_attempt attempt=$attemptNumber/$maxEntryRestoreCaptureAttempts " +
                    "elapsedSettleMillis=$elapsedMillis maxSettleMillis=$maxEntryRestoreSettleMillis " +
                    formatEntryResetResult(entryResetResult)
            )
            if (entryResetResult.verifiedForReplay) {
                crawlLogger?.info("entry_restore_result strategy=relaunch success=true")
                return entryResetResult.root
            }
            shouldContinueSampling = elapsedMillis < maxEntryRestoreSettleMillis
        }

        val failureMessage = if (expectedEntryLogicalFingerprint != null) {
            "The expected entry screen did not settle after relaunch."
        } else {
            "No entry screen root was captured after relaunch."
        }
        crawlLogger?.warn("entry_restore_result strategy=relaunch success=false message=${quote(failureMessage)}")
        return null
    }

    private suspend fun recoverToReplayableState(entryScreenLogicalFingerprint: String): Boolean {
        setReplayOrRecoveryStage("recover_to_replayable_state")
        val recovered = restoreToEntryScreenOrRelaunch(entryScreenLogicalFingerprint) != null
        crawlLogger?.info("recovery_attempt stage=${quote(crashContext.lastReplayOrRecoveryStage.orEmpty())} success=$recovered")
        return recovered
    }

    private fun routeParentScreenIds(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
    ): List<String> {
        val parentIds = mutableListOf<String>()
        var currentParentId = screenRecord.parentScreenId
        while (currentParentId != null) {
            parentIds += currentParentId
            currentParentId = tracker.findScreen(currentParentId)?.parentScreenId
        }
        return parentIds.asReversed()
    }

    private fun filesFor(screenRecord: CrawlScreenRecord): CapturedScreenFiles {
        return CapturedScreenFiles(
            htmlFile = File(screenRecord.htmlPath),
            xmlFile = File(screenRecord.xmlPath),
            mergedXmlFile = screenRecord.mergedXmlPath?.let(::File),
        )
    }

    private fun rewriteScreenXmlFor(
        tracker: CrawlRunTracker,
        snapshot: ScreenSnapshot,
        screenId: String,
        runStatus: CrawlRunStatus,
    ) {
        val screenRecord = tracker.findScreen(screenId) ?: return
        val crawlState = buildScreenCrawlState(tracker, snapshot, screenRecord, runStatus)
        CaptureFileStore.rewriteScreenXml(filesFor(screenRecord), snapshot, crawlState)
    }

    private fun buildScreenCrawlState(
        tracker: CrawlRunTracker,
        snapshot: ScreenSnapshot,
        screenRecord: CrawlScreenRecord,
        runStatus: CrawlRunStatus,
    ): ScreenCrawlState {
        val identity = ScreenIdentityCodec.decode(screenRecord.screenFingerprint)
            ?: ScreenIdentityFields(
                packageName = screenRecord.packageName,
                title = screenRecord.screenName,
                hints = emptyList(),
            )
        val parent = screenRecord.parentScreenId?.let { parentId ->
            ParentEdgeRef(
                screenId = parentId,
                triggerLabel = screenRecord.triggerLabel,
                triggerResourceId = screenRecord.triggerResourceId,
            )
        }
        val isRoot = screenRecord.depth == 0
        val runLevel = if (isRoot) {
            RunLevelState(
                sessionId = tracker.sessionId,
                startedAt = tracker.startedAt,
                finishedAt = null,
                status = runStatus,
                maxDepthReached = tracker.maxDiscoveredDepth(),
            )
        } else {
            null
        }
        val outbound = tracker.outboundEdges(screenRecord.screenId)
        val edgesByElement = snapshot.elements.mapNotNull { element ->
            val edge = outbound.firstOrNull { matchesElement(it, element) }
                ?: return@mapNotNull null
            element.toLinkKey() to EdgeXmlView(
                edgeId = edge.edgeId,
                status = edge.status,
                childScreenId = edge.childScreenId,
                childScreenName = edge.childScreenName,
                message = edge.message,
                approval = edge.approval,
                externalPackage = edge.externalPackage,
            )
        }.toMap()
        return ScreenCrawlState(
            screenId = screenRecord.screenId,
            depth = screenRecord.depth,
            expansionStatus = screenRecord.expansionStatus,
            isRoot = isRoot,
            screenIdentity = identity,
            parent = parent,
            route = screenRecord.route,
            runLevel = runLevel,
            edgesByElement = edgesByElement,
        )
    }

    private fun matchesElement(edge: CrawlEdgeRecord, element: PressableElement): Boolean {
        return edge.label == element.label &&
            edge.resourceId == element.resourceId &&
            edge.bounds == element.bounds &&
            edge.className == element.className &&
            edge.childIndexPath == element.childIndexPath &&
            edge.firstSeenStep == element.firstSeenStep
    }

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

    private fun formatEntryResetResult(result: EntryScreenResetResult): String {
        return "observedPackageName=${quote(result.root.packageName.orEmpty())} " +
            "outcome=${result.outcome.name.lowercase()} " +
            "observedLogicalFingerprint=${quote(result.observedLogicalFingerprint)} " +
            "expectedLogicalFingerprintPresent=${result.expectedLogicalFingerprint != null} " +
            "expectedLogicalFingerprint=${quote(result.expectedLogicalFingerprint.orEmpty())} " +
            "matchedExpectedLogical=${result.matchedExpectedLogical} " +
            "verifiedForReplay=${result.verifiedForReplay}"
    }

    private fun screenIdFor(sequenceNumber: Int): String {
        return "screen_%05d".format(sequenceNumber)
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
        parentScreenId: String,
        element: PressableElement,
        message: String,
    ): Nothing {
        throw RecoverableTraversalException(
            parentScreenId = parentScreenId,
            element = element,
            message = message,
        )
    }

    private fun abortPartialCapture(
        tracker: CrawlRunTracker,
        failedParentScreenId: String,
        session: CrawlSessionDirectory,
        rootSnapshot: ScreenSnapshot,
        rootFiles: CapturedScreenFiles,
        message: String,
        failedElement: PressableElement? = null,
    ): Nothing {
        failedElement?.let { element ->
            tracker.addEdge(
                parentScreenId = failedParentScreenId,
                element = element,
                status = CrawlEdgeStatus.FAILED,
                message = message,
            )
        }
        crawlLogger?.warn(
            "crawl_partial_abort failedParentScreenId=$failedParentScreenId message=${quote(message)} " +
                "failedElement=${failedElement?.let(::formatElement) ?: "<none>"}"
        )
        val manifestFile = saveManifest(
            session = session,
            tracker = tracker,
            status = CrawlRunStatus.PARTIAL_ABORT,
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

    private fun stopMessageForPauseReason(reason: PauseReason): String {
        return when (reason) {
            PauseReason.ELAPSED_TIME_EXCEEDED ->
                "Deep crawl stopped after reaching the elapsed-time checkpoint."

            PauseReason.FAILED_EDGE_COUNT_EXCEEDED ->
                "Deep crawl stopped after reaching the failed-edge checkpoint."

            PauseReason.EXTERNAL_PACKAGE_BOUNDARY ->
                "Deep crawl stopped at an external-package boundary."
        }
    }

    private fun saveManifest(
        session: CrawlSessionDirectory,
        tracker: CrawlRunTracker,
        status: CrawlRunStatus,
    ): File {
        if (lastLoggedManifestStatus != status) {
            crawlLogger?.info(
                "manifest_status_transition status=${status.displayName()} manifestFile=${session.manifestFile.absolutePath}"
            )
            lastLoggedManifestStatus = status
        }
        val manifest = tracker.buildManifest(status = status, finishedAt = timeProvider())
        val manifestFile = CaptureFileStore.saveManifest(
            session = session,
            manifest = manifest,
        )
        CaptureFileStore.saveGraph(
            session = session,
            graph = CrawlGraphBuilder.build(manifest),
        )
        return manifestFile
    }

    private fun logPersistedScreenCapture(
        screenId: String,
        parentScreenId: String?,
        depth: Int,
        route: CrawlRoute,
        snapshot: ScreenSnapshot,
        screenFingerprint: String,
        files: CapturedScreenFiles,
        namingEventClassName: String?,
        namingRoot: AccessibilityNodeSnapshot,
    ) {
        crawlLogger?.info(
            "screen_capture screenId=$screenId parentScreenId=${parentScreenId ?: "<root>"} depth=$depth " +
                "route=${quote(formatRoute(route))} screenName=${quote(snapshot.screenName)} " +
                "screenFingerprint=${quote(screenFingerprint)} scrollStepCount=${snapshot.scrollStepCount} " +
                "artifactPaths=${quote(formatFiles(files))}"
        )
        logNamingInputs(
            eventClassName = namingEventClassName,
            root = namingRoot,
            screenName = snapshot.screenName,
        )
    }

    private fun logNamingInputs(
        eventClassName: String?,
        root: AccessibilityNodeSnapshot,
        screenName: String,
    ) {
        val debugInfo = ScreenNaming.analyzeScreenName(
            eventClassName = eventClassName,
            selectedApp = selectedApp,
            root = root,
        )
        crawlLogger?.info(
            "screen_naming screenName=${quote(screenName)} chosenStrategy=${debugInfo.chosenStrategy} " +
                "chosenScore=${debugInfo.chosenScore ?: -1} chosenTitleIsWeak=${debugInfo.chosenTitleIsWeak} " +
                "eventClassName=${quote(debugInfo.eventClassName.orEmpty())} " +
                "eventClassCandidate=${quote(debugInfo.eventClassCandidate.orEmpty())} " +
                "textCandidates=${quote(formatNameCandidates(debugInfo.textCandidates))} " +
                "resourceIdFallback=${quote(debugInfo.resourceIdCandidate?.let { "${it.title}(${it.score})" } ?: "<none>")} " +
                "dedupFingerprint=${quote(debugInfo.dedupFingerprint)} " +
                "dedupConfidence=${debugInfo.dedupConfidence.name.lowercase()} " +
                "identityHints=${quote(formatIdentityHints(debugInfo.identityHints))}"
        )
    }

    private fun logTraversalPlan(
        screenRecord: CrawlScreenRecord,
        traversalPlan: TraversalPlan,
    ) {
        crawlLogger?.info(
            "traversal_plan screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                "eligibleCount=${traversalPlan.eligibleElements.size} skippedCount=${traversalPlan.skippedElements.size} " +
                "deterministicOrder=${quote(traversalPlan.eligibleElements.joinToString(" | ") { formatElement(it) })} " +
                "skipReasons=${quote(traversalPlan.skippedElements.joinToString(" | ") { "${it.reason}:${it.element.label}" })}"
        )
    }

    private fun logFrontierState(
        mutation: String,
        screenId: String,
        frontier: ArrayDeque<String>,
    ) {
        crawlLogger?.info(
            "frontier_mutation mutation=$mutation screenId=$screenId frontierSize=${frontier.size} frontier=${formatFrontier(frontier)}"
        )
    }

    private fun replayFailure(
        parentScreenId: String,
        element: PressableElement,
        message: String,
    ): ReplayToScreenResult.Failure {
        crawlLogger?.warn(
            "replay_result success=false parentScreenId=$parentScreenId message=${quote(message)} element=${formatElement(element)}"
        )
        return ReplayToScreenResult.Failure(
            parentScreenId = parentScreenId,
            element = element,
            message = message,
        )
    }

    private fun buildUnexpectedFailureMessage(error: Throwable): String {
        return buildString {
            append("unexpected_crawler_exception ")
            append("throwableClass=${error.javaClass.name} ")
            append("throwableMessage=${quote(error.message.orEmpty())} ")
            append("lastScreenId=${crashContext.lastScreenId ?: "<none>"} ")
            append("lastScreenName=${quote(crashContext.lastScreenName.orEmpty())} ")
            append("lastElement=${crashContext.lastElement?.let(::formatElement) ?: "<none>"} ")
            append("lastFrontier=${formatFrontier(crashContext.lastFrontierSnapshot)} ")
            append("lastReplayOrRecoveryStage=${quote(crashContext.lastReplayOrRecoveryStage.orEmpty())}")
        }
    }

    private fun rememberScreen(screenId: String?, screenName: String?) {
        crashContext.lastScreenId = screenId
        crashContext.lastScreenName = screenName
    }

    private fun rememberElement(element: PressableElement?) {
        crashContext.lastElement = element
    }

    private fun rememberFrontier(frontier: ArrayDeque<String>) {
        crashContext.lastFrontierSnapshot = frontier.toList()
    }

    private fun setReplayOrRecoveryStage(stage: String) {
        crashContext.lastReplayOrRecoveryStage = stage
    }

    private fun formatFiles(files: CapturedScreenFiles): String {
        return listOfNotNull(
            "html=${files.htmlFile.absolutePath}",
            "xml=${files.xmlFile.absolutePath}",
            files.mergedXmlFile?.let { "mergedXml=${it.absolutePath}" },
        ).joinToString(", ")
    }

    private fun formatNameCandidates(candidates: List<ScreenNameCandidate>): String {
        if (candidates.isEmpty()) {
            return "<none>"
        }
        return candidates.joinToString(" | ") { candidate ->
            "${candidate.title}(${candidate.score})"
        }
    }

    private fun formatIdentityHints(hints: List<String>): String {
        return if (hints.isEmpty()) "<none>" else hints.joinToString(" | ")
    }

    private fun formatFrontier(frontier: Iterable<String>): String {
        val values = frontier.toList()
        return if (values.isEmpty()) "<empty>" else values.joinToString(prefix = "[", postfix = "]")
    }

    private fun formatAllowedPackageNames(): String {
        return if (allowedPackageNames.isEmpty()) {
            "<empty>"
        } else {
            allowedPackageNames.joinToString(prefix = "[", postfix = "]")
        }
    }

    private fun formatDestinationMetrics(metrics: DestinationRichnessMetrics?): String {
        if (metrics == null) {
            return "<none>"
        }
        return "richnessScore=${metrics.richnessScore} " +
            "visibleNodeCount=${metrics.visibleNodeCount} " +
            "visibleTextOrContentDescriptionCount=${metrics.visibleTextOrContentDescriptionCount} " +
            "visibleTextOrContentDescriptionCharacterCount=${metrics.visibleTextOrContentDescriptionCharacterCount} " +
            "distinctPressableCount=${metrics.distinctPressableCount} " +
            "nonEmptyPressableLabelCount=${metrics.nonEmptyPressableLabelCount} " +
            "logicalFingerprintLength=${metrics.logicalFingerprintLength} " +
            "scrollableNodeCount=${metrics.scrollableNodeCount} " +
            "progressIndicatorCount=${metrics.progressIndicatorCount}"
    }

    private fun formatRoute(route: CrawlRoute): String {
        if (route.steps.isEmpty()) {
            return "<root>"
        }
        return route.steps.joinToString(" -> ") { step ->
            "${step.label}@${step.firstSeenStep}"
        }
    }

    private fun formatRouteStep(step: CrawlRouteStep): String {
        return "label=${quote(step.label)} resourceId=${quote(step.resourceId.orEmpty())} " +
            "className=${quote(step.className.orEmpty())} bounds=${quote(step.bounds)} " +
            "childIndexPath=${step.childIndexPath} firstSeenStep=${step.firstSeenStep} " +
            "expectedPackageName=${quote(step.expectedPackageName.orEmpty())}"
    }

    private fun formatElement(element: PressableElement): String {
        return "label=${quote(element.label)} resourceId=${quote(element.resourceId.orEmpty())} " +
            "className=${quote(element.className.orEmpty())} bounds=${quote(element.bounds)} " +
            "childIndexPath=${element.childIndexPath} firstSeenStep=${element.firstSeenStep}"
    }

    private fun formatEdgeRecord(edge: CrawlEdgeRecord): String {
        return "label=${quote(edge.label)} resourceId=${quote(edge.resourceId.orEmpty())} " +
            "className=${quote(edge.className.orEmpty())} bounds=${quote(edge.bounds)} " +
            "childIndexPath=${edge.childIndexPath} firstSeenStep=${edge.firstSeenStep}"
    }

    private fun quote(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
    }

    private fun screenIdentityFor(
        snapshot: ScreenSnapshot,
        root: AccessibilityNodeSnapshot,
    ): ScreenIdentity {
        return ScreenNaming.buildScreenIdentity(
            screenName = snapshot.screenName,
            packageName = snapshot.packageName,
            root = root,
        )
    }

    internal interface Host {
        suspend fun captureCurrentRootSnapshot(expectedPackageName: String?): AccessibilityNodeSnapshot?
        fun scrollForward(childIndexPath: List<Int>): Boolean
        fun scrollBackward(childIndexPath: List<Int>): Boolean
        fun click(element: PressableElement): Boolean
        fun performGlobalBack(): Boolean
        suspend fun relaunchTargetApp(selectedApp: SelectedAppRef): String?
        suspend fun awaitPauseDecision(
            reason: PauseReason,
            snapshot: PauseProgressSnapshot,
            externalPackageContext: ExternalPackageDecisionContext? = null,
        ): PauseDecision
        fun publishProgress(message: String)
        fun setActiveCrawlLogger(logger: CrawlLogger?)
    }

    internal sealed class DeepCrawlOutcome {
        abstract val summary: CrawlRunSummary

        data class Completed(
            override val summary: CrawlRunSummary,
        ) : DeepCrawlOutcome()

        data class PartialAbort(
            override val summary: CrawlRunSummary,
            val message: String,
        ) : DeepCrawlOutcome()
    }

    private sealed class ReplayToScreenResult {
        data class Success(val root: AccessibilityNodeSnapshot) : ReplayToScreenResult()

        data class Failure(
            val parentScreenId: String,
            val element: PressableElement,
            val message: String,
        ) : ReplayToScreenResult()
    }

    private sealed class ScreenPreparationResult {
        data class Success(val snapshot: ScreenSnapshot) : ScreenPreparationResult()

        data class Failure(
            val parentScreenId: String,
            val element: PressableElement,
            val message: String,
        ) : ScreenPreparationResult()
    }

    private data class OpenedChildDestination(
        val root: AccessibilityNodeSnapshot,
        val beforeClickFingerprint: String,
        val fingerprint: String,
        val settleStopReason: DestinationSettleStopReason,
        val settleElapsedMillis: Long,
        val sampleCount: Int,
        val selectedMetrics: DestinationRichnessMetrics,
    )

    private data class EdgeWorkItem(
        val edgeId: String?,
        val element: PressableElement,
    )

    private data class CrawlCrashContext(
        var lastScreenId: String? = null,
        var lastScreenName: String? = null,
        var lastElement: PressableElement? = null,
        var lastFrontierSnapshot: List<String> = emptyList(),
        var lastReplayOrRecoveryStage: String? = null,
    ) {
        fun reset() {
            lastScreenId = null
            lastScreenName = null
            lastElement = null
            lastFrontierSnapshot = emptyList()
            lastReplayOrRecoveryStage = null
        }
    }

    private class PartialCrawlAbortException(
        val summary: CrawlRunSummary,
        message: String,
    ) : IllegalStateException(message)

    private class RecoverableTraversalException(
        val parentScreenId: String,
        val element: PressableElement,
        message: String,
    ) : IllegalStateException(message)

    private companion object {
        private const val DEFAULT_MAX_POST_CLICK_SETTLE_MILLIS = 3_000L
        private const val DEFAULT_MAX_ENTRY_RESTORE_SETTLE_MILLIS = 3_000L
        private const val maxEntryRestoreCaptureAttempts = 10
    }
}

private fun ResumeMode.toLogString(): String = when (this) {
    ResumeMode.ContinueAuto -> "continue_auto"
    is ResumeMode.ResumeFromScreen -> "resume_from_screen:$screenId"
    is ResumeMode.ReExpand -> "re_expand:$screenId"
}
