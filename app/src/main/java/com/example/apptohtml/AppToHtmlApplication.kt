package com.example.apptohtml

import android.app.Application
import com.example.apptohtml.diagnostics.DiagnosticLogger

class AppToHtmlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.init(this)
    }
}
