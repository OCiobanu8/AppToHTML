package com.example.apptohtml.crawler

import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun waitingState(requestId: Long): CrawlerUiState {
        return CrawlerUiState.idle().copy(
            phase = CrawlerPhase.WAITING_FOR_TARGET_SCREEN,
            selectedApp = selectedApp(),
            requestId = requestId,
            statusMessage = "Waiting for target screen.",
        )
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
    }
}
