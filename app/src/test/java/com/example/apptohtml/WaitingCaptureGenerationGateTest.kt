package com.example.apptohtml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingCaptureGenerationGateTest {
    @Test
    fun latestScheduledGenerationWins() {
        val gate = WaitingCaptureGenerationGate()

        val firstGeneration = gate.scheduleNextAttempt()
        val secondGeneration = gate.scheduleNextAttempt()

        assertFalse(gate.isCurrent(firstGeneration))
        assertTrue(gate.isCurrent(secondGeneration))
    }
}
