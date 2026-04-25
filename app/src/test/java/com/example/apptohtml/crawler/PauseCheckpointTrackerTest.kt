package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseCheckpointTrackerTest {
    private var nowMs = 0L

    @Test
    fun nextTriggeredReason_returns_elapsed_time_when_time_budget_exceeded() {
        val tracker = tracker(
            config = PauseCheckpointConfig(
                initialTimeThresholdMs = 1_000L,
                subsequentTimeThresholdMs = 500L,
                initialFailedEdgeThreshold = 3,
                subsequentFailedEdgeThreshold = 2,
            ),
            startedAtMs = 100L,
        )

        nowMs = 1_100L

        assertEquals(PauseReason.ELAPSED_TIME_EXCEEDED, tracker.nextTriggeredReason())
    }

    @Test
    fun nextTriggeredReason_returns_failed_edge_count_when_edge_budget_exceeded() {
        val tracker = tracker(
            config = PauseCheckpointConfig(
                initialTimeThresholdMs = 5_000L,
                subsequentTimeThresholdMs = 1_000L,
                initialFailedEdgeThreshold = 2,
                subsequentFailedEdgeThreshold = 1,
            ),
            startedAtMs = 0L,
        )

        tracker.recordFailedEdge()
        assertNull(tracker.nextTriggeredReason())

        tracker.recordFailedEdge()

        assertEquals(PauseReason.FAILED_EDGE_COUNT_EXCEEDED, tracker.nextTriggeredReason())
    }

    @Test
    fun rollForwardAfterContinue_advances_only_the_relevant_budget() {
        val tracker = tracker(
            config = PauseCheckpointConfig(
                initialTimeThresholdMs = 1_000L,
                subsequentTimeThresholdMs = 500L,
                initialFailedEdgeThreshold = 2,
                subsequentFailedEdgeThreshold = 3,
            ),
            startedAtMs = 0L,
        )

        nowMs = 1_000L
        assertEquals(PauseReason.ELAPSED_TIME_EXCEEDED, tracker.nextTriggeredReason())

        tracker.rollForwardAfterContinue(PauseReason.ELAPSED_TIME_EXCEEDED)

        nowMs = 1_400L
        assertNull(tracker.nextTriggeredReason())

        nowMs = 1_500L
        assertEquals(PauseReason.ELAPSED_TIME_EXCEEDED, tracker.nextTriggeredReason())

        tracker.recordFailedEdge()
        tracker.recordFailedEdge()
        assertEquals(PauseReason.ELAPSED_TIME_EXCEEDED, tracker.nextTriggeredReason())

        nowMs = 1_499L
        assertEquals(PauseReason.FAILED_EDGE_COUNT_EXCEEDED, tracker.nextTriggeredReason())

        tracker.rollForwardAfterContinue(PauseReason.FAILED_EDGE_COUNT_EXCEEDED)

        assertNull(tracker.nextTriggeredReason())

        tracker.recordFailedEdge()
        tracker.recordFailedEdge()
        tracker.recordFailedEdge()
        assertEquals(PauseReason.FAILED_EDGE_COUNT_EXCEEDED, tracker.nextTriggeredReason())
    }

    private fun tracker(
        config: PauseCheckpointConfig,
        startedAtMs: Long,
    ): PauseCheckpointTracker {
        nowMs = startedAtMs
        return PauseCheckpointTracker(
            config = config,
            startedAtMs = startedAtMs,
            timeProvider = { nowMs },
        )
    }
}
