package com.example.apptohtml.model

data class SelectedAppRef(
    val packageName: String,
    val appName: String,
    val launcherActivity: String,
    val selectedAt: Long,
)
