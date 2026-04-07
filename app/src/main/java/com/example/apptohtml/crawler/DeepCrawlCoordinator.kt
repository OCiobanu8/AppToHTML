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
    private val createSession: (Long) -> CrawlSessionDirectory,
    private val scrollScanCoordinator: ScrollScanCoordinator = ScrollScanCoordinator(),
    private val scanScreenOverride: (suspend (
        eventClassName: String?,
        initialRoot: AccessibilityNodeSnapshot,
        capturePackageName: String?,
        progressPrefix: String,
    ) -> ScreenSnapshot)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val resolvedLinksByScreenId = mutableMapOf<String, MutableMap<PressableElementLinkKey, String>>()
    private val crashContext = CrawlCrashContext()
    private var crawlLogger: CrawlLogger? = null
    private var lastLoggedManifestStatus: CrawlRunStatus? = null

    suspend fun crawl(
        initialRoot: AccessibilityNodeSnapshot,
        eventClassName: String?,
    ): DeepCrawlOutcome {
        val crawlStartedAt = timeProvider()
        val blacklist = loadBlacklist()
        val session = createSession(crawlStartedAt)
        val tracker = CrawlRunTracker(
            sessionId = session.sessionId,
            packageName = selectedApp.packageName,
            startedAt = crawlStartedAt,
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
        logger.info(
            "crawl_start startedAt=$crawlStartedAt packageName=${selectedApp.packageName} " +
                "appName=${selectedApp.appName.ifBlank { "<blank>" }} initialEventClass=${eventClassName.orEmpty()} " +
                "manifestFile=${session.manifestFile.absolutePath} logFile=${session.logFile.absolutePath}"
        )

        try {
            val liveEntryRoot = restoreToEntryScreenOrRelaunch(
                expectedEntryLogicalFingerprint = null,
                preferRelaunchWhenEntryIsAmbiguous = true,
            )
                ?: throw IllegalStateException(
                    "Target app left the foreground while resetting to the first screen."
                )

            val rootSnapshot = scanCurrentScreen(
                eventClassName = liveEntryRoot.className ?: initialRoot.className ?: eventClassName,
                initialRoot = liveEntryRoot,
                capturePackageName = selectedApp.packageName,
                progressPrefix = "Mapping the root screen.",
            )
            val entryScreenLogicalFingerprint = scrollScanCoordinator.logicalEntryViewportFingerprint(
                rootSnapshot.stepSnapshots.firstOrNull()?.root ?: liveEntryRoot
            )

            val rootSequence = tracker.nextScreenSequenceNumber()
            val rootScreenId = screenIdFor(rootSequence)
            val rootScreenIdentity = screenIdentityFor(
                snapshot = rootSnapshot,
                root = rootSnapshot.mergedRoot ?: liveEntryRoot,
            )
            val rootScreenFingerprint = rootScreenIdentity.fingerprint
            val rootFiles = CaptureFileStore.saveScreen(
                session = session,
                snapshot = rootSnapshot,
                sequenceNumber = rootSequence,
                screenPrefix = "root",
            )
            tracker.addScreen(
                screenId = rootScreenId,
                snapshot = rootSnapshot,
                screenFingerprint = rootScreenFingerprint,
                indexFingerprint = rootScreenIdentity.canLinkToExisting,
                files = rootFiles,
                parentScreenId = null,
                triggerElement = null,
                route = CrawlRoute(),
                depth = 0,
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
            saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)

            val frontier = ArrayDeque<String>()
            frontier.add(rootScreenId)
            rememberFrontier(frontier)
            logFrontierState(
                mutation = "enqueue_initial_root",
                screenId = rootScreenId,
                frontier = frontier,
            )
            var cachedRootSnapshot: ScreenSnapshot? = rootSnapshot

            while (frontier.isNotEmpty()) {
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
    ) {
        val topSnapshot = snapshot.stepSnapshots.firstOrNull()?.root
            ?: throw IllegalStateException("Capture for '${snapshot.screenName}' did not preserve a top-of-screen snapshot.")
        val usesEntryFingerprint = screenRecord.route.steps.isEmpty()
        val topFingerprint = if (usesEntryFingerprint) {
            entryScreenLogicalFingerprint
        } else {
            scrollScanCoordinator.logicalViewportFingerprint(topSnapshot)
        }
        val traversalPlan = TraversalPlanner.planTraversal(snapshot, blacklist)
        rememberScreen(screenRecord.screenId, screenRecord.screenName)
        logTraversalPlan(screenRecord, traversalPlan)

        traversalPlan.skippedElements.forEach { skipped ->
            tracker.addEdge(
                parentScreenId = screenRecord.screenId,
                element = skipped.element,
                status = CrawlEdgeStatus.SKIPPED_BLACKLIST,
                message = skipped.reason,
            )
            crawlLogger?.info(
                "edge_skipped_blacklist parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                    "reason=${quote(skipped.reason)} element=${formatElement(skipped.element)}"
            )
        }
        saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)

        if (traversalPlan.eligibleElements.isEmpty()) {
            crawlLogger?.info(
                "screen_expansion_complete screenId=${screenRecord.screenId} screenName=${quote(screenRecord.screenName)} " +
                    "result=no_eligible_elements"
            )
            return
        }

        host.publishProgress(
            "Mapped '${snapshot.screenName}'. Visiting ${traversalPlan.eligibleElements.size} target(s)."
        )

        traversalPlan.eligibleElements.forEachIndexed { index, element ->
            rememberElement(element)
            try {
                host.publishProgress(
                    "Visiting target ${index + 1} of ${traversalPlan.eligibleElements.size} from '${snapshot.screenName}': ${element.label}"
                )
                crawlLogger?.info(
                    "edge_visit_start parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "edgeIndex=${index + 1}/${traversalPlan.eligibleElements.size} element=${formatElement(element)}"
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
                        host.captureCurrentRootSnapshot(selectedApp.packageName)
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
                        "fingerprintType=logical expectedFingerprint=${quote(topFingerprint)} actualFingerprint=${quote(liveTopFingerprint)}"
                )
                if (liveTopFingerprint != topFingerprint) {
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
                        host.captureCurrentRootSnapshot(selectedApp.packageName)
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

                val childInitialRoot = host.captureCurrentRootSnapshot(expectedPackageName = null)
                    ?: failCurrentEdge(
                        parentScreenId = screenRecord.screenId,
                        element = element,
                        message = "The target app was lost immediately after clicking '${element.label}'.",
                    )
                val afterClickFingerprint = if (usesEntryFingerprint) {
                    scrollScanCoordinator.logicalEntryViewportFingerprint(childInitialRoot)
                } else {
                    scrollScanCoordinator.logicalViewportFingerprint(childInitialRoot)
                }
                crawlLogger?.info(
                    "edge_click_result parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "fingerprintType=logical beforeClickFingerprint=${quote(beforeClickFingerprint)} afterClickFingerprint=${quote(afterClickFingerprint)} " +
                        "topFingerprint=${quote(topFingerprint)} element=${formatElement(element)}"
                )
                if (afterClickFingerprint == beforeClickFingerprint || afterClickFingerprint == topFingerprint) {
                    tracker.addEdge(
                        parentScreenId = screenRecord.screenId,
                        element = element,
                        status = CrawlEdgeStatus.SKIPPED_NO_NAVIGATION,
                        message = "No distinct child screen detected.",
                    )
                    crawlLogger?.info(
                        "edge_skipped_no_navigation parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                            "element=${formatElement(element)}"
                    )
                    saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                    return@forEachIndexed
                }

                host.publishProgress("Mapping screen opened by '${element.label}'.")
                val childSnapshot = scanCurrentScreen(
                    eventClassName = childInitialRoot.className,
                    initialRoot = childInitialRoot,
                    capturePackageName = childInitialRoot.packageName ?: selectedApp.packageName,
                    progressPrefix = "Mapping screen '${element.label}'.",
                )
                val childScreenIdentity = screenIdentityFor(
                    snapshot = childSnapshot,
                    root = childSnapshot.mergedRoot ?: childInitialRoot,
                )
                val childScreenFingerprint = childScreenIdentity.fingerprint
                val existingChildScreenId = if (childScreenIdentity.canLinkToExisting) {
                    tracker.findScreenIdByFingerprint(childScreenFingerprint)
                } else {
                    null
                }
                crawlLogger?.info(
                    "child_capture_candidate parentScreenId=${screenRecord.screenId} parentScreenName=${quote(screenRecord.screenName)} " +
                        "depth=${screenRecord.depth + 1} route=${quote(formatRoute(screenRecord.route.append(element.toRouteStep())))} " +
                        "candidateScreenName=${quote(childSnapshot.screenName)} candidateFingerprint=${quote(childScreenFingerprint)} " +
                        "candidateDedupConfidence=${childScreenIdentity.confidence.name.lowercase()} " +
                        "candidateIdentityHints=${quote(formatIdentityHints(childScreenIdentity.identityHints))} " +
                        "scrollStepCount=${childSnapshot.scrollStepCount} element=${formatElement(element)}"
                )
                logNamingInputs(
                    eventClassName = childInitialRoot.className,
                    root = childSnapshot.mergedRoot ?: childInitialRoot,
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
                    tracker.addEdge(
                        parentScreenId = screenRecord.screenId,
                        childScreenId = existingChildScreenId,
                        element = element,
                        status = CrawlEdgeStatus.LINKED_EXISTING,
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
                        sequenceNumber = childSequence,
                        screenPrefix = "child",
                    )
                    tracker.addScreen(
                        screenId = childScreenId,
                        snapshot = childSnapshot,
                        screenFingerprint = childScreenFingerprint,
                        indexFingerprint = childScreenIdentity.canLinkToExisting,
                        files = childFiles,
                        parentScreenId = screenRecord.screenId,
                        triggerElement = element,
                        route = screenRecord.route.append(element.toRouteStep()),
                        depth = screenRecord.depth + 1,
                    )
                    tracker.addEdge(
                        parentScreenId = screenRecord.screenId,
                        childScreenId = childScreenId,
                        element = element,
                        status = CrawlEdgeStatus.CAPTURED,
                        message = "Captured child screen '${childSnapshot.screenName}'.",
                    )
                    rememberScreen(childScreenId, childSnapshot.screenName)
                    logPersistedScreenCapture(
                        screenId = childScreenId,
                        parentScreenId = screenRecord.screenId,
                        depth = screenRecord.depth + 1,
                        route = screenRecord.route.append(element.toRouteStep()),
                        snapshot = childSnapshot,
                        screenFingerprint = childScreenFingerprint,
                        files = childFiles,
                        namingEventClassName = childInitialRoot.className,
                        namingRoot = childSnapshot.mergedRoot ?: childInitialRoot,
                    )
                    resolvedLinksByScreenId
                        .getOrPut(screenRecord.screenId) { mutableMapOf() }[element.toLinkKey()] =
                        childFiles.htmlFile.name
                    resolvedLinksByScreenId.putIfAbsent(childScreenId, mutableMapOf())
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
                saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
            } catch (edgeFailure: RecoverableTraversalException) {
                crawlLogger?.warn(
                    "edge_failure_recoverable parentScreenId=${edgeFailure.parentScreenId} message=${quote(edgeFailure.message.orEmpty())} " +
                        "element=${formatElement(edgeFailure.element)}"
                )
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
                        failedElement = edgeFailure.element,
                    )
                }

                tracker.addEdge(
                    parentScreenId = edgeFailure.parentScreenId,
                    element = edgeFailure.element,
                    status = CrawlEdgeStatus.FAILED,
                    message = edgeFailure.message,
                )
                saveManifest(session, tracker, CrawlRunStatus.IN_PROGRESS)
                host.publishProgress(
                    "Recovered after '${edgeFailure.element.label}' failed. Continuing with the next target."
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
            capturePackageName = replayResult.root.packageName ?: selectedApp.packageName,
            progressPrefix = "Replaying route to '${screenRecord.screenName}'.",
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
            rememberElement(routeStep.toPressableElement())
            setReplayOrRecoveryStage(
                "replay_route_step destinationScreenId=${screenRecord.screenId} stepIndex=$index label=${quote(routeStep.label)}"
            )
            crawlLogger?.info(
                "replay_route_step destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "stepIndex=$index parentScreenId=$parentScreenId step=${formatRouteStep(routeStep)}"
            )
            val topRoot = scrollScanCoordinator.rewindToTop(
                selectedApp = selectedApp,
                initialRoot = currentRoot,
                tryScrollBackward = { path -> host.scrollBackward(path) },
                captureCurrentRoot = {
                    host.captureCurrentRootSnapshot(selectedApp.packageName)
                },
                onProgress = host::publishProgress,
            )

            val targetStepRoot = scrollScanCoordinator.moveToStep(
                selectedApp = selectedApp,
                initialRoot = topRoot,
                targetStepIndex = routeStep.firstSeenStep,
                tryScrollForward = { path -> host.scrollForward(path) },
                captureCurrentRoot = {
                    host.captureCurrentRootSnapshot(selectedApp.packageName)
                },
                onProgress = host::publishProgress,
            ) ?: return replayFailure(
                parentScreenId = parentScreenId,
                element = routeStep.toPressableElement(),
                message = "Could not scroll back to '${routeStep.label}' while replaying '${screenRecord.screenName}'.",
            )

            val beforeClickFingerprint = scrollScanCoordinator.logicalViewportFingerprint(targetStepRoot)
            if (!host.click(routeStep.toPressableElement())) {
                return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Could not click '${routeStep.label}' while replaying '${screenRecord.screenName}'.",
                )
            }

            val nextRoot = host.captureCurrentRootSnapshot(expectedPackageName = null)
                ?: return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "The target app was lost while replaying '${screenRecord.screenName}'.",
                )
            val afterClickFingerprint = scrollScanCoordinator.logicalViewportFingerprint(nextRoot)
            crawlLogger?.info(
                "replay_route_step_result destinationScreenId=${screenRecord.screenId} destinationScreenName=${quote(screenRecord.screenName)} " +
                    "fingerprintType=logical stepIndex=$index beforeClickFingerprint=${quote(beforeClickFingerprint)} " +
                    "afterClickFingerprint=${quote(afterClickFingerprint)} step=${formatRouteStep(routeStep)}"
            )
            if (afterClickFingerprint == beforeClickFingerprint) {
                return replayFailure(
                    parentScreenId = parentScreenId,
                    element = routeStep.toPressableElement(),
                    message = "Clicking '${routeStep.label}' did not navigate while replaying '${screenRecord.screenName}'.",
                )
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
    ): ScreenSnapshot {
        scanScreenOverride?.let { override ->
            return override(
                eventClassName,
                initialRoot,
                capturePackageName,
                progressPrefix,
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
                "entry_restore_result strategy=restore_to_entry stopReason=${entryResetResult.stopReason.name.lowercase()} " +
                    "matchedExpectedLogical=${expectedEntryLogicalFingerprint != null && entryResetResult.stopReason == EntryScreenResetStopReason.NO_BACK_AFFORDANCE}"
            )
            if (
                entryResetResult.stopReason == EntryScreenResetStopReason.NO_BACK_AFFORDANCE &&
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

        repeat(maxForegroundCaptureAttempts) { attempt ->
            val relaunchedRoot = host.captureCurrentRootSnapshot(selectedApp.packageName) ?: return@repeat
            val entryResetResult = normalizeRootToEntryScreen(
                targetPackageName = selectedApp.packageName,
                initialRoot = relaunchedRoot,
                expectedEntryLogicalFingerprint = expectedEntryLogicalFingerprint,
            )
            crawlLogger?.info(
                "entry_restore_relaunch_attempt attempt=${attempt + 1}/$maxForegroundCaptureAttempts " +
                    "stopReason=${entryResetResult.stopReason.name.lowercase()}"
            )
            if (entryResetResult.stopReason == EntryScreenResetStopReason.NO_BACK_AFFORDANCE) {
                crawlLogger?.info("entry_restore_result strategy=relaunch success=true")
                return entryResetResult.root
            }
        }

        crawlLogger?.warn("entry_restore_result strategy=relaunch success=false message=${quote("No entry screen root was captured after relaunch.")}")
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
                tracker = tracker,
                rootSnapshot = rootSnapshot,
                rootFiles = rootFiles,
                manifestFile = manifestFile,
            ),
            message = message,
        )
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
        return CaptureFileStore.saveManifest(
            session = session,
            manifest = tracker.buildManifest(status = status, finishedAt = timeProvider()),
        )
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
            "childIndexPath=${step.childIndexPath} firstSeenStep=${step.firstSeenStep}"
    }

    private fun formatElement(element: PressableElement): String {
        return "label=${quote(element.label)} resourceId=${quote(element.resourceId.orEmpty())} " +
            "className=${quote(element.className.orEmpty())} bounds=${quote(element.bounds)} " +
            "childIndexPath=${element.childIndexPath} firstSeenStep=${element.firstSeenStep}"
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
        private const val maxForegroundCaptureAttempts = 4
    }
}
