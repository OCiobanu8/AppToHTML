package com.example.apptohtml.crawler

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class CrawlLogger(
    private val sessionId: String,
    private val logFile: File,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    fun info(message: String) {
        append("INFO", message)
    }

    fun warn(message: String) {
        append("WARN", message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        append("ERROR", message)
        throwable?.let { error ->
            append("ERROR", "throwableClass=${error.javaClass.name} throwableMessage=${error.message.orEmpty()}")
            error.stackTraceToString()
                .trimEnd()
                .lineSequence()
                .forEach { line ->
                    append("ERROR", "stacktrace=$line")
                }
        }
    }

    @Synchronized
    private fun append(level: String, message: String) {
        logFile.parentFile?.mkdirs()
        logFile.appendText("${timestamp()} [$level] session=$sessionId $message\n", Charsets.UTF_8)
    }

    private fun timestamp(): String = timestampFormat.format(Date(timeProvider()))

    private companion object {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
