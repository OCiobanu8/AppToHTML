package com.example.apptohtml.crawler

import com.example.apptohtml.diagnostics.DiagnosticLogger
import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

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
                dedupKeyToScreenId = loaded.dedupKeyToScreenId,
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
                expectedEntryIdentity = null,
                preferRelaunchWhenEntryIsAmbiguous = true,
            )
                ?: throw IllegalStateException(
                    "Target app left the foreground while resetting to the first screen."
                )

            val rootScreenId: String
            val rootFiles: CapturedScreenFiles
            val rootSnapshot: ScreenSnapshot
            val entryScreenIdentity: ScreenIdentity

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
                entryScreenIdentity = ScreenIdentity.fromRoot(
                    rootSnapshot.stepSnapshots.firstOrNull()?.root ?: liveEntryRoot
                )
                rememberScreen(rootScreenId, loadedRoot.screenName)
                logger.info(
                    "crawl_resume_hydrated rootScreenId=$rootScreenId rootScreenName=${quote(loadedRoot.screenName)} " +
                        "screensLoaded=${loaded.screens.size} edgesLoaded=${loaded.edges.size}"
                )
            } else {
                rootSnapshot = scanCurrentScreen(
                    eventClassName = liveEntryRoot.className ?: initialRoot.className ?: eventClassName,
                    initialRoot = liveEntryRoot,
                    capturePackageName = selectedApp.packageName,
                    progressPrefix = "Mapping the root screen.",
                )
                entryScreenIdentity = ScreenIdentity.fromRoot(
                    rootSnapshot.stepSnapshots.firstOrNull()?.root ?: liveEntryRoot
                )

                val rootSequence = tracker.nextScreenSequenceNumber()
                rootScreenId = screenIdFor(rootSequence)
                val rootIdentity = entryScreenIdentity.withName(
                    screenNameIdentityFor(
                        snapshot = rootSnapshot,
                        root = rootSnapshot.mergedRoot ?: liveEntryRoot,
                    )
                )
                rootFiles = CaptureFileStore.saveScreen(
                    session = session,
                    snapshot = rootSnapshot,
                    screenId = rootScreenId,
                    identity = rootIdentity,
                )
                tracker.addScreen(
                    screenId = rootScreenId,
                    snapshot = rootSnapshot,
                    identity = rootIdentity,
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
                    screenFingerprint = DedupPolicy.nameKey(rootIdentity).orEmpty(),
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
                        entryScreenIdentity = entryScreenIdentity,
                    )) {
                        is ScreenPreparationResult.Success -> prepared.snapshot
                        is ScreenPreparationResult.Failure -> {
                            handlePreparationFailure(
                                tracker = tracker,
                                session = session,
                                rootSnapshot = rootSnapshot,
                                rootFiles = rootFiles,
                                failure = prepared,
                                entryScreenIdentity = entryScreenIdentity,
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
                    entryScreenIdentity = entryScreenIdentity,
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
        entryScreenIdentity: ScreenIdentity,
        pauseTracker: PauseCheckpointTracker,
    ) {
        val topSnapshot = snapshot.stepSnapshots.firstOrNull()?.root
            ?: throw IllegalStateException("Capture for '${snapshot.screenName}' did not preserve a top-of-screen snapshot.")
        val isRootScreen = screenRecord.route.steps.isEmpty()
        val topIdentity = if (isRootScreen) {
            entryScreenIdentity
        } else {
            ScreenIdentity.fromRoot(topSnapshot)
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
                    entryScreenIdentity = entryScreenIdentity,
                    expectedTopIdentity = topIdentity,
                    isRootScreen = isRootScreen,
                )
                val activeChildRoot = openedChild.root
                val beforeClickIdentity = openedChild.beforeClickIdentity
                val currentPackageName = screenRecord.packageName
                val childPackageName = activeChildRoot.packageName ?: currentPackageName
                val afterClickIdentity = openedChild.identity
                crawlLogger?.info(
                    "edge_click_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "fingerprintType=structured beforeClickIdentity=${quote(describe(beforeClickIdentity))} afterClickIdentity=${quote(describe(afterClickIdentity))} " +
                        "topIdentity=${quote(describe(topIdentity))} settleStopReason=${openedChild.settleStopReason.name.lowercase()} " +
                        "settleElapsedMillis=${openedChild.settleElapsedMillis} settleSampleCount=${openedChild.sampleCount} " +
                        "selectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))} element=${formatElement(element)}"
                )
                val remainedInCurrentPackage = childPackageName == currentPackageName
                if (
                    remainedInCurrentPackage &&
                    NavigatedAwayPolicy(BackAffordanceCounting.forScreen(isRootScreen))
                        .navigatedAway(beforeClickIdentity, topIdentity, afterClickIdentity).not()
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

                if (childPackageName != selectedApp.packageName) {
                    // The target-app boundary is uncrossable. Accessibility cannot reveal a
                    // control's destination before the click, so the crossing is detected here,
                    // after the fact: record the edge, decline to capture, and move on. The crawler
                    // is briefly standing in the foreign app; the next edge's restore falls through
                    // to a relaunch of the target because the package probe fails.
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
                        identity = persistedIdentity(screenRecord),
                    )
                    rewriteScreenXmlFor(tracker, snapshot, screenRecord.screenId, CrawlRunStatus.IN_PROGRESS)
                    saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                    return@forEachIndexed
                }

                host.publishProgress("Mapping screen opened by '${element.label}'.")
                val childSnapshot = scanCurrentScreen(
                    eventClassName = activeChildRoot.className,
                    initialRoot = activeChildRoot,
                    capturePackageName = childPackageName,
                    progressPrefix = "Mapping screen '${element.label}'.",
                )
                val childTopRoot = childSnapshot.stepSnapshots.firstOrNull()?.root ?: activeChildRoot
                val childIdentity = ScreenIdentity.fromRoot(childTopRoot)
                val childRoute = screenRecord.route.append(
                    element.toRouteStep(
                        expectedPackageName = childSnapshot.packageName,
                        expectedDestinationIdentity = openedChild.identity,
                        expectedReplayIdentity = childIdentity,
                        expectedReplayScreenName = childSnapshot.screenName,
                    )
                )
                val childScreenName = screenNameIdentityFor(
                    snapshot = childSnapshot,
                    root = childSnapshot.mergedRoot ?: activeChildRoot,
                )
                val childFullIdentity = childIdentity.withName(childScreenName)
                val childScreenFingerprint = DedupPolicy.nameKey(childFullIdentity).orEmpty()
                // S7 — dedup resolves through DedupPolicy, which also decides eligibility.
                val existingChildScreenId = tracker.findScreenIdByIdentity(childFullIdentity)
                crawlLogger?.info(
                    "child_capture_candidate parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "depth=${screenRecord.depth + 1} route=${quote(formatRoute(childRoute))} " +
                        "candidateScreenName=${quote(childSnapshot.screenName)} candidateFingerprint=${quote(childScreenFingerprint)} " +
                        "candidateDedupConfidence=${childScreenName.confidence.name.lowercase()} " +
                        "candidateTitleDisambiguators=${quote(formatTitleDisambiguators(childScreenName.titleDisambiguators))} " +
                        "scrollStepCount=${childSnapshot.scrollStepCount} element=${formatElement(element)}"
                )
                logNamingInputs(
                    eventClassName = activeChildRoot.className,
                    root = childSnapshot.mergedRoot ?: activeChildRoot,
                    screenName = childSnapshot.screenName,
                )

                if (!childScreenName.canLinkToExisting) {
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
                            "matchedFingerprint=${quote(DedupPolicy.nameKey(existingChildScreen.identity).orEmpty())} " +
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
                        identity = childFullIdentity,
                    )
                    tracker.addScreen(
                        screenId = childScreenId,
                        snapshot = childSnapshot,
                        identity = childFullIdentity,
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
                    identity = persistedIdentity(screenRecord),
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
                val recovered = recoverToReplayableState(entryScreenIdentity)
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
        }
    }

    private suspend fun prepareScreenForExpansion(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        entryScreenIdentity: ScreenIdentity,
    ): ScreenPreparationResult {
        rememberScreen(screenRecord.screenId, screenRecord.screenName)
        setReplayOrRecoveryStage("prepare_screen_for_expansion screenId=${screenRecord.screenId}")
        val replayResult = replayRouteToScreen(
            tracker = tracker,
            screenRecord = screenRecord,
            entryScreenIdentity = entryScreenIdentity,
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
        val liveIdentity = ScreenIdentity
            .fromRoot(snapshot.mergedRoot ?: replayResult.root)
            .withName(
                screenNameIdentityFor(
                    snapshot = snapshot,
                    root = snapshot.mergedRoot ?: replayResult.root,
                )
            )
        // The screen must still be the one this route was recorded for. ReplayArrivalCheck owns
        // that rule — the name, plus a settled identity's traits — and is pinned without a device.
        //
        // Traits are evaluated against the FIRST VIEWPORT, not the scroll-merged tree, because that
        // is the tree the validation tool reads back from a capture. Using the merged tree here
        // would make the crawler and the tool ask one question of two different screens: a
        // `lacks-control` for a control below the fold would hold in the tool and fail here.
        // The name half is unchanged and still reads the merged tree.
        val arrivalRoot = snapshot.stepSnapshots.firstOrNull()?.root
            ?: snapshot.mergedRoot
            ?: replayResult.root
        val arrival = ReplayArrivalCheck.check(screenRecord.identity, liveIdentity, arrivalRoot)
        val nameComparison = SameNamePolicy.compare(screenRecord.identity, liveIdentity)
        crawlLogger?.info(
                "replay_validation destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=screen_identity expectedIdentity=${quote(DedupPolicy.nameKey(screenRecord.identity).orEmpty())} actualIdentity=${quote(DedupPolicy.nameKey(liveIdentity).orEmpty())} " +
                "route=${quote(formatRoute(screenRecord.route))}"
        )
        logNamingInputs(
            eventClassName = replayResult.root.className,
            root = snapshot.mergedRoot ?: replayResult.root,
            screenName = snapshot.screenName,
        )
        if (arrival !is ReplayArrivalCheck.Verdict.Arrived) {
            val routeElement = screenRecord.route.steps.last().toPressableElement()
            val message = when (arrival) {
                is ReplayArrivalCheck.Verdict.NameDiverged ->
                    "Route replay diverged for '${screenRecord.screenName}'. " +
                        "Expected '${screenRecord.screenName}' but found '${snapshot.screenName}'."
                // Names the failing assertion: the screen is called the right thing, so saying only
                // that it diverged would report "Expected X but found X" (a2h-c2b.6).
                is ReplayArrivalCheck.Verdict.TraitsDoNotHold ->
                    "Route replay reached '${screenRecord.screenName}' but its settled identity " +
                        "does not hold there: ${arrival.describe()}."
                ReplayArrivalCheck.Verdict.Arrived -> error("unreachable")
            }
            crawlLogger?.warn(
                "replay_arrival_diverged destinationScreenId=${screenRecord.screenId} " +
                    "verdict=${arrival::class.simpleName} message=${quote(message)}"
            )
            return ScreenPreparationResult.Failure(
                parentScreenId = screenRecord.parentScreenId ?: screenRecord.screenId,
                element = routeElement,
                message = message,
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
        entryScreenIdentity: ScreenIdentity,
    ) {
        rememberElement(failure.element)
        crawlLogger?.warn(
            "screen_preparation_failure parentScreenId=${failure.parentScreenId} message=${quote(failure.message)} " +
                "element=${formatElement(failure.element)}"
        )
        val recovered = recoverToReplayableState(entryScreenIdentity)
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
        entryScreenIdentity: ScreenIdentity,
        expectedTopIdentity: ScreenIdentity,
        isRootScreen: Boolean,
    ): OpenedChildDestination {
        crawlLogger?.info(
            "child_open_restore_attempt parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "triggerLabel=${quote(element.label)} " +
                "expectedTopIdentity=${quote(describe(expectedTopIdentity))}"
        )
        val liveScreenRoot = restoreLiveScreenForEdge(
            tracker = tracker,
            screenRecord = screenRecord,
            element = element,
            entryScreenIdentity = entryScreenIdentity,
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

        val liveTopIdentity = ScreenIdentity.fromRoot(topRoot)
        // S2 — pre-open top validation. The root's stored identity was captured before the crawler
        // ever navigated away, so it carries no back affordance, while the live root grows one once
        // it has. The policy is what ignores that now, not the builder.
        val topComparison = SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen))
            .compare(expectedTopIdentity, liveTopIdentity)
        crawlLogger?.info(
            "screen_top_validation screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=structured expectedIdentity=${quote(describe(expectedTopIdentity))} " +
                "actualIdentity=${quote(describe(liveTopIdentity))} matchReason=${topComparison.reason.name.lowercase()} " +
                "missingIdentities=${topComparison.missing.size} extraIdentities=${topComparison.extra.size}"
        )
        if (!topComparison.matched) {
            crawlLogger?.info(
                "child_open_restore_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                    "triggerLabel=${quote(element.label)} " +
                    "destinationIdentityChanged=false result=top_fingerprint_mismatch"
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

        val beforeClickIdentity = ScreenIdentity.fromRoot(targetStepRoot)
        crawlLogger?.info(
            "edge_click_prepare parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "fingerprintType=structured beforeClickIdentity=${quote(describe(beforeClickIdentity))} element=${formatElement(element)}"
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
            beforeClickIdentity = beforeClickIdentity,
            expectedTopIdentity = expectedTopIdentity,
            isRootScreen = isRootScreen,
        )
        crawlLogger?.info(
            "child_open_restore_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                "triggerLabel=${quote(element.label)} " +
                "actualPackageName=${quote(openedChild.root.packageName.orEmpty())} " +
                "destinationIdentityChanged=${!SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen)).compare(beforeClickIdentity, openedChild.identity).matched} " +
                "settleStopReason=${openedChild.settleStopReason.name.lowercase()} " +
                "settleElapsedMillis=${openedChild.settleElapsedMillis} settleSampleCount=${openedChild.sampleCount} " +
                "selectedMetrics=${quote(formatDestinationMetrics(openedChild.selectedMetrics))} result=captured"
        )
        return openedChild
    }

    private suspend fun captureChildDestinationAfterClick(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        beforeClickIdentity: ScreenIdentity,
        expectedTopIdentity: ScreenIdentity,
        isRootScreen: Boolean,
    ): OpenedChildDestination {
        val identity: (AccessibilityNodeSnapshot) -> ScreenIdentity = { root ->
            ScreenIdentity.fromRoot(root)
        }
        val result = destinationSettler.settle(
            DestinationSettleRequest(
                parentPackageName = screenRecord.packageName,
                expectedPackageName = null,
                beforeClickIdentity = beforeClickIdentity,
                topIdentity = expectedTopIdentity,
                mode = DestinationSettleMode.DISCOVERY,
                sameScreenPolicy = SameScreenPolicy(BackAffordanceCounting.forScreen(isRootScreen)),
                identity = identity,
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
            expectedChildPackageName = null,
            beforeClickIdentity = beforeClickIdentity,
            expectedTopIdentity = expectedTopIdentity,
            settleResult = result,
        )

        val selectedSample = result.samples.firstOrNull { sample ->
            sample.root != null &&
                sample.root == result.root &&
                sample.identity == result.identity
        } ?: result.samples.lastOrNull { sample -> sample.root != null }
        val selectedRoot = result.root ?: selectedSample?.root ?: failCurrentEdge(
            parentScreenId = screenRecord.screenId,
            element = element,
            message = "The target app was lost immediately after clicking '${element.label}'.",
        )
        val selectedIdentity = result.identity ?: selectedSample?.identity ?: identity(selectedRoot)
        val selectedMetrics = selectedSample?.metrics ?: DestinationRichnessMetrics.from(
            root = selectedRoot,
            logicalFingerprint = ScreenIdentityCodec.encodeLogical(
                identity = selectedIdentity,
                countBackAffordances = BackAffordanceCounting.forScreen(isRootScreen),
            ),
        )

        return OpenedChildDestination(
            root = selectedRoot,
            beforeClickIdentity = beforeClickIdentity,
            identity = selectedIdentity,
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
        beforeClickIdentity: ScreenIdentity,
        expectedTopIdentity: ScreenIdentity,
        sample: DestinationSample,
    ) {
        crawlLogger?.info(
            "child_destination_observe_attempt parentScreenId=${screenRecord.screenId} " +
                "parentScreenName=${quote(screenRecord.screenName)} triggerLabel=${quote(element.label)} " +
                "attempt=${sample.attemptNumber} elapsedSettleMillis=${sample.elapsedMillis} " +
                "maxSettleMillis=$maxPostClickSettleMillis " +
                "expectedPackageName=${quote(expectedChildPackageName.orEmpty())} " +
                "beforeClickIdentity=${quote(describe(beforeClickIdentity))} topIdentity=${quote(describe(expectedTopIdentity))}"
        )
    }

    private fun logChildDestinationObserveResult(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickIdentity: ScreenIdentity,
        expectedTopIdentity: ScreenIdentity,
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
                "beforeClickIdentity=${quote(describe(beforeClickIdentity))} topIdentity=${quote(describe(expectedTopIdentity))} " +
                "observedIdentity=${quote(describe(sample.identity))} " +
                "eligibilityReason=${sample.eligibilityReason.name.lowercase()} eligible=${sample.eligible} " +
                "packageChanged=${sample.packageChanged} identityChangedFromBefore=${sample.identityChangedFromBefore} " +
                "identityChangedFromTop=${sample.identityChangedFromTop} sameIdentityAsPrevious=${sample.sameIdentityAsPrevious} " +
                "becameCurrentBest=${sample.becameCurrentBest} " +
                "metrics=${quote(formatDestinationMetrics(sample.metrics))} result=$result"
        )
    }

    private fun logChildDestinationSettleSamples(
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        expectedChildPackageName: String?,
        beforeClickIdentity: ScreenIdentity,
        expectedTopIdentity: ScreenIdentity,
        settleResult: DestinationSettleResult,
    ) {
        settleResult.samples.forEach { sample ->
            logChildDestinationObserveAttempt(
                screenRecord = screenRecord,
                element = element,
                expectedChildPackageName = expectedChildPackageName,
                beforeClickIdentity = beforeClickIdentity,
                expectedTopIdentity = expectedTopIdentity,
                sample = sample,
            )
            logChildDestinationObserveResult(
                screenRecord = screenRecord,
                element = element,
                expectedChildPackageName = expectedChildPackageName,
                beforeClickIdentity = beforeClickIdentity,
                expectedTopIdentity = expectedTopIdentity,
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
                "selectedIdentity=${quote(describe(settleResult.identity))} " +
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
                sample.identity == settleResult.identity
        }?.metrics
    }

    private suspend fun restoreLiveScreenForEdge(
        tracker: CrawlRunTracker,
        screenRecord: CrawlScreenRecord,
        element: PressableElement,
        entryScreenIdentity: ScreenIdentity,
    ): AccessibilityNodeSnapshot {
        if (screenRecord.route.steps.isEmpty()) {
            setReplayOrRecoveryStage("restore_root_for_edge screenId=${screenRecord.screenId}")
            return restoreToEntryScreenOrRelaunch(entryScreenIdentity) ?: failCurrentEdge(
                parentScreenId = screenRecord.screenId,
                element = element,
                message = "Could not restore the root screen before opening '${element.label}'.",
            )
        }

        return when (val replayResult = replayRouteToScreen(
            tracker = tracker,
            screenRecord = screenRecord,
            entryScreenIdentity = entryScreenIdentity,
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
        entryScreenIdentity: ScreenIdentity,
    ): ReplayToScreenResult {
        crawlLogger?.info(
            "replay_attempt destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                "expectedScreenFingerprint=${quote(DedupPolicy.nameKey(screenRecord.identity).orEmpty())} route=${quote(formatRoute(screenRecord.route))}"
        )
        if (screenRecord.route.steps.isEmpty()) {
            val entryRoot = restoreToEntryScreenOrRelaunch(entryScreenIdentity)
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

        var currentRoot = restoreToEntryScreenOrRelaunch(entryScreenIdentity)
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

            val routeStepIsRootScreen = parentScreen.route.steps.isEmpty()
            val routeStepIdentity: (AccessibilityNodeSnapshot) -> ScreenIdentity = { root ->
                ScreenIdentity.fromRoot(root)
            }
            val routeStepPolicy = SameScreenPolicy(BackAffordanceCounting.forScreen(routeStepIsRootScreen))
            val topIdentity = if (routeStepIsRootScreen) {
                entryScreenIdentity
            } else {
                ScreenIdentity.fromRoot(topRoot)
            }
            val beforeClickIdentity = routeStepIdentity(targetStepRoot)
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
                    beforeClickIdentity = beforeClickIdentity,
                    topIdentity = topIdentity,
                    knownDestinationIdentity = routeStep.expectedDestinationIdentity,
                    mode = DestinationSettleMode.ROUTE_REPLAY,
                    sameScreenPolicy = routeStepPolicy,
                    identity = routeStepIdentity,
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
                beforeClickIdentity = beforeClickIdentity,
                expectedTopIdentity = topIdentity,
                settleResult = settleResult,
            )
            val nextRoot = settleResult.root ?: return replayFailure(
                parentScreenId = parentScreenId,
                element = routeStep.toPressableElement(),
                message = "The target app was lost while replaying '${screenRecord.screenName}'.",
            )
            val afterClickIdentity = settleResult.identity ?: routeStepIdentity(nextRoot)
            crawlLogger?.info(
                "replay_route_step_settle_result destinationScreenId=${screenRecord.screenId} " +
                    "destinationScreenName=${quote(screenRecord.screenName)} stepIndex=$index " +
                    "parentScreenId=$parentScreenId expectedPackageName=${quote(routeStep.expectedPackageName.orEmpty())} " +
                    "knownDestinationIdentity=${quote(describe(routeStep.expectedDestinationIdentity))} " +
                    "selectedPackageName=${quote(settleResult.packageName.orEmpty())} " +
                    "selectedIdentity=${quote(describe(afterClickIdentity))} sampleCount=${settleResult.samples.size} " +
                    "elapsedSettleMillis=${settleResult.elapsedMillis} " +
                    "stopReason=${settleResult.stopReason.name.lowercase()} " +
                    "selectionReason=${settleResult.selectionReason?.name?.lowercase().orEmpty()} " +
                    "selectedMetrics=${quote(formatDestinationMetrics(selectedMetrics(settleResult)))} " +
                    "step=${formatRouteStep(routeStep)}"
            )
            // S3 - "did that click navigate?", through the named policy rather than two `==`s.
            if (
                !NavigatedAwayPolicy(BackAffordanceCounting.forScreen(routeStepIsRootScreen))
                    .navigatedAway(beforeClickIdentity, topIdentity, afterClickIdentity)
            ) {
                return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Clicking '${routeStep.label}' did not navigate while replaying '${screenRecord.screenName}'.",
                )
            }
            crawlLogger?.info(
                "replay_route_step_result destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "fingerprintType=structured stepIndex=$index beforeClickIdentity=${quote(describe(beforeClickIdentity))} " +
                    "topIdentity=${quote(describe(topIdentity))} afterClickIdentity=${quote(describe(afterClickIdentity))} " +
                    "step=${formatRouteStep(routeStep)}"
            )

            val expectedReplayIdentity = routeStep.expectedReplayIdentity
            val expectedReplayScreenName = routeStep.expectedReplayScreenName
                ?: if (index == screenRecord.route.steps.lastIndex) screenRecord.screenName else null
            // S4 - route-step validation, now reporting what differed rather than only that it did.
            if (expectedReplayIdentity != null) {
                val observedReplayIdentity = ScreenIdentity.fromRoot(nextRoot)
                // Back affordances always count here; RouteStepDestinationCheck owns that rule
                // and the reason, and is pinned so it cannot quietly become parent-derived.
                val comparison =
                    RouteStepDestinationCheck.compare(expectedReplayIdentity, observedReplayIdentity)
                val matched = comparison.matched
                crawlLogger?.info(
                    "replay_route_step_validation destinationScreenId=${screenRecord.screenId} " +
                        "destinationScreenName=${quote(screenRecord.screenName)} stepIndex=$index " +
                        "expectedReplayScreenName=${quote(expectedReplayScreenName.orEmpty())} " +
                        "expectedReplayIdentity=${quote(describe(expectedReplayIdentity))} " +
                        "observedReplayIdentity=${quote(describe(observedReplayIdentity))} missingIdentities=${comparison.missing.size} extraIdentities=${comparison.extra.size} " +
                        "matchedExpectedReplay=$matched step=${formatRouteStep(routeStep)}"
                )
                if (!matched) {
                    val expectedScreenLabel = expectedReplayScreenName?.takeIf { it.isNotBlank() }
                        ?: "<unnamed>"
                    return replayFailure(
                        parentScreenId = parentScreenId,
                        element = routeStep.toPressableElement(),
                        message = "Route replay step $index for '${routeStep.label}' reached an unexpected screen while replaying '${screenRecord.screenName}': " +
                            "expected '$expectedScreenLabel' (replay identity '${describe(expectedReplayIdentity)}') " +
                            "but observed replay identity '${describe(observedReplayIdentity)}'.",
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
        expectedEntryIdentity: ScreenIdentity? = null,
    ): EntryScreenResetResult {
        return scrollScanCoordinator.rewindToEntryScreen(
            initialRoot = initialRoot,
            targetPackageName = targetPackageName,
            expectedEntryIdentity = expectedEntryIdentity,
            tryBack = { host.performGlobalBack() },
            captureCurrentRoot = {
                host.captureCurrentRootSnapshot(expectedPackageName = null)
            },
            onProgress = host::publishProgress,
        )
    }

    private suspend fun restoreToEntryScreenOrRelaunch(
        expectedEntryIdentity: ScreenIdentity? = null,
        preferRelaunchWhenEntryIsAmbiguous: Boolean = false,
    ): AccessibilityNodeSnapshot? {
        setReplayOrRecoveryStage("restore_to_entry_or_relaunch")
        val currentRoot = host.captureCurrentRootSnapshot(expectedPackageName = null)
        crawlLogger?.info(
            "entry_restore_probe currentPackage=${currentRoot?.packageName.orEmpty()} " +
                "expectedEntryIdentityPresent=${expectedEntryIdentity != null} " +
                "preferRelaunchWhenEntryIsAmbiguous=$preferRelaunchWhenEntryIsAmbiguous"
        )
        if (currentRoot?.packageName == selectedApp.packageName) {
            val entryIsAmbiguous = preferRelaunchWhenEntryIsAmbiguous &&
                expectedEntryIdentity == null &&
                !EntryScreenBackAffordanceDetector.hasVisibleInAppBackAffordance(currentRoot)
            crawlLogger?.info(
                "entry_restore_attempt strategy=restore_to_entry currentClass=${currentRoot.className.orEmpty()} " +
                    "entryIsAmbiguous=$entryIsAmbiguous"
            )
            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = selectedApp.packageName,
                initialRoot = currentRoot,
                expectedEntryIdentity = expectedEntryIdentity,
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
                        "observedPackageName=${quote("")} observedIdentity=${quote("")} " +
                        "expectedIdentityPresent=${expectedEntryIdentity != null} " +
                        "expectedIdentity=${quote(describe(expectedEntryIdentity))} " +
                        "outcome=capture_missing matchedExpectedLogical=false verifiedForReplay=false"
                )
                shouldContinueSampling = elapsedMillis < maxEntryRestoreSettleMillis
                continue
            }
            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = selectedApp.packageName,
                initialRoot = relaunchedRoot,
                expectedEntryIdentity = expectedEntryIdentity,
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

        val failureMessage = if (expectedEntryIdentity != null) {
            "The expected entry screen did not settle after relaunch."
        } else {
            "No entry screen root was captured after relaunch."
        }
        crawlLogger?.warn("entry_restore_result strategy=relaunch success=false message=${quote(failureMessage)}")
        return null
    }

    private suspend fun recoverToReplayableState(entryScreenIdentity: ScreenIdentity): Boolean {
        setReplayOrRecoveryStage("recover_to_replayable_state")
        val recovered = restoreToEntryScreenOrRelaunch(entryScreenIdentity) != null
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

    /**
     * The identity a screen's files are written with — XML and HTML alike.
     *
     * The record already holds the whole identity, so this persists it rather than rebuilding a
     * name-only copy. The fallbacks are kept: a record whose naming pass produced nothing is still
     * written under the screen's own package and name.
     *
     * One producer on purpose. The page and the XML are rewritten from different call sites, and
     * two copies of this rule would let a screen's two files disagree about what screen it is.
     */
    private fun persistedIdentity(screenRecord: CrawlScreenRecord): ScreenIdentity {
        val name = screenRecord.identity.name
        return screenRecord.identity.withName(
            ScreenNameIdentity(
                packageName = name?.packageName ?: screenRecord.packageName,
                screenName = name?.screenName ?: screenRecord.screenName,
                titleDisambiguators = name?.titleDisambiguators.orEmpty(),
                confidence = name?.confidence ?: ScreenDedupConfidence.WEAK,
            )
        )
    }

    private fun buildScreenCrawlState(
        tracker: CrawlRunTracker,
        snapshot: ScreenSnapshot,
        screenRecord: CrawlScreenRecord,
        runStatus: CrawlRunStatus,
    ): ScreenCrawlState {
        val identity = persistedIdentity(screenRecord)
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
        return ElementFingerprint.of(edge) == ElementFingerprint.of(element)
    }

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

    private fun formatEntryResetResult(result: EntryScreenResetResult): String {
        return "observedPackageName=${quote(result.root.packageName.orEmpty())} " +
            "outcome=${result.outcome.name.lowercase()} " +
            "observedIdentity=${quote(describe(result.observedIdentity))} " +
            "expectedIdentityPresent=${result.expectedIdentity != null} " +
            "expectedIdentity=${quote(describe(result.expectedIdentity))} " +
            "matchedExpectedLogical=${result.matchedExpectedLogical} " +
            "matchedCompatibleLogical=${result.outcome == EntryScreenResetOutcome.MATCHED_COMPATIBLE_LOGICAL} " +
            "entryFingerprintMatchReason=${result.entryFingerprintMatchReason?.name?.lowercase().orEmpty()} " +
            "missingIdentities=${result.missingIdentities.size} extraIdentities=${result.extraIdentities.size} " +
            "entryFingerprintExpectedCount=${result.entryFingerprintExpectedCount} " +
            "entryFingerprintObservedCount=${result.entryFingerprintObservedCount} " +
            "entryFingerprintOverlapCount=${result.entryFingerprintOverlapCount} " +
            "entryFingerprintExpectedCoverage=${formatEntryFingerprintMetric(result.entryFingerprintExpectedCoverage)} " +
            "entryFingerprintObservedCoverage=${formatEntryFingerprintMetric(result.entryFingerprintObservedCoverage)} " +
            "entryFingerprintDiceSimilarity=${formatEntryFingerprintMetric(result.entryFingerprintDiceSimilarity)} " +
            "verifiedForReplay=${result.verifiedForReplay}"
    }

    private fun formatEntryFingerprintMetric(value: Double): String {
        return String.format(Locale.US, "%.3f", value)
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
                "titleDisambiguators=${quote(formatTitleDisambiguators(debugInfo.titleDisambiguators))}"
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

    private fun formatTitleDisambiguators(values: List<String>): String {
        return if (values.isEmpty()) "<none>" else values.joinToString(" | ")
    }

    private fun formatFrontier(frontier: Iterable<String>): String {
        val values = frontier.toList()
        return if (values.isEmpty()) "<empty>" else values.joinToString(prefix = "[", postfix = "]")
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

    /** Log-friendly rendering of an identity's content half. */
    private fun describe(identity: ScreenIdentity?): String {
        return identity?.let(ScreenIdentityCodec::encodeContent).orEmpty()
    }

    private fun quote(value: String): String {
        return "\"${value.replace("\"", "\\\"")}\""
    }

    private fun screenNameIdentityFor(
        snapshot: ScreenSnapshot,
        root: AccessibilityNodeSnapshot,
    ): ScreenNameIdentity {
        return ScreenNaming.buildScreenNameIdentity(
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
        val beforeClickIdentity: ScreenIdentity,
        val identity: ScreenIdentity,
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
