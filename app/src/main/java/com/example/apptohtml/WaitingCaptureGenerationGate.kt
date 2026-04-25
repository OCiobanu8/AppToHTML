package com.example.apptohtml

import java.util.concurrent.atomic.AtomicLong

internal class WaitingCaptureGenerationGate {
    private val latestGeneration = AtomicLong(0L)

    fun scheduleNextAttempt(): Long = latestGeneration.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = latestGeneration.get() == generation
}
