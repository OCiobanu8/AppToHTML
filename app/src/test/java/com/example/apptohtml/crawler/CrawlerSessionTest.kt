package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrawlerSessionTest {
    @Suppress("UNCHECKED_CAST")
    private val uiStateFlow: MutableStateFlow<CrawlerUiState>
        get() = uiStateField.get(CrawlerSession) as MutableStateFlow<CrawlerUiState>

    @After
    fun tearDown() {
        uiStateFlow.value = CrawlerUiState.idle()
        timeoutJobField.set(CrawlerSession, null)
        pendingPauseDecisionField.set(CrawlerSession, null)
        pendingPauseDecisionIdField.set(CrawlerSession, null)
        nextPauseDecisionIdField.setLong(CrawlerSession, 1L)
    }

    @Test
    fun claimScanning_transitionsWaitingRequestOnlyOnce() {
        uiStateFlow.value = waitingState(requestId = 1001L)

        assertTrue(
            CrawlerSession.claimScanning(
                requestId = 1001L,
                message = "Resetting to the first screen.",
            )
        )
        assertEquals(CrawlerPhase.SCANNING_TARGET_SCREEN, CrawlerSession.currentState().phase)
        assertFalse(
            CrawlerSession.claimScanning(
                requestId = 1001L,
                message = "A second caller should not re-claim scanning.",
            )
        )
    }

    @Test
    fun claimScanning_rejectsMismatchedRequestId() {
        uiStateFlow.value = waitingState(requestId = 2002L)

        assertFalse(
            CrawlerSession.claimScanning(
                requestId = 9999L,
                message = "This request should be rejected.",
            )
        )
        assertEquals(CrawlerPhase.WAITING_FOR_TARGET_SCREEN, CrawlerSession.currentState().phase)
    }

    @Test
    fun pauseForDecision_switches_phase_and_awaits_decision() = runBlocking {
        uiStateFlow.value = traversingState(requestId = 3003L)

        val pauseResult = async {
            CrawlerSession.pauseForDecision(
                reason = PauseReason.ELAPSED_TIME_EXCEEDED,
                snapshot = PauseProgressSnapshot(
                    elapsedTimeMs = 10_000L,
                    capturedScreenCount = 4,
                    capturedChildScreenCount = 3,
                    failedEdgeCount = 1,
                ),
            )
        }

        waitForPause()
        val pausedState = CrawlerSession.currentState()
        val decisionId = pausedState.pauseDecisionId

        assertEquals(CrawlerPhase.PAUSED_FOR_DECISION, pausedState.phase)
        assertEquals(PauseReason.ELAPSED_TIME_EXCEEDED, pausedState.pauseReason)
        assertEquals(10_000L, pausedState.pauseElapsedTimeMs)
        assertEquals(1, pausedState.pauseFailedEdgeCount)
        assertEquals(4, pausedState.pausedCapturedScreenCount)
        assertEquals(3, pausedState.pausedCapturedChildScreenCount)
        assertTrue(decisionId != null)
        assertFalse(pauseResult.isCompleted)

        CrawlerSession.resumeCrawl(requestId = 3003L, decisionId = decisionId!!)

        assertEquals(PauseDecision.CONTINUE, pauseResult.await())
        val resumedState = CrawlerSession.currentState()
        assertEquals(CrawlerPhase.TRAVERSING_CHILD_SCREENS, resumedState.phase)
        assertNull(resumedState.pauseDecisionId)
        assertNull(resumedState.pauseReason)
    }

    @Test
    fun stopAndSave_completes_deferred_with_stop() = runBlocking {
        uiStateFlow.value = traversingState(requestId = 4004L)

        val pauseResult = async {
            CrawlerSession.pauseForDecision(
                reason = PauseReason.FAILED_EDGE_COUNT_EXCEEDED,
                snapshot = PauseProgressSnapshot(
                    elapsedTimeMs = 12_000L,
                    capturedScreenCount = 2,
                    capturedChildScreenCount = 1,
                    failedEdgeCount = 5,
                ),
            )
        }

        waitForPause()
        val decisionId = CrawlerSession.currentState().pauseDecisionId

        CrawlerSession.stopAndSave(requestId = 4004L, decisionId = decisionId!!)

        assertEquals(PauseDecision.STOP, pauseResult.await())
        assertEquals(
            "Stopping deep crawl and saving current progress.",
            CrawlerSession.currentState().statusMessage,
        )
        assertNull(CrawlerSession.currentState().pauseDecisionId)
    }

    @Test
    fun skipExternalEdge_completes_deferred_with_skip_edge() = runBlocking {
        uiStateFlow.value = traversingState(requestId = 5005L)

        val pauseResult = async {
            CrawlerSession.pauseForDecision(
                reason = PauseReason.EXTERNAL_PACKAGE_BOUNDARY,
                snapshot = PauseProgressSnapshot(
                    elapsedTimeMs = 15_000L,
                    capturedScreenCount = 6,
                    capturedChildScreenCount = 5,
                    failedEdgeCount = 0,
                ),
                externalPackageContext = ExternalPackageDecisionContext(
                    currentPackageName = "com.example.target",
                    nextPackageName = "com.google.android.googlequicksearchbox",
                    parentScreenId = "screen_00000",
                    parentScreenName = "Home",
                    triggerLabel = "Open Google",
                ),
            )
        }

        waitForPause()
        val pausedState = CrawlerSession.currentState()
        val decisionId = pausedState.pauseDecisionId

        assertEquals("com.example.target", pausedState.pauseCurrentPackageName)
        assertEquals("com.google.android.googlequicksearchbox", pausedState.pauseNextPackageName)
        assertEquals("Open Google", pausedState.pauseTriggerLabel)

        CrawlerSession.skipExternalEdge(requestId = 5005L, decisionId = decisionId!!)

        assertEquals(PauseDecision.SKIP_EDGE, pauseResult.await())
        assertEquals(CrawlerPhase.TRAVERSING_CHILD_SCREENS, CrawlerSession.currentState().phase)
        assertNull(CrawlerSession.currentState().pauseDecisionId)
    }

    @Test
    fun stale_pause_decision_id_is_ignored() = runBlocking {
        uiStateFlow.value = traversingState(requestId = 6006L)

        val firstPause = async {
            CrawlerSession.pauseForDecision(
                reason = PauseReason.ELAPSED_TIME_EXCEEDED,
                snapshot = PauseProgressSnapshot(
                    elapsedTimeMs = 8_000L,
                    capturedScreenCount = 1,
                    capturedChildScreenCount = 0,
                    failedEdgeCount = 0,
                ),
            )
        }

        waitForPause()
        val firstDecisionId = CrawlerSession.currentState().pauseDecisionId!!
        CrawlerSession.resumeCrawl(requestId = 6006L, decisionId = firstDecisionId)
        assertEquals(PauseDecision.CONTINUE, firstPause.await())

        val secondPause = async {
            CrawlerSession.pauseForDecision(
                reason = PauseReason.FAILED_EDGE_COUNT_EXCEEDED,
                snapshot = PauseProgressSnapshot(
                    elapsedTimeMs = 16_000L,
                    capturedScreenCount = 3,
                    capturedChildScreenCount = 2,
                    failedEdgeCount = 4,
                ),
            )
        }

        waitForPause()
        val secondDecisionId = CrawlerSession.currentState().pauseDecisionId!!
        assertNotEquals(firstDecisionId, secondDecisionId)

        CrawlerSession.resumeCrawl(requestId = 6006L, decisionId = firstDecisionId)

        assertFalse(secondPause.isCompleted)
        assertEquals(CrawlerPhase.PAUSED_FOR_DECISION, CrawlerSession.currentState().phase)

        CrawlerSession.resumeCrawl(requestId = 6006L, decisionId = secondDecisionId)
        assertEquals(PauseDecision.CONTINUE, secondPause.await())
    }

    private fun waitingState(requestId: Long): CrawlerUiState {
        return CrawlerUiState.idle().copy(
            phase = CrawlerPhase.WAITING_FOR_TARGET_SCREEN,
            selectedApp = selectedApp(),
            requestId = requestId,
            statusMessage = "Waiting for target screen.",
        )
    }

    private fun traversingState(requestId: Long): CrawlerUiState {
        return CrawlerUiState.idle().copy(
            phase = CrawlerPhase.TRAVERSING_CHILD_SCREENS,
            selectedApp = selectedApp(),
            requestId = requestId,
            statusMessage = "Traversing child screens.",
        )
    }

    private suspend fun waitForPause() {
        repeat(20) {
            if (CrawlerSession.currentState().phase == CrawlerPhase.PAUSED_FOR_DECISION) {
                return
            }
            yield()
        }
        assertEquals(CrawlerPhase.PAUSED_FOR_DECISION, CrawlerSession.currentState().phase)
    }

    private fun selectedApp(): SelectedAppRef {
        return SelectedAppRef(
            packageName = "com.example.target",
            appName = "Target",
            launcherActivity = "com.example.target.MainActivity",
            selectedAt = 123L,
        )
    }

    private companion object {
        private val uiStateField = CrawlerSession::class.java.getDeclaredField("_uiState").apply {
            isAccessible = true
        }
        private val timeoutJobField = CrawlerSession::class.java.getDeclaredField("timeoutJob").apply {
            isAccessible = true
        }
        private val pendingPauseDecisionField = CrawlerSession::class.java.getDeclaredField("pendingPauseDecision").apply {
            isAccessible = true
        }
        private val pendingPauseDecisionIdField = CrawlerSession::class.java.getDeclaredField("pendingPauseDecisionId").apply {
            isAccessible = true
        }
        private val nextPauseDecisionIdField = CrawlerSession::class.java.getDeclaredField("nextPauseDecisionId").apply {
            isAccessible = true
        }
    }
}
