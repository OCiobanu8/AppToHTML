package com.example.apptohtml.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val TAG = "AppToHtmlDiag"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var diagnosticsDir: File? = null

    fun init(context: Context) {
        diagnosticsDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        installUncaughtHandler()
        log("Diagnostic logger initialized")
    }

    fun log(message: String) {
        Log.i(TAG, message)
        appendLine("diag.log", "${timestamp()} [INFO] $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        appendLine("diag.log", "${timestamp()} [ERROR] $message${throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val crashFile = "crash_${fileStampFormat.format(Date())}.log"
                appendLine(crashFile, "${timestamp()} [CRASH] Thread=${thread.name}")
                appendLine(crashFile, throwable.stackTraceToString())
                appendLine("diag.log", "${timestamp()} [CRASH] ${throwable.javaClass.simpleName}: ${throwable.message}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    private fun appendLine(fileName: String, line: String) {
        val dir = diagnosticsDir ?: return
        runCatching {
            File(dir, fileName).appendText(line + "\n")
        }
    }

    private fun timestamp(): String = timestampFormat.format(Date())
}
